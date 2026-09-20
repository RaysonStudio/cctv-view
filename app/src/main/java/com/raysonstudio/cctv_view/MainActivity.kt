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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import com.tencent.smtt.export.external.interfaces.ConsoleMessage
import com.tencent.smtt.sdk.WebChromeClient
import com.tencent.smtt.sdk.WebView
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * 央视频 TV 直播壳的入口 Activity（单 WebView）。
 *
 * 观看体验优先：只维护一个 WebView，杜绝双播放器/双解码/音频焦点抢占导致的卡顿。
 * 启动与切台的加速全部依靠 [CctvWebConfig] 的「内存缓存 + 磁盘缓存」：
 * - 内存缓存：shouldInterceptRequest 直接本地返回首页 HTML，换台不再同步抓网
 * - 磁盘缓存：首页 HTML 落盘，冷启动时先从磁盘读入内存，再后台刷新
 */
class MainActivity : AppCompatActivity(), CctvWebViewClient.Listener, VideoPollController.Callback {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var channelList: ListView
    private lateinit var splashLogo: ImageView

    private var windowInsetsController: WindowInsetsControllerCompat? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var videoPollController: VideoPollController
    private var userAgent: String = ""

    private var exitTime = 0L
    private var currentChannel = 1 // 1-based

    private lateinit var channelAdapter: ChannelAdapter
    private val menuRows = ArrayList<MenuRow>()
    private val favorites = LinkedHashSet<Int>() // 1-based 频道索引，按收藏顺序
    private val prefs by lazy { getSharedPreferences("cctv_view", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化缓存（磁盘缓存读入内存），再开始加载
        CctvWebConfig.init(applicationContext)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        setContentView(R.layout.activity_main)
        splashLogo = findViewById(R.id.splashLogo)
        drawerLayout = findViewById(R.id.drawerLayout)
        val container: FrameLayout = findViewById(R.id.webViewContainer)
        webView = container.findViewById(R.id.webView)
        channelList = findViewById(R.id.channelList)
        loadingProgress = findViewById(R.id.loadingProgress)

        // 禁止网页抢占焦点（沿用修改前的焦点策略）：TV 上焦点应停留在频道列表 / 遥控器导航。
        // 注意：X5 WebView 是 FrameLayout 包装，内部 web 内容视图仍可能抢焦点，
        // 因此额外用 FOCUS_BLOCK_DESCENDANTS 阻断其内部内容视图成为焦点。
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        loadFavorites()
        channelAdapter = ChannelAdapter(this, menuRows, favorites)
        channelList.adapter = channelAdapter
        rebuildMenu()

        videoPollController = VideoPollController(webView, handler, loadingProgress, this)

        setupWebView()
        setupMenu()

        // 后台预热（内存缓存未命中时从网络抓取并落盘）
        CctvWebConfig.warmUp(userAgent)

        loadChannel(1)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(channelList)) {
                    drawerLayout.closeDrawer(channelList)
                    return
                }
                val now = System.currentTimeMillis()
                if (now - exitTime < 2000) {
                    finish()
                } else {
                    exitTime = now
                    Toast.makeText(this@MainActivity, "再次按返回退出", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun setupWebView() {
        userAgent = CctvWebConfig.applySettings(webView)

        // WebChromeClient：捕获 JS console 日志和视频错误
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("CCTV_JS", consoleMessage?.message() ?: "")
                return true
            }
        }

        webView.webViewClient = CctvWebViewClient(userAgent, this)
    }

    // ===== CctvWebViewClient.Listener =====

    override fun onPageStarted(url: String?) {
        loadingProgress.visibility = View.VISIBLE
        splashLogo.visibility = View.VISIBLE
        Log.d("CCTV_LIFECYCLE", "onPageStarted url=$url")
        webView.evaluateJavascript(CctvWebConfig.buildPageStartScript(), null)
        Log.d("CCTV_WEB", "开始加载: $url")
        videoPollController.scheduleFromPageStart()
    }

    override fun onPageFinished(url: String?) {
        loadingProgress.visibility = View.GONE
        splashLogo.visibility = View.GONE
        Log.d("CCTV_LIFECYCLE", "onPageFinished url=$url ${videoPollController.debugState()}")
        webView.evaluateJavascript("(function(){try{window.cctvClean && window.cctvClean();}catch(e){}})();", null)
        videoPollController.scheduleFromPageFinished()
    }

    override fun onPageCommitVisible(url: String?) {
        Log.d("CCTV_LIFECYCLE", "onPageCommitVisible url=$url")
        webView.evaluateJavascript("(function(){try{window.cctvClean && window.cctvClean();}catch(e){}})();", null)
    }

    override fun onMainFrameError(description: String?) {
        Log.e("CCTV_WEB", "主页面加载失败: $description")
        loadingProgress.visibility = View.GONE
    }

    // ===== VideoPollController.Callback =====

    override fun onVideoFullscreen() {
        hideSystemUI()
    }

    override fun reloadCurrentChannel() {
        webView.loadUrl(ChannelManager.getChannelUrl(currentChannel))
    }

    override fun getCurrentChannel(): Int = currentChannel

    // ===== 频道切换 =====

    private fun loadChannel(channelIndex: Int) {
        videoPollController.resetForChannelChange()
        currentChannel = channelIndex
        loadingProgress.visibility = View.VISIBLE
        val url = ChannelManager.getChannelUrl(channelIndex)
        Log.d("CCTV_KEY", "Loading URL: $url")
        webView.loadUrl(url)
        loadingProgress.postDelayed({ loadingProgress.visibility = View.GONE }, 15000)
    }

    private fun setupMenu() {
        channelList.isVerticalScrollBarEnabled = false
        channelList.isFastScrollEnabled = false
        channelList.descendantFocusability = ListView.FOCUS_AFTER_DESCENDANTS
        channelList.setItemsCanFocus(false)
        channelList.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            Log.d("CCTV_FOCUS", "LIST FOCUS=$hasFocus")
        }
        channelList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                Log.d("CCTV_FOCUS", "SELECT=$position")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        channelList.setOnItemClickListener { _, _, pos, _ ->
            val idx = channelIndexAtPosition(pos) ?: return@setOnItemClickListener
            loadChannel(idx)
            drawerLayout.closeDrawer(channelList)
        }
    }

