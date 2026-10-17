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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 央视频 JCE 时移协议（主协议）：向 jacc.ysp.cctv.cn 提交 TEA 加密 + gzip 的
 * JCE 请求包，换取指定频道的时移直播 m3u8 地址。返回的播放列表分片为**明文 TS**，
 * 播放器可直接拉取，无需解密（这是原生播放零卡顿的关键）。
 *
 * 移植自 funwukong/ysp-live（Python），JCE 读写器与打包格式逐字节对齐。
 */
object YspJceProtocol {

    private const val TAG = "CCTV_JCE"

    private const val JCE_URL = "https://jacc.ysp.cctv.cn"
    private const val CMD_TIMESHIFT = 25312
    private const val VER_NAME = "3.2.7.26212"
    private const val VER_CODE = "302070"
    private const val APP_ID = "1200013"
    private const val QMF_APP_ID = 10012
    private const val QMF_PLATFORM = 1
    private const val BIZ_ID = 0L
    private const val CHAN_ID = "10070"
    private const val QMF_SCENE = 531
    private const val DEFN = "fhd"

    /** 无效 CDN 域名标志：出现即切换 bkliveinfo 备用协议 */
    private const val DEAD_HOST_MARK = "liverecord.video.cloud.cctv.com"

    /** 时移窗口（秒）：取最近 3 分钟的切片窗口 */
    private const val WINDOW_SEC = 180

    // ==================== JCE 写入器 ====================

    private class JceWriter {
        private val b = ByteArrayOutputStream()

        fun head(typ: Int, tag: Int) {
            if (tag < 15) {
                b.write(((tag and 0xf) shl 4) or (typ and 0xf))
            } else {
                b.write(0xf0 or (typ and 0xf))
                b.write(tag)
            }
        }

        fun byte(v: Int, tag: Int) {
            if (v == 0) head(12, tag) else {
                head(0, tag)
                b.write(v and 0xff)
            }
        }

        fun short(v: Int, tag: Int) {
            if (v in -128..127) byte(v, tag) else {
                head(1, tag)
                b.write((v ushr 8) and 0xff)
                b.write(v and 0xff)
            }
        }

        fun int(v: Int, tag: Int) {
            if (v in -32768..32767) short(v, tag) else {
                head(2, tag)
                b.write(YspCrypto.be32Bytes(v.toUInt()))
            }
        }

        fun long(v: Long, tag: Int) {
            if (v in Int.MIN_VALUE..Int.MAX_VALUE) int(v.toInt(), tag) else {
                head(3, tag)
                b.write(YspCrypto.be64Bytes(v))
            }
        }

        fun float(v: Float, tag: Int) {
            head(4, tag)
            b.write(YspCrypto.be32Bytes(java.lang.Float.floatToIntBits(v).toUInt()))
        }

        fun double(v: Double, tag: Int) {
            head(5, tag)
            b.write(YspCrypto.be64Bytes(java.lang.Double.doubleToLongBits(v)))
        }

        fun string(s: String?, tag: Int) {
            if (s == null) return
            val data = s.toByteArray(Charsets.UTF_8)
            if (data.size > 255) {
                head(7, tag)
                b.write(YspCrypto.be32Bytes(data.size.toUInt()))
                b.write(data)
            } else {
                head(6, tag)
                b.write(data.size)
                b.write(data)
            }
        }

        fun bytes(data: ByteArray, tag: Int) {
            head(13, tag)
            head(0, 0)
            int(data.size, 0)
            b.write(data)
        }

        fun struct(fn: (JceWriter) -> Unit, tag: Int) {
            head(10, tag)
            fn(this)
            head(11, 0)
        }

        fun list(count: Int, tag: Int) {
            head(9, tag)
            int(count, 0)
        }

        fun out(): ByteArray = b.toByteArray()
    }

    // ==================== JCE 读取器 ====================

    private class JceReader(private val d: ByteArray) {
        private var p = 0

        fun rem() = d.size - p

        fun u8(): Int {
            if (p >= d.size) throw java.io.EOFException("JCE truncated")
            return d[p++].toInt() and 0xff
        }

        fun head(): Pair<Int, Int> {
            val b = u8()
            var typ = b and 0xf
            var tag = (b and 0xf0) shr 4
            if (tag == 15) tag = u8()
            return typ to tag
        }

