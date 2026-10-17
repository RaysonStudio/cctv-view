/*
 * Copyright 2026 Rayson Studio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.raysonstudio.cctv_view

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * 应用内本地 HLS 代理：把央视频直播流变成 ExoPlayer 可直接播放的本地 m3u8。
 *
 * 设计（与 funwukong/ysp-live 一致）：
 * - 在 127.0.0.1 上起一个极简 HTTP 服务，只下发 m3u8 播放列表；
 * - 分片 URL 全部指向央视 CDN 明文 TS，由 ExoPlayer 直连拉取（代理不跑视频流量）；
 * - JCE 时移协议为主：后台每 3 秒刷新 180 秒窗口，滑窗合成滚动直播列表；
 * - JCE 返回死域名或频道在黑名单时自动切换 bkliveinfo + cKey 备用协议。
 *
 * 重要：bkliveinfo 返回的播放列表地址是**一次性令牌**（首次抓取 200，再次 403），
 * 因此 bk 模式**每个刷新周期都重新请求 bkliveinfo 换新地址**，并把分片并入滑窗，
 * 与 JCE 模式共用同一套滚动列表合成逻辑。
 *
 * 这取代了「WebView 网页里跑 hls.js」的旧方案：无 JS、无 WASM、无逐片解密，
 * 视频由系统播放器硬件解码，是零卡顿的根本保证。
 */
object YspPlaylistServer {

    private const val TAG = "CCTV_PROXY"

    private const val REFRESH_MS_JCE = 3000L      // JCE 模式刷新间隔
    private const val REFRESH_MS_BK = 6000L       // bk 模式刷新间隔（地址一次性，需节制频率）
    private const val MAX_SEGS = 150              // 滑窗最大分片数（覆盖 2s 小分片流约 5 分钟窗口）
    private const val READ_TIMEOUT_MS = 15000

    // 官方移动端协议 UA（bkliveinfo 播放列表用）
    private const val QQ_LIVE_UA = "qqlive"
    // 桌面 Chrome UA（JCE 时移播放列表用）
    private const val CHROME_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private const val HLS_ACCEPT = "application/vnd.apple.mpegurl,application/json,*/*"

    /** 强制走 bkliveinfo 的频道（1-based，JCE 对这些频道稳定返回死域名） */
    private val FORCE_BK = setOf(11, 12, 14, 15, 16, 17, 18, 19)

    private class Segment(val dur: Double, val pdt: String, val url: String)

    private class ChannelState(
        val index: Int,      // 1-based
        val pid: String,
        @Volatile var sid: String
    ) {
        val lock = Any()
        @Volatile var mode: String = "jce"        // jce | bk
        val segments = LinkedHashMap<String, Segment>() // key -> seg
        val order = ArrayDeque<String>()
        var seq = 0
        var lastRefresh = 0L
        @Volatile var lastError = ""
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 本地代理端口（0 表示未启动） */
    @Volatile var port: Int = 0
        private set

    private val channels = HashMap<Int, ChannelState>()

    /** 初始化频道表（从 ChannelManager 读取 pid/sid） */
    fun init() {
        for (i in 1..ChannelManager.totalChannels) {
            val pid = ChannelManager.getChannelPid(i)
            val sid = ChannelManager.getChannelSid(i)
            val st = ChannelState(i, pid, sid)
            if (i in FORCE_BK) st.mode = "bk"
            channels[i] = st
        }
    }

    fun start() {
        if (serverSocket != null) return
        init()
        try {
            val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            acceptThread = Thread({
                while (true) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        break
                    }
                    // 每连接一线程：分片流式转发会占住连接，必须并发处理
                    Thread({
                        try {
                            handle(socket)
                        } catch (e: Exception) {
                            Log.w(TAG, "handle error: ${e.message}")
                        } finally {
                            try { socket.close() } catch (e: Exception) {}
                        }
                    }, "ysp-conn").apply { isDaemon = true }.start()
                }
            }, "ysp-hls-proxy").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "local HLS proxy started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "proxy start failed: ${e.message}")
            serverSocket = null
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        acceptThread = null
        port = 0
    }

    /** 播放器用的本地 m3u8 地址 */
    fun playlistUrl(channelIndex: Int): String = "http://127.0.0.1:$port/ch$channelIndex.m3u8"

    /** sid 后台刷新后热更新对应频道的流 id */
    fun updateChannelSid(channelIndex: Int, sid: String) {
        channels[channelIndex]?.let { st ->
            synchronized(st.lock) {
                st.sid = sid
                // 清理旧分片，下次刷新用新 sid 重新取流
                st.segments.clear()
                st.order.clear()
                st.seq = 0
            }
        }
    }

