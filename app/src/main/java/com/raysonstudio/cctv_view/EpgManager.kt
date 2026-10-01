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

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 央视频 EPG（节目单）管理器。
 *
 * 后台按顺序拉取各频道的当日节目单（protobuf 二进制，约 2.4KB/频道），
 * 解码后按 `st <= now < et`（unix 秒）计算「正在播出」的节目，
 * 供侧边栏每行右侧显示。接口无鉴权、无需登录，与官网 TV 页用的是同一个接口：
 *
 *   GET https://capi.yangshipin.cn/api/yspepg/program/{pid}
 *   响应：cn.yangshipin.omstv.common.proto.epgProgramModel.Response
 *
 * 解析器为零依赖手写实现（对应 parse-epg.js 的 Kotlin 移植）：
 * varint 用 Long 累加（避免 Int 移位溢出），未知字段按 wire type 跳过以保持向前兼容。
 */
object EpgManager {

    private const val TAG = "CCTV_EPG"
    private const val EPG_BASE = "https://capi.yangshipin.cn/api/yspepg/program/"
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L // 节目单每 10 分钟刷新一轮
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"

    /** 单条节目（只保留侧边栏需要的字段） */
    data class Program(
        val name: String,      // 节目名（页面标题显示的就是它）
        val st: Long,          // 开始时间（unix 秒）
        val et: Long,          // 结束时间（unix 秒）
        val startTime: String, // "HH:MM"（北京时间）
        val endTime: String,   // "HH:MM"（北京时间）
    )

