package com.raysonstudio.cctv_view

object ChannelManager {
    val channelNames = listOf(
        "CCTV-1 综合", "CCTV-2 财经", "CCTV-3 综艺", "CCTV-4 中文国际",
        "CCTV-5 体育", "CCTV-6 电影", "CCTV-7 国防军事", "CCTV-8 电视剧",
        "CCTV-9 纪录", "CCTV-10 科教", "CCTV-11 戏曲", "CCTV-12 社会与法",
        "CCTV-13 新闻", "CCTV-14 少儿", "CCTV-15 音乐", "CCTV-16 奥林匹克",
        "CCTV-17 农业农村", "CCTV-5+ 体育"
    )

    fun getChannelUrl(index: Int): String {
        val url = when (index) {
            9 -> "https://tv.cctv.com/live/cctvjilu/"
            18 -> "https://tv.cctv.com/live/cctv5plus/"
            else -> "https://tv.cctv.com/live/cctv${index}/"
        }
        return url
    }
}