        fun get(n: Int): ByteArray {
            if (p + n > d.size) throw java.io.EOFException("JCE truncated")
            val out = d.copyOfRange(p, p + n)
            p += n
            return out
        }

        fun value(typ: Int): Any? {
            return when (typ) {
                0 -> get(1)[0].toInt()
                1 -> { val b = get(2); ((b[0].toInt() shl 8) or (b[1].toInt() and 0xff)).toShort() }
                2 -> YspCrypto.be32(get(4)).toInt()
                3 -> {
                    val hi = YspCrypto.be32(get(4)).toLong()
                    val lo = YspCrypto.be32(get(4)).toLong()
                    (hi shl 32) or lo
                }
                4 -> java.lang.Float.intBitsToFloat(YspCrypto.be32(get(4)).toInt())
                5 -> java.lang.Double.longBitsToDouble(
                    (YspCrypto.be32(get(4)).toLong() shl 32) or YspCrypto.be32(get(4)).toLong()
                )
                6 -> get(u8()).toString(Charsets.UTF_8)
                7 -> { val n = YspCrypto.be32(get(4)).toInt(); get(n).toString(Charsets.UTF_8) }
                8 -> { val n = intValue(); (0 until n).associate { fv() to fv() } }
                9 -> { val n = intValue(); (0 until n).map { fv() } }
                10 -> struct()
                11 -> null
                12 -> 0
                13 -> {
                    head() // 内部 SimpleList 头
                    val n = intValue()
                    get(n)
                }
                else -> throw IllegalArgumentException("JCE type $typ")
            }
        }

        fun fv(): Any? {
            val (t, _) = head()
            return value(t)
        }

        fun intValue(): Int {
            val (t, _) = head()
            val v = value(t)
            return when (v) {
                is Int -> v
                is Short -> v.toInt()
                is Long -> v.toInt()
                is Byte -> v.toInt()
                else -> 0
            }
        }

        fun struct(): Map<Int, Any?> {
            val m = HashMap<Int, Any?>()
            while (rem() > 0) {
                val (t, tag) = head()
                if (t == 11) break
                m[tag] = value(t)
            }
            return m
        }
    }

    // ==================== 打包 ====================

    private val guid: String by lazy {
        val chars = "0123456789abcdef"
        buildString { repeat(32) { append(chars.random()) } }
    }

    private fun qua(w: JceWriter) {
        w.string(VER_NAME, 0)
        w.string(VER_CODE, 1)
        w.int(1080, 2)
        w.int(2400, 3)
        w.int(3, 4)
        w.string("12", 5)
        w.int(1, 6)
        w.int(1, 7)
        w.int(420, 8)
        w.string(CHAN_ID, 9)
        for (i in 10..14) w.string("", i)
        w.struct({ ww ->
            ww.int(0, 0); ww.byte(0, 1); ww.string("", 2)
        }, 15)
        w.string("", 16); w.string("", 17); w.string("", 18)
        w.struct({ ww ->
            ww.int(0, 0); ww.float(0f, 1); ww.float(0f, 2); ww.double(0.0, 3)
        }, 19)
        w.string(guid.take(16), 20)
        w.string("Pixel 6", 21)
        w.int(1, 22)
        for (i in 23..26) w.int(0, i)
        w.string("", 27); w.string("", 28)
        w.string(guid, 29)
    }

    private fun head(w: JceWriter, cmd: Int, reqid: Int) {
        w.int(reqid, 0)
        w.int(cmd, 1)
        w.struct(::qua, 2)
        w.string(APP_ID, 3)
        w.string(guid, 4)
        w.list(0, 5)
        w.struct({}, 6)
        w.list(0, 7)
        w.int(0, 8); w.int(0, 9); w.int(0, 10)
    }