    private class DecodedResponse(
        val code: Long,
        val dataList: List<Program>,
        val updateTime: Long,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val programs = HashMap<String, List<Program>>() // pid -> 当日节目单
    private val fetchedAt = HashMap<String, Long>()         // pid -> 抓取时间
    private val refreshing = AtomicBoolean(false)           // 是否有后台轮次在跑
    private val pending = AtomicBoolean(false)              // 轮次期间又有新请求
    private var periodicStarted = false                     // 仅主线程访问
    @Volatile private var listener: (() -> Unit)? = null    // 主线程写，后台线程读

    /** 绑定 UI 回调（主线程调用）。EPG 更新后回调里 notifyDataSetChanged 即可。 */
    fun attach(onUpdated: () -> Unit) {
        listener = onUpdated
    }

    fun detach() {
        listener = null
    }

    /** 启动周期刷新定时器（主线程调用，幂等） */
    fun start() {
        if (periodicStarted) return
        periodicStarted = true
        schedulePeriodic()
    }

    private fun schedulePeriodic() {
        mainHandler.postDelayed({
            ensureFresh(0, emptySet())
            schedulePeriodic()
        }, REFRESH_INTERVAL_MS)
    }

    /**
     * 请求一次刷新（主线程调用）。当前频道与收藏频道排在拉取顺序前面，
     * 保证用户先看到正在看的频道。currentChannel 传 0 表示不特别优先。
     */
    fun ensureFresh(currentChannel: Int, favorites: Set<Int>) {
        pending.set(true)
        kick(currentChannel, favorites)
    }

    private fun kick(currentChannel: Int, favorites: Set<Int>) {
        if (!refreshing.compareAndSet(false, true)) return // 已有轮次在跑，pending 标记会触发补跑
        val order = buildOrder(currentChannel, favorites)
        Thread {
            try {
                refreshRound(order)
            } finally {
                refreshing.set(false)
                notifyUi()
                if (pending.compareAndSet(true, false)) {
                    kick(currentChannel, favorites)
                }
            }
        }.start()
    }

    /** 拉取顺序：当前频道 → 收藏频道（按频道号） → 其余频道（按频道号） */
    private fun buildOrder(currentChannel: Int, favorites: Set<Int>): List<String> {
        val ordered = LinkedHashSet<String>()
        if (currentChannel in 1..ChannelManager.totalChannels) {
            ordered.add(ChannelManager.getChannelPid(currentChannel))
        }
        favorites.sorted().forEach { ordered.add(ChannelManager.getChannelPid(it)) }
        for (i in 1..ChannelManager.totalChannels) {
            ordered.add(ChannelManager.getChannelPid(i))
        }
        return ordered.toList()
    }

    /** 一轮刷新：只抓过期频道；抓到第一个频道就先把结果推给 UI */
    private fun refreshRound(order: List<String>) {
        var firstPush = false
        for (pid in order) {
            val last = synchronized(fetchedAt) { fetchedAt[pid] ?: 0L }
            if (System.currentTimeMillis() - last < REFRESH_INTERVAL_MS) continue
            if (fetchPid(pid) && !firstPush) {
                firstPush = true
                notifyUi()
            }
        }
    }

    /** 抓取并解码一个频道的节目单；失败保留旧数据 */
    private fun fetchPid(pid: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(EPG_BASE + pid).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.doInput = true
            connection.setRequestProperty("User-Agent", DESKTOP_UA)
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} pid=$pid")
                return false
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val res = decodeResponse(bytes)
            if (res.code != 200L) {
                Log.w(TAG, "EPG code=${res.code} pid=$pid")
                return false
            }
            synchronized(programs) {
                programs[pid] = res.dataList
                fetchedAt[pid] = System.currentTimeMillis()
            }
            Log.d(TAG, "fetched pid=$pid programs=${res.dataList.size} updateTime=${res.updateTime}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed pid=$pid: ${e.javaClass.simpleName} ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** 「正在播」的节目名；无数据/不在播出区间时返回 null（侧边栏该行留空） */
    fun getProgramName(pid: String): String? {
        val list = synchronized(programs) { programs[pid] } ?: return null
        val now = System.currentTimeMillis() / 1000
        for (p in list) {
            if (p.st <= now && now < p.et) return p.name
        }
        return null
    }

    private fun notifyUi() {
        val callback = listener ?: return
        mainHandler.post {
            try {
                callback()
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
    }

    /* ==================== protobuf 读取器（零依赖） ==================== */
    /* wire format：tag = (字段编号 << 3) | wireType；wireType 0=varint 1=64bit 2=length-delimited 5=32bit */

    private class ProtoReader(private val buf: ByteArray) {
        var pos = 0
            private set

        val eof: Boolean get() = pos >= buf.size

        /** 读取 varint，返回 Long（避免 Int 移位溢出） */
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (eof) throw IllegalStateException("EPG protobuf truncated (varint)")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        /** 读取 length-delimited 字段，返回其字节区间 */
        fun readLengthDelimited(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > buf.size) {
                throw IllegalStateException("EPG protobuf truncated (length-delimited)")
            }
            val out = buf.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        /** 读取字符串字段（length-delimited + UTF-8） */
        fun readString(): String = String(readLengthDelimited(), Charsets.UTF_8)

        /** 跳过未知/不关心的字段，保持向前兼容 */
        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()            // varint
                1 -> pos += 8                // 64-bit
                2 -> readLengthDelimited()   // length-delimited
                5 -> pos += 4                // 32-bit
                else -> throw IllegalStateException("unknown protobuf wire type $wireType")
            }
        }
    }

    /**
     * programModel.Program（dataList 中的每一条节目）
     *  1 programId(string) 2 name(string) 3 st(uint64) 4 et(uint64) 5 startTime(string)
     *  6 endTime(string) 7 duration(uint32) 8 isVip(bool) 9 copyrightFlag(string) 10 timeShiftReviewFlag(string)
     */
    private fun decodeProgram(buf: ByteArray): Program {
        val r = ProtoReader(buf)
        var name = ""
        var st = 0L
        var et = 0L
        var startTime = ""
        var endTime = ""
        while (!r.eof) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (field) {
                1 -> r.readString()          // programId
                2 -> name = r.readString()   // name
                3 -> st = r.readVarint()     // st
                4 -> et = r.readVarint()     // et
                5 -> startTime = r.readString()
                6 -> endTime = r.readString()
                7 -> r.readVarint()          // duration
                8 -> r.readVarint()          // isVip
                9 -> r.readString()          // copyrightFlag
                10 -> r.readString()         // timeShiftReviewFlag
                else -> r.skip(wire)
            }
        }
        return Program(name, st, et, startTime, endTime)
    }

    /**
     * epgProgramModel.Response（接口整体响应）
     *  1 code(uint32) 2 dataList(repeated Program) 3 message(string) 4 updateTime(uint64)
     */
    private fun decodeResponse(buf: ByteArray): DecodedResponse {
        val r = ProtoReader(buf)
        var code = 0L
        val dataList = ArrayList<Program>()
        var updateTime = 0L
        while (!r.eof) {
            val tag = r.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7L).toInt()
            when (field) {
                1 -> code = r.readVarint()
                2 -> dataList.add(decodeProgram(r.readLengthDelimited()))
                3 -> r.readString()          // message
                4 -> updateTime = r.readVarint()
                else -> r.skip(wire)
            }
        }
        return DecodedResponse(code, dataList, updateTime)
    }
}
