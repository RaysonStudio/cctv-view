package com.raysonstudio.cctv_view

object ChannelManager {
    private val channelPids = listOf(
        // CCTV系列
        Pair("CCTV-1 综合", "600001859"),
        Pair("CCTV-2 财经", "600001800"),
        Pair("CCTV-3 综艺", "600001801"),
        Pair("CCTV-4 中文国际", "600001814"),
        Pair("CCTV-5 体育", "600001818"),
        Pair("CCTV-5+ 体育赛事", "600001817"),
        Pair("CCTV-6 电影", "600108442"),
        Pair("CCTV-7 国防军事", "600004092"),
        Pair("CCTV-8 电视剧", "600001803"),
        Pair("CCTV-9 纪录", "600004078"),
        Pair("CCTV-10 科教", "600001805"),
        Pair("CCTV-11 戏曲", "600001806"),
        Pair("CCTV-12 社会与法", "600001807"),
        Pair("CCTV-13 新闻", "600001811"),
        Pair("CCTV-14 少儿", "600001809"),
        Pair("CCTV-15 音乐", "600001815"),
        Pair("CCTV-16 奥林匹克-HD", "600098637"),
        Pair("CCTV-16 奥林匹克-4K", "600099502"),
        Pair("CCTV-17 农业农村", "600001810"),
        Pair("CCTV-4K", "600002264"),
        Pair("CCTV-8K", "600156816"),
        // CGTN
        Pair("CGTN", "600014550"),
        // 卫视系列
        Pair("云南卫视", "600190402"),
        Pair("北京卫视", "600002309"),
        Pair("江苏卫视", "600002521"),
        Pair("东方卫视", "600002483"),
        Pair("浙江卫视", "600002520"),
        Pair("湖南卫视", "600002475"),
        Pair("湖北卫视", "600002508"),
        Pair("广东卫视", "600002485"),
        Pair("广西卫视", "600002509"),
        Pair("黑龙江卫视", "600002498"),
        Pair("海南卫视", "600002506"),
        Pair("重庆卫视", "600002531"),
        Pair("深圳卫视", "600002481"),
        Pair("四川卫视", "600002516"),
        Pair("河南卫视", "600002525"),
        Pair("福建卫视", "600002484"),
        Pair("贵州卫视", "600002490"),
        Pair("江西卫视", "600002503"),
        Pair("辽宁卫视", "600002505"),
        Pair("安徽卫视", "600002532"),
        Pair("河北卫视", "600002493"),
        Pair("山东卫视", "600002513"),
        Pair("天津卫视", "600152137"),
        Pair("吉林卫视", "600190405"),
        Pair("陕西卫视", "600190400"),
        Pair("甘肃卫视", "600190408"),
        Pair("宁夏卫视", "600190737"),
        Pair("内蒙古卫视", "600190401"),
        Pair("山西卫视", "600190407"),
        Pair("青海卫视", "600190406"),
        Pair("西藏卫视", "600190403"),
        Pair("新疆卫视", "600152138"),
        Pair("教育电视台", "600171827")
    )

    val channelNames = channelPids.map { it.first }

    val totalChannels = channelPids.size

    fun getChannelUrl(index: Int): String {
        // index 是 1-based
        val pid = if (index in 1..channelPids.size) {
            channelPids[index - 1].second
        } else {
            channelPids[0].second // 默认CCTV1
        }
        return "https://www.yangshipin.cn/tv/home?pid=$pid"
    }
}