    // ==================== HTTP 服务 ====================

    private fun handle(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        val requestLine = reader.readLine() ?: return
        // 丢弃其余请求头
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }

        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val fullPath = parts[1]
        val path = fullPath.substringBefore('?')
        val query = fullPath.substringAfter('?', "")

        var body: ByteArray?
        var status = 404
        var contentType = "text/plain; charset=utf-8"

        if (path == "/health") {
            body = "ok".toByteArray()
            status = 200
        } else if (path == "/seg") {
            // 分片代理：ExoPlayer 直连部分 CDN 会被 403（UA/Referer 指纹），
            // 统一走本地代理用已验证的头组合抓取。
            val target = java.net.URLDecoder.decode(
                query.substringAfter("u=", ""), "UTF-8"
            )
            if (target.startsWith("http")) {
                streamSegment(socket, target)
                return
            } else {
                body = "bad url\n".toByteArray()
            }
        } else if (path.startsWith("/test") && BuildConfig.DEBUG) {
            // 诊断端点（仅 debug 构建）：/test?url=<encoded> 或 /test?jce=pid,sid
            if (query.startsWith("jce=")) {
                val pair = query.removePrefix("jce=").split(',')
                val sb = StringBuilder()
                if (pair.size == 2) {
                    repeat(2) {
                        val jurl = YspJceProtocol.fetchTimeshiftUrl(pair[0], pair[1])
                        val t = fetchText(jurl, CHROME_UA)
                        val names = parseSegments(t, jurl).take(3)
                            .joinToString(" | ") { it.url.substringAfterLast('/').substringBefore('?') }
                        sb.append("names=[$names]\n")
                        Thread.sleep(2500)
                    }
                }
                body = sb.toString().toByteArray(Charsets.UTF_8)
            } else {
                val q = java.net.URLDecoder.decode(query, "UTF-8")
                val target = java.net.URLDecoder.decode(
                    q.substringAfter("url=", "").substringBefore('&'), "UTF-8"
                )
                val variant = q.substringAfter("variant=", "none").substringBefore('&')
                val r1 = try {
                    val t = fetchText(target, QQ_LIVE_UA,
                        if (variant == "noaccept") null else HLS_ACCEPT,
                        referer = variant != "noreferer")
                    "OK len=${t.length} head=${t.take(40).replace("\n", "|")}"
                } catch (e: Exception) { "FAIL ${e.javaClass.simpleName}: ${e.message?.take(200)}" }
                body = "$variant=$r1\n".toByteArray(Charsets.UTF_8)
            }
            status = 200
            contentType = "text/plain; charset=utf-8"
        } else {
            val m = Regex("^/ch(\\d+)\\.m3u8$").find(path)
            if (m != null) {
                val idx = m.groupValues[1].toIntOrNull() ?: 0
                val st = channels[idx]
                if (st != null) {
                    // 首个请求同步取流（避免首次 503 后再等重试），之后后台刷新
                    val sync = synchronized(st.lock) { st.segments.isEmpty() }
                    ensureFresh(st, sync)
                    val playlist = synchronized(st.lock) { buildPlaylist(st) }
                    if (playlist.isNotEmpty()) {
                        body = playlist.toByteArray(Charsets.UTF_8)
                        status = 200
                        contentType = "application/vnd.apple.mpegurl"
                    } else {
                        body = "channel $idx loading (${st.lastError})\n".toByteArray()
                        status = 503
                    }
                } else {
                    body = "unknown channel\n".toByteArray()
                }
            } else {
                body = "not found\n".toByteArray()
            }
        }

        val out = socket.getOutputStream()
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Service Unavailable"}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body!!.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    // ==================== 刷新逻辑 ====================

    private fun ensureFresh(st: ChannelState, sync: Boolean) {
        val now = System.currentTimeMillis()
        val interval = if (st.mode == "bk") REFRESH_MS_BK else REFRESH_MS_JCE
        if (now - st.lastRefresh < interval) return
        st.lastRefresh = now
        if (sync) {
            doRefresh(st)
        } else {
            Thread({ doRefresh(st) }, "ysp-refresh-${st.index}").apply { isDaemon = true }.start()
        }
    }

    private fun doRefresh(st: ChannelState) {
        try {
            if (st.mode == "bk") {
                refreshBk(st)
            } else {
                try {
                    refreshJce(st)
                } catch (e: YspJceProtocol.DeadHostException) {
                    Log.w(TAG, "[ch${st.index}] JCE dead host -> switch to bkliveinfo")
                    st.mode = "bk"
                    refreshBk(st)
                }
            }
        } catch (e: Exception) {
            synchronized(st.lock) { st.lastError = "${e.javaClass.simpleName}: ${e.message}".take(120) }
            Log.w(TAG, "[ch${st.index}] refresh failed: ${st.lastError}")
        }
    }

