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

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 频道清单（name, pid, streamId）。
 *
 * streamId（cnlid）用于 JCE / bkliveinfo 原生播放协议。
 * 内置值来自央视频 TV 页 page/PG00000004 接口抓取；streamId 可能随央视频
 * 后台调整而变化，[YspChannelSidRefresher] 会在后台周期性刷新并更新这里。
 */
object ChannelManager {

    data class Channel(val name: String, val pid: String, val sid: String)

    private val channelPids = mutableListOf(
        Channel("CCTV-1 综合", "600001859", "2024078201"),
        Channel("CCTV-2 财经", "600001800", "2024075401"),
        Channel("CCTV-3 综艺", "600001801", "2024068501"),
        Channel("CCTV-4 中文国际", "600001814", "2029797101"),
        Channel("CCTV-5 体育", "600001818", "2024078401"),
        Channel("CCTV-5+ 体育赛事", "600001817", "2024078001"),
        Channel("CCTV-6 电影", "600108442", "2013693901"),
        Channel("CCTV-7 国防军事", "600004092", "2024072001"),
        Channel("CCTV-8 电视剧", "600001803", "2029793001"),
        Channel("CCTV-9 纪录", "600004078", "2024078601"),
        Channel("CCTV-10 科教", "600001805", "2024078701"),
        Channel("CCTV-11 戏曲", "600001806", "2027248701"),
        Channel("CCTV-12 社会与法", "600001807", "2027248801"),
        Channel("CCTV-13 新闻", "600001811", "2029797201"),
        Channel("CCTV-14 少儿", "600001809", "2027248901"),
        Channel("CCTV-15 音乐", "600001815", "2027249001"),
        Channel("CCTV-16 奥林匹克-HD", "600098637", "2027249101"),
        Channel("CCTV-16 奥林匹克-4K", "600099502", "2027249301"),
        Channel("CCTV-17 农业农村", "600001810", "2027249401"),
        Channel("CCTV-4K", "600002264", "2029810301"),
        Channel("CCTV-8K", "600156816", "2026774101"),
        Channel("CGTN", "600014550", "2024181701"),
        Channel("云南卫视", "600190402", "2025561303"),
        Channel("北京卫视", "600002309", "2024052703"),
        Channel("江苏卫视", "600002521", "2024171103"),
        Channel("东方卫视", "600002483", "2024054503"),
        Channel("浙江卫视", "600002520", "2024054703"),
        Channel("湖南卫视", "600002475", "2024054803"),
        Channel("湖北卫视", "600002508", "2024171203"),
        Channel("广东卫视", "600002485", "2024060903"),
        Channel("广西卫视", "600002509", "2024060703"),
        Channel("黑龙江卫视", "600002498", "2029797003"),
        Channel("海南卫视", "600002506", "2024055603"),
        Channel("重庆卫视", "600002531", "2024061103"),
        Channel("深圳卫视", "600002481", "2024061303"),
        Channel("四川卫视", "600002516", "2024061403"),
        Channel("河南卫视", "600002525", "2029797303"),
        Channel("福建卫视", "600002484", "2024061503"),
        Channel("贵州卫视", "600002490", "2024061603"),
        Channel("江西卫视", "600002503", "2024061703"),
        Channel("辽宁卫视", "600002505", "2024171303"),
        Channel("安徽卫视", "600002532", "2024171403"),
        Channel("河北卫视", "600002493", "2024171503"),
        Channel("山东卫视", "600002513", "2029787903"),
        Channel("天津卫视", "600152137", "2019927003"),
        Channel("吉林卫视", "600190405", "2025561503"),
        Channel("陕西卫视", "600190400", "2029795103"),
        Channel("甘肃卫视", "600190408", "2025561703"),
        Channel("宁夏卫视", "600190737", "2025608503"),
        Channel("内蒙古卫视", "600190401", "2025561203"),
        Channel("山西卫视", "600190407", "2025560803"),
        Channel("青海卫视", "600190406", "2025559103"),
        Channel("西藏卫视", "600190403", "2025558003"),
        Channel("新疆卫视", "600152138", "2019927403"),
        Channel("教育电视台", "600171827", "2022823801"),
    )

    private val refreshing = AtomicBoolean(false)

    val channelNames = channelPids.map { it.name }

    val totalChannels = channelPids.size

    private fun channelAt(index: Int): Channel =
        if (index in 1..channelPids.size) channelPids[index - 1] else channelPids[0]

    fun getChannelName(index: Int): String = channelAt(index).name

    fun getChannelPid(index: Int): String = channelAt(index).pid

    /** streamId / cnlid（JCE 与 bkliveinfo 协议用） */
    fun getChannelSid(index: Int): String = channelAt(index).sid

    fun getChannelUrl(index: Int): String =
        "https://www.yangshipin.cn/tv/home?pid=" + channelAt(index).pid

    /** 由 [YspChannelSidRefresher] 后台更新 streamId（1-based） */
    fun updateSid(index: Int, sid: String) {
        if (index in 1..channelPids.size && sid.isNotBlank()) {
            channelPids[index - 1] = channelAt(index).copy(sid = sid)
        }
    }

    /** 标记后台 sid 刷新是否正在进行（避免多轮并发） */
    fun tryBeginSidRefresh(): Boolean = refreshing.compareAndSet(false, true)

    fun endSidRefresh() {
        refreshing.set(false)
    }
}
