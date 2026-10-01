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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/**
 * 侧边栏菜单的一行。
 *
 * 菜单结构（自上而下）：
 * - [TopDivider]：收藏夹与「教育电视台」之间的白线（环形列表的环绕边界）
 * - [Header]：小字「收藏夹」
 * - [FavoriteChannel]：收藏的频道
 * - [BottomDivider]：收藏夹与 CCTV1 之间的白线
 * - [Channel]：普通频道
 */
sealed class MenuRow {
    val selectable: Boolean get() = this is FavoriteChannel || this is Channel

    object TopDivider : MenuRow()
    object Header : MenuRow()
    object BottomDivider : MenuRow()
    data class FavoriteChannel(val channelIndex: Int) : MenuRow()
    data class Channel(val channelIndex: Int) : MenuRow()
}

/**
 * 混合型菜单适配器：分隔线、收藏夹标题、收藏频道、普通频道。
 */
class ChannelAdapter(
    private val context: Context,
    private val rows: List<MenuRow>,
    private val favorites: Set<Int>
) : BaseAdapter() {

    companion object {
        private const val TYPE_DIVIDER = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_FAVORITE = 2
        private const val TYPE_CHANNEL = 3
    }

    /**
     * 跑马灯总开关：侧边栏打开时开启（超宽节目名滚动显示），
     * 关闭时置 false，避免抽屉收起后隐藏列表里的跑马灯仍在后台空转耗电。
     */
    var marqueeEnabled: Boolean = true

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Any = rows[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 4

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        MenuRow.TopDivider, MenuRow.BottomDivider -> TYPE_DIVIDER
        MenuRow.Header -> TYPE_HEADER
        is MenuRow.FavoriteChannel -> TYPE_FAVORITE
        is MenuRow.Channel -> TYPE_CHANNEL
    }

    override fun areAllItemsEnabled(): Boolean = false

    override fun isEnabled(position: Int): Boolean = rows[position].selectable

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = rows[position]
        val inflater = LayoutInflater.from(context)
        return when (row) {
            MenuRow.TopDivider, MenuRow.BottomDivider -> {
                val v = convertView ?: inflater.inflate(R.layout.menu_divider, parent, false)
                v.isFocusable = false
                v
            }
            MenuRow.Header -> {
                val v = convertView ?: inflater.inflate(R.layout.menu_header, parent, false)
                v.isFocusable = false
                v
            }
            is MenuRow.FavoriteChannel -> {
                val v = convertView ?: inflater.inflate(R.layout.channel_item, parent, false)
                v.findViewById<TextView>(R.id.channelName).text =
                    ChannelManager.getChannelName(row.channelIndex)
                bindProgram(v, row.channelIndex)
                v
            }
            is MenuRow.Channel -> {
                val v = convertView ?: inflater.inflate(R.layout.channel_item, parent, false)
                val name = ChannelManager.getChannelName(row.channelIndex)
                v.findViewById<TextView>(R.id.channelName).text =
                    if (row.channelIndex in favorites) "★ $name" else name
                bindProgram(v, row.channelIndex)
                v
            }
        }
    }

    /** 绑定该频道「正在播」的节目名；isSelected 用于驱动 TextView 跑马灯 */
    private fun bindProgram(v: View, channelIndex: Int) {
        val programView = v.findViewById<TextView>(R.id.programName)
        programView.text = EpgManager.getProgramName(ChannelManager.getChannelPid(channelIndex)) ?: ""
        programView.isSelected = marqueeEnabled
    }
}