    /** JCE：拉取 180s 时移窗口，把新分片并入滑窗 */
    private fun refreshJce(st: ChannelState) {
        val url = YspJceProtocol.fetchTimeshiftUrl(st.pid, st.sid)
        val text = fetchText(url, CHROME_UA)
        val segs = parseSegments(text, url)
        if (segs.isEmpty()) throw IllegalStateException("empty JCE playlist")
        if (st.segments.isEmpty()) {
            Log.d(TAG, "[ch${st.index}] JCE first window segs=${segs.size} " +
                "first=${segs.first().url.substringAfterLast('/').substringBefore('?')} " +
                "last=${segs.last().url.substringAfterLast('/').substringBefore('?')} " +
                "url=${url.substringAfter("cdn.ysp.cctv.cn/").substringBefore(".m3u8").take(40)}")
        }
        mergeSegments(st, segs, "JCE")
    }

    /**
     * bkliveinfo：播放列表地址一次性有效，因此每轮都重新请求 bkliveinfo
     * 换新地址（2 个地址互为兜底），把新分片并入滑窗。
     */
    private fun refreshBk(st: ChannelState) {
        val urls = YspCKeyProtocol.fetchBkUrls(st.sid, st.pid)
        var lastErr = ""
        for (u in urls) {
            try {
                val pl = fetchAbsPlaylist(u, 0, QQ_LIVE_UA)
                if (!pl.startsWith("#EXTM3U")) continue
                val segs = parseSegments(pl, u)
                if (segs.isEmpty()) throw IllegalStateException("empty bk playlist")
                mergeSegments(st, segs, "BK")
                return
            } catch (e: Exception) {
                lastErr = "${e.javaClass.simpleName}: ${e.message}".take(120)
                Log.w(TAG, "[ch${st.index}] bk fetch fail host=${u.take(45)}: $lastErr")
                continue
            }
        }
        throw IllegalStateException("bk playlist failed: $lastErr")
    }

    /** 新分片并入滑窗（去重 + 序号 + 裁剪窗口） */
    private fun mergeSegments(st: ChannelState, segs: List<Segment>, src: String) {
        var added = 0
        var firstNew = ""
        synchronized(st.lock) {
            for (s in segs) {
                // 去重键：文件名末尾的数字序号（如 xxxx_147791.ts -> 147791）。
                // 注意：部分 CDN（如 mobilelive-playback）每次请求给同一分片加随机中间段，
                // 不能用完整文件名去重；尾部序号才是内容的稳定标识。
                val name = s.url.substringAfterLast('/', s.url).substringBefore('?')
                val m = Regex("(\\d+)\\.ts$").find(name)
                val key = if (m != null) "s:${m.groupValues[1]}" else "f:$name"
                if (st.segments.containsKey(key)) continue
                st.seq++
                st.segments[key] = s
                st.order.addLast(key)
                if (firstNew.isEmpty()) firstNew = name
                added++
            }
            while (st.order.size > MAX_SEGS) {
                val k = st.order.removeFirst()
                st.segments.remove(k)
            }
            st.lastError = ""
        }
        if (added > 0) Log.d(
            TAG, "[ch${st.index}] $src +$added segments (total=${st.segments.size}) firstNew=$firstNew"
        )
    }

