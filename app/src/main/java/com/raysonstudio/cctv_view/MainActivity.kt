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
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
 * 央视频 TV 直播壳的入口 Activity。
 *
 * 职责仅限于编排：
 * - 视图绑定（[activity_main.xml]）
 * - 频道列表抽屉菜单（[setupMenu] / [loadChannel]）
 * - 遥控器按键派发（[onKeyDown]）
 * - 系统栏隐藏（[hideSystemUI]）
 * - 生命周期转发给 [webView] 与 [videoPollController]
 *
 * WebView 配置见 [CctvWebConfig]，请求拦截见 [CctvWebViewClient]，
 * 视频就绪轮询与重试见 [VideoPollController]。
 */
class MainActivity : AppCompatActivity(), CctvWebViewClient.Listener, VideoPollController.Callback {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var channelList: ListView
    private lateinit var splashLogo: ImageView

    private lateinit var windowInsetsController: WindowInsetsControllerCompat
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var videoPollController: VideoPollController
    private var userAgent: String = ""

    private var exitTime = 0L
    private var currentChannel = 1 // 1-based

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 启用 Edge-to-Edge 显示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        setContentView(R.layout.activity_main)
        splashLogo = findViewById(R.id.splashLogo)
        drawerLayout = findViewById(R.id.drawerLayout)
        val container: FrameLayout = findViewById(R.id.webViewContainer)
        webView = container.findViewById(R.id.webView)
        channelList = findViewById(R.id.channelList)
        loadingProgress = findViewById(R.id.loadingProgress)

        webView.isFocusable = false
        webView.isFocusableInTouchMode = false

        val adapter = ArrayAdapter(this, R.layout.channel_item, R.id.channelName, ChannelManager.channelNames)
        channelList.adapter = adapter

        videoPollController = VideoPollController(webView, handler, loadingProgress, this)

        setupWebView()
        setupMenu()

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
        // ============================================================
        // 关键：在 shouldInterceptRequest 中已同步把全屏 CSS 注入到 HTML <head>，
        // 从渲染管线源头就隐藏非播放器元素 + 预全屏播放器容器。
        // 这里保留一个带守卫的 JS 兜底，以防拦截失败或非常规入口。
        // 这样当 video.js 建立解码 Surface 时，尺寸/位置就已经是最终全屏值，
        // 避免视频已开始播放后再改变布局造成解码器 Surface 失效（=先声后画+长黑屏）
        //
        // 只做 display:none/visibility/opacity，不做任何 DOM remove，不破坏内部引用。
        // ============================================================
        webView.evaluateJavascript(CctvWebConfig.buildPageStartScript(), null)
        Log.d("CCTV_WEB", "开始加载: $url")
        videoPollController.scheduleFromPageStart()
    }

    override fun onPageFinished(url: String?) {
        loadingProgress.visibility = View.GONE
        splashLogo.visibility = View.GONE
        Log.d("CCTV_LIFECYCLE", "onPageFinished url=$url ${videoPollController.debugState()}")
        // 页面加载完成后再清一次，捕获 SPA 延迟渲染的节点
        webView.evaluateJavascript("(function(){try{window.cctvClean && window.cctvClean();}catch(e){}})();", null)
        videoPollController.scheduleFromPageFinished()
    }

    override fun onPageCommitVisible(url: String?) {
        Log.d("CCTV_LIFECYCLE", "onPageCommitVisible url=$url")
        // 页面即将可见时再清一次
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
        // 切换频道：重置视频轮询/重试状态并暂停旧视频
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
            loadChannel(pos + 1)
            drawerLayout.closeDrawer(channelList)
        }
    }

    // 使用新的 WindowInsetsControllerCompat API 替代废弃的 systemUiVisibility
    private fun hideSystemUI() {
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ===== 生命周期 =====

    override fun onPause() {
        super.onPause()
        // 暂停 WebView 的 JS 定时器和视频播放，释放 CPU
        webView.onPause()
        videoPollController.onPause()
    }

    override fun onResume() {
        super.onResume()
        // 恢复 WebView
        webView.onResume()
        videoPollController.onResume()
    }

    override fun onDestroy() {
        // 彻底清理 WebView，防止内存泄漏
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
                    if (channelList.selectedItemPosition == 0) channelList.setSelection(total - 1) else return super.onKeyDown(keyCode, event)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (channelList.selectedItemPosition == total - 1) channelList.setSelection(0) else return super.onKeyDown(keyCode, event)
                    return true
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (drawerLayout.isDrawerOpen(channelList)) {
                val pos = channelList.selectedItemPosition
                if (pos >= 0) {
                    loadChannel(pos + 1)
                    drawerLayout.closeDrawer(channelList)
                }
            } else {
                // 6. 优化后的刷新逻辑：直接重新加载当前频道，逻辑统一且可靠
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
                channelList.setSelection(currentChannel - 1)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