    // ===== 收藏夹 =====

    private fun loadFavorites() {
        favorites.clear()
        val saved = prefs.getString("favorite_channels", "") ?: ""
        saved.split(",").forEach { token ->
            val idx = token.trim().toIntOrNull()
            if (idx != null && idx in 1..ChannelManager.totalChannels) {
                favorites.add(idx)
            }
        }
    }

    private fun saveFavorites() {
        prefs.edit().putString("favorite_channels", favorites.joinToString(",")).apply()
    }

    private fun rebuildMenu() {
        menuRows.clear()
        menuRows.add(MenuRow.TopDivider)
        menuRows.add(MenuRow.Header)
        favorites.forEach { menuRows.add(MenuRow.FavoriteChannel(it)) }
        menuRows.add(MenuRow.BottomDivider)
        for (i in 1..ChannelManager.totalChannels) {
            menuRows.add(MenuRow.Channel(i))
        }
        channelAdapter.notifyDataSetChanged()
    }

    private fun selectablePositions(): List<Int> {
        val result = ArrayList<Int>()
        for (i in menuRows.indices) {
            if (menuRows[i].selectable) result.add(i)
        }
        return result
    }

    private fun moveSelection(delta: Int) {
        val positions = selectablePositions()
        if (positions.isEmpty()) return
        val current = channelList.selectedItemPosition
        var index = positions.indexOf(current)
        index = if (index < 0) {
            if (delta > 0) 0 else positions.size - 1
        } else {
            (index + delta + positions.size) % positions.size
        }
        channelList.setSelection(positions[index])
    }

    private fun channelIndexAtPosition(pos: Int): Int? {
        val row = menuRows.getOrNull(pos) ?: return null
        return when (row) {
            is MenuRow.Channel -> row.channelIndex
            is MenuRow.FavoriteChannel -> row.channelIndex
            else -> null
        }
    }

    private fun nearestSelectablePosition(fromPos: Int): Int {
        val positions = selectablePositions()
        for (p in positions) {
            if (p >= fromPos) return p
        }
        return positions.lastOrNull() ?: 0
    }

    private fun toggleFavoriteForSelected() {
        val pos = channelList.selectedItemPosition
        val row = menuRows.getOrNull(pos) ?: return
        val channelIndex = when (row) {
            is MenuRow.Channel -> row.channelIndex
            is MenuRow.FavoriteChannel -> row.channelIndex
            else -> return
        }
        val removingFromFavoritesSection = row is MenuRow.FavoriteChannel
        if (channelIndex in favorites) favorites.remove(channelIndex) else favorites.add(channelIndex)
        saveFavorites()
        rebuildMenu()

        val anchor = if (removingFromFavoritesSection) {
            nearestSelectablePosition(pos)
        } else {
            menuRows.indexOfFirst { it is MenuRow.Channel && it.channelIndex == channelIndex }
        }
        channelList.setSelection(if (anchor >= 0) anchor else selectablePositions().firstOrNull() ?: 0)
    }

    private fun hideSystemUI() {
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ===== 生命周期 =====

    override fun onPause() {
        super.onPause()
        webView.onPause()
        videoPollController.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        videoPollController.onResume()
    }

    override fun onDestroy() {
        videoPollController.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val total = ChannelManager.totalChannels
        if (!drawerLayout.isDrawerOpen(channelList)) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (currentChannel <= 1) loadChannel(total) else loadChannel(currentChannel - 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (currentChannel >= total) loadChannel(1) else loadChannel(currentChannel + 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> return true
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    moveSelection(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    moveSelection(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    toggleFavoriteForSelected()
                    return true
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (drawerLayout.isDrawerOpen(channelList)) {
                val idx = channelIndexAtPosition(channelList.selectedItemPosition)
                if (idx != null) {
                    loadChannel(idx)
                    drawerLayout.closeDrawer(channelList)
                }
            } else {
                loadChannel(currentChannel)
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (drawerLayout.isDrawerOpen(channelList)) {
                drawerLayout.closeDrawer(channelList)
            } else {
                drawerLayout.openDrawer(channelList)
                channelList.requestFocus()
                val anchor = menuRows.indexOfFirst { it is MenuRow.Channel && it.channelIndex == currentChannel }
                if (anchor >= 0) {
                    channelList.setSelection(anchor)
                } else {
                    selectablePositions().firstOrNull()?.let { channelList.setSelection(it) }
                }
                // 选中 CCTV1 的同时把列表滚回顶部，保证「收藏夹」标题及下方内容可见
                channelList.post { channelList.smoothScrollToPosition(0) }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