    private fun wrap(cmd: Int, body: ByteArray, reqid: Int): ByteArray {
        val w = JceWriter()
        w.struct({ ww -> head(ww, cmd, reqid) }, 0)
        w.bytes(body, 1)
        val reqcmd = w.out()

        val inner = ByteArrayOutputStream()
        inner.write(38) // 0x26
        inner.write(YspCrypto.be32Bytes((reqcmd.size + 17).toUInt()))
        inner.write(1)
        inner.write(ByteArray(10))
        inner.write(reqcmd)
        inner.write(40) // 0x28
        val comp = YspCrypto.gzip(inner.toByteArray())

        val out = ByteArrayOutputStream()
        out.write(19) // 0x13
        out.write(YspCrypto.be32Bytes(0u))
        out.write(YspCrypto.be16Bytes(2))
        out.write(YspCrypto.be16Bytes(65281))
        out.write(YspCrypto.be16Bytes(cmd))
        out.write(YspCrypto.be16Bytes(0))
        out.write(YspCrypto.be64Bytes(reqid.toLong()))
        out.write(YspCrypto.be32Bytes(QMF_SCENE.toUInt()))
        out.write(YspCrypto.be32Bytes(QMF_APP_ID.toUInt()))
        out.write(YspCrypto.be64Bytes(BIZ_ID))
        val g = guid.toByteArray(Charsets.US_ASCII)
        out.write(g)
        out.write(ByteArray(32 - g.size))
        out.write(QMF_PLATFORM)
        out.write(YspCrypto.be32Bytes(VER_CODE.toInt().toUInt()))
        out.write(ByteArray(6))
        out.write(0)
        out.write(YspCrypto.be16Bytes(0))
        out.write(YspCrypto.be16Bytes(0))
        out.write(YspCrypto.be32Bytes(inner.size().toUInt()))
        out.write(comp)
        out.write(3) // 0x03

        val pkt = out.toByteArray()
        // 包长写入 offset 1..5
        YspCrypto.putBe32(pkt.size.toUInt(), pkt, 1)
        return pkt
    }

    private fun unwrap(data: ByteArray): ByteArray {
        if (data.isEmpty() || data[0].toInt() != 0x13 || data.size < 90) {
            throw IllegalStateException("bad JCE frame")
        }
        val flags = YspCrypto.be32(data, 21).toInt()
        var payload = data.copyOfRange(89, data.size - 1)
        if (flags and 2 != 0) payload = YspCrypto.gunzip(payload)
        if (payload.isEmpty() || payload[0].toInt() != 0x26 || payload.last().toInt() != 0x28) {
            throw IllegalStateException("bad JCE payload")
        }
        val rc = JceReader(payload.copyOfRange(16, payload.size - 1)).struct()
        return (rc[1] as? ByteArray) ?: ByteArray(0)
    }

    /**
     * 获取指定频道的时移直播 m3u8 地址。
     *
     * @param pid    直播 pid（如 600001859）
     * @param sid    流 id / cnlid（如 2024078201）
     * @param start  时移窗口起点（unix 秒）
     * @param end    时移窗口终点（unix 秒）
     * @return m3u8 URL；若返回死域名则抛 [DeadHostException]
     */
    @Throws(DeadHostException::class)
    fun fetchTimeshiftUrl(pid: String, sid: String, start: Long, end: Long): String {
        val w = JceWriter()
        w.string(pid, 0)
        w.string(sid, 1)
        w.long(start, 2)
        w.long(end, 3)
        w.string(DEFN, 4)
        val body = w.out()

        val reqid = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val packet = wrap(CMD_TIMESHIFT, body, reqid)

        var connection: HttpURLConnection? = null
        try {
            connection = URL(JCE_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(packet) }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("JCE HTTP $code")
            }
            val raw = connection.inputStream.use { it.readBytes() }
            val respBody = unwrap(raw)
            if (respBody.isEmpty()) throw IllegalStateException("empty JCE body")

            val m = JceReader(respBody).struct()
            val err = (m[0] as? Number)?.toInt() ?: 0
            if (err != 0) {
                val msg = (m[1] as? String) ?: "errCode=$err"
                throw IllegalStateException("JCE error $msg")
            }
            val url = (m[2] as? String) ?: ""
            if (url.isEmpty()) throw IllegalStateException("empty m3u8 url")
            if (url.contains(DEAD_HOST_MARK)) {
                Log.w(TAG, "dead CDN host, switch to bkliveinfo: ${url.take(80)}")
                throw DeadHostException(url)
            }
            return url
        } finally {
            connection?.disconnect()
        }
    }

    /** 取当前时刻之前的 [WINDOW_SEC] 秒窗口的时移地址 */
    fun fetchTimeshiftUrl(pid: String, sid: String): String {
        val now = System.currentTimeMillis() / 1000
        return fetchTimeshiftUrl(pid, sid, now - WINDOW_SEC, now)
    }

    /** JCE 返回死域名（需切换 bkliveinfo 备用协议） */
    class DeadHostException(url: String) : Exception("dead CDN host: ${url.take(60)}")
}
