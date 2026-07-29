package com.raysonstudio.cctv_view

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

class MainActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webView: WebView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var channelList: ListView
    private val handler = Handler(Looper.getMainLooper())
    private var fullscreenDone = false
    private lateinit var splashLogo: ImageView
    private var exitTime = 0L
    private var currentChannel = 1 // 1-based

    private lateinit var firstLaunchPopup: View
    private lateinit var sharedPreferences: SharedPreferences
    private val FIRST_LAUNCH_KEY = "is_first_launch"

    // 用于控制沉浸式模式的新 API
    private lateinit var windowInsetsController: WindowInsetsControllerCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 启用 Edge-to-Edge 显示
        WindowCompat.setDecorFitsSystemWindows(window, false)
        windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        setContentView(R.layout.activity_main)
        firstLaunchPopup = findViewById(R.id.firstLaunchPopup)
        sharedPreferences = getPreferences(MODE_PRIVATE)

        val isFirstLaunch = sharedPreferences.getBoolean(FIRST_LAUNCH_KEY, true)
        if (isFirstLaunch) {
            // 如果是第一次，显示弹窗
            firstLaunchPopup.visibility = View.VISIBLE
            // 并记录下“已经不是第一次启动了”
            sharedPreferences.edit().putBoolean(FIRST_LAUNCH_KEY, false).apply()
        }
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

        setupWebView()
        setupMenu()
        fetchAndLoadDefaultChannel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(channelList)) {
                    drawerLayout.closeDrawer(channelList)
                    return
                }
                if (firstLaunchPopup.visibility == View.VISIBLE) {
                    firstLaunchPopup.visibility = View.GONE
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

    // 在 MainActivity 类内部，其他函数之外
    private fun fetchAndLoadDefaultChannel() {
        // 创建一个 OkHttp 客户端
        val client = OkHttpClient()

        // 创建一个请求，指向你的 Flask 服务器 API
        val request = Request.Builder()
            .url("http://${BuildConfig.SERVER_IP}:${BuildConfig.SERVER_PORT}/api/default_channel")
            .build()

        // 在新线程中执行网络请求，不能在主线程进行网络操作
        Thread {
            try {
                // 执行请求并获取响应
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        var defaultChannelFromServer = 1
                        // 解析返回的 JSON 数据
                        val jsonData = response.body?.string()
                        if (jsonData != null) {
                            val jsonObject = JSONObject(jsonData)
                            defaultChannelFromServer = jsonObject.getInt("default_channel")
                        }

                        // 回到主线程更新 UI (加载频道)
                        runOnUiThread {
                            loadChannel(defaultChannelFromServer)
                        }
                    } else {
                        // 如果服务器返回错误，则加载默认频道 1
                        runOnUiThread {
                            loadChannel(1)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果发生异常（如网络不通），则加载默认频道 1
                runOnUiThread {
                    loadChannel(1)
                }
            }
        }.start()
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.domStorageEnabled = true
        // 2. 移除了已废弃的 databaseEnabled
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    view?.loadUrl(url)
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                setViewsVisibility(View.VISIBLE, loadingProgress, splashLogo)
                Log.d("CCTV_WEB", "开始加载: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setViewsVisibility(View.GONE, loadingProgress, splashLogo)
                Log.d("CCTV_WEB", "加载完成: $url")
                waitVideo()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Log.e("CCTV_WEB", "主页面加载失败: ${error?.description}")
                    loadingProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun setViewsVisibility(visibility: Int, vararg views: View) {
        for (view in views) {
            view.visibility = visibility
        }
    }

    private fun loadChannel(channelIndex: Int) {
        fullscreenDone = false
        currentChannel = channelIndex
        loadingProgress.visibility = View.VISIBLE
        val url = ChannelManager.getChannelUrl(channelIndex)
        Log.d("CCTV_KEY", "Loading URL: $url")
        webView.loadUrl(url)
        loadingProgress.postDelayed({ loadingProgress.visibility = View.GONE }, 3000)
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

    private fun waitVideo() {
        val runnable = object : Runnable {
            override fun run() {
                if (fullscreenDone) return
                webView.evaluateJavascript(
                    """
                    (function(){
                        let v=document.getElementById("h5player_player");
                        if(!v || v.readyState<3) return JSON.stringify({state:"WAIT"});
                        let p=v;
                        while(p && p!==document.body){
                            p.style.position="fixed";
                            p.style.left="0px";
                            p.style.top="0px";
                            p.style.width="100vw";
                            p.style.height="100vh";
                            p.style.zIndex="999999";
                            p.style.background="black";
                            p=p.parentElement;
                        }
                        v.style.width="100vw";
                        v.style.height="100vh";
                        v.style.objectFit="contain";
                        function hideControls(){
                            [ "control_bar_player", "player_fullscreen_player" ].forEach(function(id){
                                let e=document.getElementById(id);
                                if(e){
                                    e.style.display="none";
                                    e.style.visibility="hidden";
                                    e.style.opacity="0";
                                }
                            });
                            document.querySelectorAll(
                                "[class*=control],[class*=Control],[class*=toolbar]"
                            ).forEach(function(e){
                                e.style.display="none";
                                e.style.visibility="hidden";
                            });
                        }
                        hideControls();
                        if(!window.cctvObserver){
                            window.cctvObserver = new MutationObserver(function(){
                                hideControls();
                            });
                            window.cctvObserver.observe(
                                document.body,
                                { childList:true, subtree:true, attributes:true, attributeFilter:["style","class"] }
                            );
                        }
                        document.body.style.margin="0";
                        document.documentElement.style.margin="0";
                        return JSON.stringify({ state:"FULLSCREEN", width:v.videoWidth, height:v.videoHeight });
                    })();
                    """,
                ) { result ->
                    Log.d("CCTV_FULLSCREEN", result)
                    if (result.contains("FULLSCREEN")) {
                        fullscreenDone = true
                        hideSystemUI()
                    }
                }
                if (!fullscreenDone) {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(runnable)
    }

    // 4. 使用新的 WindowInsetsControllerCompat API 替代废弃的 systemUiVisibility
    private fun hideSystemUI() {
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // 5. 移除了未使用的 isCurrentlyInFullscreen 函数

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!drawerLayout.isDrawerOpen(channelList)) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (currentChannel <= 1) loadChannel(18) else loadChannel(currentChannel - 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (currentChannel >= 18) loadChannel(1) else loadChannel(currentChannel + 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (channelList.selectedItemPosition == 0) channelList.setSelection(17) else return super.onKeyDown(keyCode, event)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (channelList.selectedItemPosition == 17) channelList.setSelection(0) else return super.onKeyDown(keyCode, event)
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