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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 后台刷新央视频 TV 频道表的 streamId（cnlid）。
 *
 * streamId 会随央视频后台调整而变化（如高清流改造），App 内置映射可能过时。
 * 本类定时请求 TV 页同款接口 page/PG00000004（protobuf），解析出 pid→streamId
 * 最新映射并更新 [ChannelManager] 与 [YspPlaylistServer]。
 *
 * 接口：GET https://capi.yangshipin.cn/api/oms/pc/page/PG00000004?<ts5s>
 * 响应：cn.yangshipin.oms.common.proto.pageModel.Response
 * 路径：Response.data.feedModuleList[].dataTvChannelList[] -> { pid=4, streamId=6 }
 */
object YspChannelSidRefresher {

    private const val TAG = "CCTV_SID"
    private const val PAGE_URL_BASE = "https://capi.yangshipin.cn/api/oms/pc/page/PG00000004?"
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L // 每 6 小时刷一轮
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"

    private var thread: Thread? = null

    /** 启动周期刷新（幂等，后台线程） */
    fun start() {
        if (thread != null) return
        thread = Thread({
            while (true) {
                try {
                    refresh()
                } catch (e: Exception) {
                    Log.w(TAG, "sid refresh failed: ${e.message}")
                }
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "ysp-sid-refresh").apply {
            isDaemon = true
            start()
        }
    }

    /** 立即刷新一轮（首次启动时调用） */
    fun refresh() {
        if (!ChannelManager.tryBeginSidRefresh()) return
        try {
            val ts = System.currentTimeMillis() / 5000
            val url = PAGE_URL_BASE + ts
            val bytes = fetch(url)
            val map = parsePage(bytes)
            if (map.isEmpty()) {
                Log.w(TAG, "sid map empty")
                return
            }
            for (i in 1..ChannelManager.totalChannels) {
                val pid = ChannelManager.getChannelPid(i)
                val sid = map[pid] ?: continue
                if (sid != ChannelManager.getChannelSid(i)) {
                    Log.i(TAG, "sid updated: pid=$pid ${ChannelManager.getChannelSid(i)} -> $sid")
                    ChannelManager.updateSid(i, sid)
                    YspPlaylistServer.updateChannelSid(i, sid)
                }
            }
        } finally {
            ChannelManager.endSidRefresh()
        }
    }

    private fun fetch(url: String): ByteArray {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", UA)
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection?.disconnect()
        }
    }

    /* ============ 零依赖 protobuf 解析（wire format） ============ */

    private class ProtoReader(private val buf: ByteArray) {
        var pos = 0
        val eof: Boolean get() = pos >= buf.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (eof) throw IllegalStateException("protobuf truncated")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readLenDelimited(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > buf.size) throw IllegalStateException("protobuf truncated")
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> readLenDelimited()
                5 -> pos += 4
                else -> throw IllegalStateException("bad wire type $wire")
            }
        }
    }

    /**
     * 解析 pageModel.Response，返回 pid -> streamId 映射。
     * 只关心：data(2) -> feedModuleList(2)[] -> dataTvChannelList(15)[] -> { pid(4), streamId(6) }
     */
    private fun parsePage(buf: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        try {
            val r = ProtoReader(buf)
            while (!r.eof) {
                val tag = r.readVarint()
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7L).toInt()
                if (field == 2 && wire == 2) {
                    parsePageData(r.readLenDelimited(), result)
                } else {
                    r.skip(wire)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "page parse failed: ${e.message}")
        }
        return result
    }

    private fun parsePageData(buf: ByteArray, out: HashMap<String, String>) {
        val r = ProtoReader(buf)
        while (!r.eof) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 2 && wire == 2) {
                parseModule(r.readLenDelimited(), out)
            } else {
                r.skip(wire)
            }
        }
    }

    private fun parseModule(buf: ByteArray, out: HashMap<String, String>) {
        val r = ProtoReader(buf)
        while (!r.eof) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            if (field == 15 && wire == 2) {
                parseChannelItem(r.readLenDelimited(), out)
            } else {
                r.skip(wire)
            }
        }
    }

    private fun parseChannelItem(buf: ByteArray, out: HashMap<String, String>) {
        val r = ProtoReader(buf)
        var pid = ""
        var sid = ""
        while (!r.eof) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when {
                field == 4 && wire == 2 -> pid = String(r.readLenDelimited(), Charsets.UTF_8)
                field == 6 && wire == 2 -> sid = String(r.readLenDelimited(), Charsets.UTF_8)
                else -> r.skip(wire)
            }
        }
        if (pid.isNotEmpty() && sid.isNotEmpty()) out[pid] = sid
    }
}