    /** 解析 m3u8 分片（EXTINF 时长 / PDT / 绝对 URL） */
    private fun parseSegments(text: String, base: String): List<Segment> {
        val segs = ArrayList<Segment>()
        var dur = 6.0
        var pdt = ""
        for (raw in text.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") ->
                    dur = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 6.0
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") ->
                    pdt = line.substringAfter(':')
                line.isNotEmpty() && !line.startsWith('#') -> {
                    segs.add(Segment(dur, pdt, resolveUrl(base, line)))
                    pdt = ""
                }
            }
        }
        return segs
    }

    /** 处理 master playlist：递归解析到媒体播放列表（最多 2 层） */
    private fun fetchAbsPlaylist(url: String, depth: Int, ua: String): String {
        val text = fetchText(url, ua, HLS_ACCEPT)
        if (depth < 2) {
            val lines = text.split('\n')
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    for (j in i + 1 until lines.size) {
                        val s = lines[j].trim()
                        if (s.isNotEmpty() && !s.startsWith('#')) {
                            return fetchAbsPlaylist(resolveUrl(url, s), depth + 1, ua)
                        }
                    }
                    break
                }
            }
        }
        // 把相对分片 URL 改写为绝对 URL
        val sb = StringBuilder()
        for (line in text.split('\n')) {
            val s = line.trim()
            if (s.isNotEmpty() && !s.startsWith('#')) {
                sb.append(resolveUrl(url, s)).append('\n')
            } else {
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }

    /** 分片代理：从央视 CDN 抓分片并流式转发给播放器 */
    private fun streamSegment(socket: Socket, target: String) {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = URL(target).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", QQ_LIVE_UA)
            connection.setRequestProperty("Referer", "https://live.cctv.cn/")
            connection.setRequestProperty("Accept", "*/*")
            val code = connection.responseCode
            val len = connection.contentLength
            val out = socket.getOutputStream()
            if (code !in 200..299) {
                val errBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(2000)
                } catch (e: Exception) { "" }
                Log.w(TAG, "seg proxy HTTP $code for ${target.take(120)}" +
                    if (!errBody.isNullOrEmpty()) " body=$errBody" else "")
                val msg = "HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(msg.toByteArray(Charsets.US_ASCII))
                out.flush()
                return
            }
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mp2t\r\n" +
                "Content-Length: ${if (len >= 0) len else 0}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            connection.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "seg proxy fail: ${e.message}")
            try {
                val msg = "HTTP/1.1 502 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(msg.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
            } catch (e2: Exception) {}
        } finally {
            connection?.disconnect()
        }
    }

    /** 滑窗合成滚动直播列表（JCE 与 bk 两种模式共用）；分片统一走本地 /seg 代理 */
    private fun buildPlaylist(st: ChannelState): String {
        if (st.segments.isEmpty()) return ""
        val keys = st.order.toList()
        val segs = keys.mapNotNull { st.segments[it] }
        val target = maxOf(6, segs.maxOf { (it.dur + 0.5).toInt() })
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:$target\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:${st.seq - segs.size + 1}\n")
        for (s in segs) {
            if (s.pdt.isNotEmpty()) sb.append("#EXT-X-PROGRAM-DATE-TIME:${s.pdt}\n")
            sb.append("#EXTINF:${"%.3f".format(s.dur)},\n")
            val proxied = "http://127.0.0.1:$port/seg?u=" +
                java.net.URLEncoder.encode(s.url, "UTF-8")
            sb.append(proxied).append('\n')
        }
        return sb.toString()
    }

    private fun fetchText(url: String, ua: String, accept: String? = null, referer: Boolean = true): String {
        var connection: java.net.HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = READ_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", ua)
            if (referer) connection.setRequestProperty("Referer", "https://live.cctv.cn/")
            if (accept != null) connection.setRequestProperty("Accept", accept)
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                } catch (e: Exception) { "" }
                throw IllegalStateException("HTTP $code for $url${if (!body.isNullOrEmpty()) " body=$body" else ""}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    /** 强制 IPv4 + 手动 SNI 抓取（诊断用；Android 14 需 API 24+ 的 serverNames） */
    private fun fetchViaIpv4(url: String): String {
        val u = URL(url)
        val host = u.host
        val addrs = InetAddress.getAllByName(host)
        val ipv4 = addrs.firstOrNull { it is java.net.Inet4Address }
            ?: throw IllegalStateException("no IPv4 address")
        val port = if (u.port > 0) u.port else 443
        val sslContext = javax.net.ssl.SSLContext.getDefault()
        val factory = sslContext.socketFactory
        val sock = factory.createSocket() as javax.net.ssl.SSLSocket
        sock.sslParameters = javax.net.ssl.SSLParameters().apply {
            serverNames = listOf(javax.net.ssl.SNIHostName(host))
        }
        sock.connect(java.net.InetSocketAddress(ipv4, port), READ_TIMEOUT_MS)
        sock.soTimeout = READ_TIMEOUT_MS
        sock.startHandshake()
        val out = sock.outputStream.bufferedWriter(Charsets.US_ASCII)
        out.write("GET ${u.path}?${u.query} HTTP/1.1\r\n")
        out.write("Host: $host\r\n")
        out.write("User-Agent: $QQ_LIVE_UA\r\n")
        out.write("Referer: https://live.cctv.cn/\r\n")
        out.write("Accept: $HLS_ACCEPT\r\n")
        out.write("Connection: close\r\n\r\n")
        out.flush()
        return sock.inputStream.bufferedReader().use { it.readText() }
    }

    private fun resolveUrl(base: String, ref: String): String =
        try { URL(URL(base), ref).toString() } catch (e: Exception) { ref }
}
