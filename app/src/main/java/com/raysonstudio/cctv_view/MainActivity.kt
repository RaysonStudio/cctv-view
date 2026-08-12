package com.raysonstudio.cctv_view

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URL
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
    private var videoRetryCount = 0 // 视频错误重试计数
    private val maxVideoRetries = 3 // 最多重试3次（整页刷新次数）
    private var waitVideoRunnable: Runnable? = null // 单一轮询引用，避免并发
    private var waitVideoStarted = false // 是否已启动轮询（onPageStarted延迟 + onPageFinished 防重复）

    private lateinit var windowInsetsController: WindowInsetsControllerCompat

    // 在 HTML <head> 中同步注入的全屏 CSS（避免 JS 异步注入与页面渲染竞争）
    private val earlyCss: String = buildString {
        // 1. 页面根：全屏固定，黑色背景
        append("#app{position:fixed!important;left:0!important;top:0!important;")
        append("width:100%!important;height:100%!important;z-index:999999!important;")
        append("margin:0!important;padding:0!important;background:#000!important;")
        append("overflow:hidden!important;max-width:none!important;min-width:0!important}\n")
        append("html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;width:100%!important;height:100%!important}\n")
        // 2. 播放器祖先链：全部填满父容器
        append(".tv-home,.tv-home-list,.tv,.tv-main,.tv-main-con,.tv-main-con-l,.tv-main-con-l-vid")
        append("{width:100%!important;height:100%!important;margin:0!important;padding:0!important;")
        append("overflow:hidden!important;background:#000!important;display:block!important;")
        append("max-width:none!important;min-width:0!important;position:static!important;flex:none!important}\n")
        // 2b. 路径上无class的中间div也要填满
        append(".tv-main-con-l-vid>div,.tv-home-list>div")
        append("{width:100%!important;height:100%!important;margin:0!important;padding:0!important;")
        append("overflow:hidden!important;background:#000!important;display:block!important;")
        append("max-width:none!important;min-width:0!important;flex:none!important}\n")
        // 3. 视频容器+视频：绝对填满
        append("[id^='vodbox']{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;display:block!important;z-index:1!important}\n")
        append(".video-con{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;margin:0!important;padding:0!important;background:#000!important;z-index:2!important}\n")
        append("video.video-js{position:absolute!important;left:0!important;top:0!important;width:100%!important;height:100%!important;background:#000!important;object-fit:contain!important;z-index:3!important;opacity:1!important;visibility:visible!important;display:block!important}\n")
        // 4. 隐藏非播放器子区域
        append(".tv-main-con-r,.tv-main-con-r-tab,.tv-main-con-r-list,.own-toast,.y-full-control,.y-full,.y-full-bg,.con.poster,.loading,[class*='control-outside'],.progress-bar,.progress-btn-wrapper,.voice,.bei,.pip,.videoFull,.full,.volume-muted-tip-container,.video-status-tip,img,picture,figure,svg,canvas,iframe,audio,object,embed,source,track,footer,header,nav,[class*='advert'],[class*='promotion'],[class*='banner']{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;z-index:-999!important}\n")
        // 5. body直接子元素中除#app外：隐藏
        append("body>*:not(#app){display:none!important}\n")
        // 6. 路径兄弟隐藏
        append("#app>div:not(.comPadding):not([class*='own-toast']){display:none!important}\n")
        append(".comPadding>div:not(.tv-home){display:none!important}\n")
        append(".tv-home>div:not(.tv-home-list){display:none!important}\n")
        append(".tv-main-con>div:not(.tv-main-con-l):not([class*='l-vid']){display:none!important}\n")
        append(".tv-main-con-l>div:not(.tv-main-con-l-vid){display:none!important}\n")
    }

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
        // 硬件加速层类型：让 WebView 的渲染走 GPU 纹理合成
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // 必须使用桌面 Chrome UA：Android UA 会触发 302 重定向到 m.yangshipin.cn 移动端页面
        // 移动端页面用 Nuxt 框架，没有 video.video-js 元素，且 WASM 加载方式不同会导致 MIME type 错误
        // 桌面页面下所有频道（含卫视）返回的 HLS 流均为 H.264 编码，Android WebView 可正常解码
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // ===== 性能优化 =====
        // 预加载 DNS：央视频核心域名提前解析
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.settings.offscreenPreRaster = true // 离屏预渲染
        }
        // 启用 WebView 内部的数据库/缓存来减少重复请求
        settings.databaseEnabled = true
        // 视口设置：确保 100% 尺寸正确映射到 WebView 像素
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // 禁用不需要的功能
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setGeolocationEnabled(false)
        settings.saveFormData = false
        settings.savePassword = false
        // 启用 WebView 调试（可通过 chrome://inspect 查看 console 日志）
        WebView.setWebContentsDebuggingEnabled(true)

        // WebChromeClient：捕获 JS console 日志和视频错误
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                Log.d("CCTV_JS", consoleMessage?.message() ?: "")
                return true
            }
        }
        // 使用更宽的缓存策略：同源内放行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 允许 WebView 控制器并发请求
        }

        webView.webViewClient = object : WebViewClient() {
            // 央视频自家域名：全部放行（包含播放器API、时间同步、鉴权、WASM DRM等关键请求）
            private val trustedDomains = listOf(
                "yangshipin.cn",   // 所有子域：www/sapi/csapi/player-api/h5access/s/aatc-api/btrace/pcsite
                "ysp.cctv.cn",     // CDN直播流域名
                "cctv.cn"          // CCTV通用域
            )
            // 视频流后缀
            private val videoExtensions = setOf(
                ".m3u8", ".mpd", ".m4s", ".m4a", ".m4v", ".ts", ".aac", ".ac3", ".ec3",
                ".mp4", ".webm", ".ogg", ".ogv", ".flv", ".mov", ".vp9", ".av1",
                ".wasm"  // WASM DRM 解密模块（卫视频道需要）
            )
            // 明确需要拦截的第三方资源（非央视频域名）
            private val blockKeywords = listOf(
                "sentry", "rum",                        // 监控
                "google", "baidu", "cnzz", "umeng"      // 第三方统计
            )

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                // 主文档：同步拦截央视频 TV 首页，在 <head> 中注入全屏 CSS
                if (request.isForMainFrame && url.contains("yangshipin.cn") && url.contains("/tv/home")) {
                    return interceptMainFrame(request, url)
                }

                // blob: / data:：放行（视频内部流）
                if (url.startsWith("blob:") || url.startsWith("data:")) return null

                val urlLower = url.lowercase()

                // 1. 央视频自家域名：全部放行（serverTime/play/鉴权/CDN等都是关键请求）
                for (domain in trustedDomains) {
                    if (urlLower.contains(domain)) return null
                }

                // 2. 视频流 / 直播分片：放行
                for (ext in videoExtensions) {
                    if (urlLower.contains(ext)) return null
                }

                // 3. 核心脚本/样式：放行
                if (urlLower.endsWith(".js") || urlLower.endsWith(".js?") ||
                    urlLower.endsWith(".css") || urlLower.endsWith(".css?") ||
                    urlLower.contains("videojs") || urlLower.contains("video-js")
                ) return null

                // 4. 命中拦截关键词：取消
                for (b in blockKeywords) {
                    if (urlLower.contains(b)) {
                        Log.d("CCTV_BLOCK", "drop: ${url.take(80)}")
                        return createEmptyResponse()
                    }
                }

                // 5. 第三方图片/字体：取消（省流量）
                if (urlLower.endsWith(".png") || urlLower.endsWith(".jpg") ||
                    urlLower.endsWith(".jpeg") || urlLower.endsWith(".gif") ||
                    urlLower.endsWith(".webp") || urlLower.endsWith(".svg") ||
                    urlLower.endsWith(".ico") || urlLower.endsWith(".woff2") ||
                    urlLower.endsWith(".woff") || urlLower.endsWith(".ttf")
                ) {
                    Log.d("CCTV_BLOCK", "drop img/font: ${url.take(80)}")
                    return createEmptyResponse()
                }

                // 6. 其他请求：放行（避免误伤播放器需要的未知资源）
                return null
            }

            private fun createEmptyResponse(): android.webkit.WebResourceResponse {
                val emptyStream = java.io.ByteArrayInputStream(ByteArray(0))
                return android.webkit.WebResourceResponse("text/plain", "utf-8", 204,
                    "No Content", emptyMap<String, String>(), emptyStream)
            }

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
                // ============================================================
                // 关键：在 shouldInterceptRequest 中已同步把全屏 CSS 注入到 HTML <head>，
                // 从渲染管线源头就隐藏非播放器元素 + 预全屏播放器容器。
                // 这里保留一个带守卫的 JS 兜底，以防拦截失败或非常规入口。
                // 这样当 video.js 建立解码 Surface 时，尺寸/位置就已经是最终全屏值，
                // 避免视频已开始播放后再改变布局造成解码器 Surface 失效（=先声后画+长黑屏）
                //
                // 只做 display:none/visibility/opacity，不做任何 DOM remove，不破坏内部引用。
                // ============================================================
                val cssJs = earlyCss.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                webView.evaluateJavascript(
                    """
                    (function(){
                        try{ if(window.cctvObserver){ window.cctvObserver.disconnect(); window.cctvObserver=null; } }catch(e){}
                        try{ if(window.cctvClean){ window.cctvClean(); } }catch(e){}
                        window.__cctvInited=false;
                        window.__cctvLayoutDone=false;
                        window.__cctvStallStart=null;
                        window.__cctvStuckStart=null;
                        window.__cctvVideoError=null;
                        window.__cctvStartTs=Date.now();
                        // 兜底：若同步 HTML 注入未生效，则通过 JS 补充 CSS
                        if(!document.getElementById('cctv-early-css')){
                            try{
                                var s=document.createElement('style');
                                s.id='cctv-early-css';
                                s.textContent='$cssJs';
                                if(document.head){document.head.appendChild(s);}
                                else{document.documentElement.appendChild(s);}
                            }catch(e){}
                        }

                        // ===== 安全删除冗余元素（只删除不在视频播放路径上的节点） =====
                        window.__cctvCleaning=false;
                        window.cctvClean=function(){
                            if(window.__cctvCleaning) return;
                            window.__cctvCleaning=true;
                            try{
                                var v=document.querySelector('video.video-js');
                                var selectors=[
                                    '.tv-zhan','.tv-zhan-shadow','.tv-zhan-con','.tv-zhan-title','.tv-zhan-kai','.tv-zhan-list',
                                    '.tv-main-con-r','.tv-main-con-r-tab','.tv-main-con-r-list',
                                    '.own-toast','.y-full-control','.y-full','.y-full-bg','.con.poster','.loading',
                                    '[class*="control-outside"]','.progress-bar','.progress-btn-wrapper','.voice','.bei','.pip',
                                    '.videoFull','.full','.volume-muted-tip-container','.video-status-tip',
                                    '[class*="advert"]','[class*="promotion"]','[class*="banner"]',
                                    'header','footer','nav'
                                ];
                                for(var i=0;i<selectors.length;i++){
                                    try{
                                        var nodes=document.querySelectorAll(selectors[i]);
                                        for(var j=0;j<nodes.length;j++){
                                            var node=nodes[j];
                                            if(!node.parentNode) continue;
                                            if(v && (node.contains(v) || v.contains(node))) continue;
                                            node.parentNode.removeChild(node);
                                        }
                                    }catch(e){}
                                }
                            }catch(e){}
                            window.__cctvCleaning=false;
                        };
                        window.cctvClean();
                        if(!window.cctvObserver){
                            window.cctvObserver=new MutationObserver(function(mutations){
                                window.cctvClean();
                            });
                            window.cctvObserver.observe(document.documentElement,{childList:true,subtree:true});
                        }

                        // 监听 video 错误事件（HEVC/codec不支持等）
                        window.__cctvVideoError=null;
                        // 用 MutationObserver 等待 video 元素出现后绑定 error 事件
                        if(!window.__cctvErrorObserver){
                            window.__cctvErrorObserver=new MutationObserver(function(){
                                var v=document.querySelector('video.video-js');
                                if(v && !v.__cctvErrBound){
                                    v.__cctvErrBound=true;
                                    v.addEventListener('error',function(e){
                                        var ec=v.error?v.error.code:-1;
                                        var em=v.error?v.error.message:'';
                                        window.__cctvVideoError={code:ec,msg:em};
                                        console.error('CCTV_VIDEO_ERROR code='+ec+' msg='+em);
                                    },true);
                                    v.addEventListener('abort',function(){
                                        console.warn('CCTV_VIDEO_ABORT');
                                    },true);
                                }
                            });
                            window.__cctvErrorObserver.observe(document.documentElement,{childList:true,subtree:true});
                        }
                    })();
                    """.trimIndent(),
                    null
                )
                Log.d("CCTV_WEB", "开始加载: $url")
                // 启动轮询：确保只有一个Runnable在运行
                // 取消之前任何遗留的轮询
                waitVideoRunnable?.let { handler.removeCallbacks(it) }
                waitVideoRunnable = null
                waitVideoStarted = false
                // 早期轮询：1秒后开始（不等onPageFinished，卫视频道WASM DRM初始化慢）
                handler.postDelayed({
                    if (!fullscreenDone && !waitVideoStarted) {
                        //startWaitVideo()
                    }
                }, 1000)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setViewsVisibility(View.GONE, loadingProgress, splashLogo)
                Log.d("CCTV_WEB", "加载完成: $url")
                // onPageFinished时如果轮询还没启动，立即启动（否则等待已有轮询继续）
                if (!fullscreenDone && !waitVideoStarted) {
                    // 如果onPageStarted的延迟1秒还没到，这里立即启动轮询
                    //startWaitVideo()
                }
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

    private fun injectCssIntoHtml(html: String, css: String): String {
        val styleBlock = "<style id=\"cctv-early-css\">$css</style>"
        val headClose = html.indexOf("</head>", ignoreCase = true)
        return if (headClose >= 0) {
            html.substring(0, headClose) + styleBlock + html.substring(headClose)
        } else {
            val htmlClose = html.indexOf("</html>", ignoreCase = true)
            if (htmlClose >= 0) {
                html.substring(0, htmlClose) + styleBlock + html.substring(htmlClose)
            } else {
                styleBlock + html
            }
        }
    }

    private fun interceptMainFrame(request: WebResourceRequest, url: String): android.webkit.WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.doInput = true

            // 复制原请求头，但跳过 WebView 自己处理的 Host/编码头
            request.requestHeaders?.forEach { (key, value) ->
                if (key.equals("Host", ignoreCase = true) ||
                    key.equals("Accept-Encoding", ignoreCase = true)) return@forEach
                connection.setRequestProperty(key, value)
            }
            connection.setRequestProperty("User-Agent", webView.settings.userAgentString)

            // 同步 WebView CookieManager 中的 cookie
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) {
                connection.setRequestProperty("Cookie", cookie)
            }

            connection.connect()

            // 把响应 Set-Cookie 写回 WebView CookieManager
            var i = 0
            while (true) {
                val key = connection.getHeaderFieldKey(i) ?: break
                if (key.equals("Set-Cookie", ignoreCase = true)) {
                    connection.getHeaderField(i)?.let { CookieManager.getInstance().setCookie(url, it) }
                }
                i++
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Log.w("CCTV_WEB", "Intercept main frame got non-2xx: $responseCode")
                return null
            }

            val contentType = connection.contentType ?: "text/html; charset=utf-8"
            val charset = contentType.substringAfter("charset=", "utf-8")
                .trim(' ', '\"', '\'')
                .ifEmpty { "utf-8" }

            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val modifiedHtml = injectCssIntoHtml(html, earlyCss)
            val bytes = modifiedHtml.toByteArray(Charsets.UTF_8)

            android.webkit.WebResourceResponse(
                contentType.substringBefore(";"),
                charset,
                responseCode,
                connection.responseMessage ?: "OK",
                emptyMap(),
                java.io.ByteArrayInputStream(bytes)
            )
        } catch (e: Exception) {
            Log.e("CCTV_WEB", "Main frame intercept failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun loadChannel(channelIndex: Int) {
        // 先暂停当前视频并清理，减少切换时的内存峰值和残留帧
        if (fullscreenDone) {
            webView.evaluateJavascript(
                """
                (function(){
                    try{
                        var v=document.querySelector('video.video-js');
                        if(v){ v.pause(); }
                    }catch(e){}
                })();
                """.trimIndent(), null
            )
        }
        // 停止旧的轮询Runnable（注意：只移除waitVideoRunnable，不移除onPageStarted的1秒延迟启动器）
        waitVideoRunnable?.let { handler.removeCallbacks(it) }
        waitVideoRunnable = null
        waitVideoStarted = false
        fullscreenDone = false
        currentChannel = channelIndex
        videoRetryCount = 0 // 切换频道时重置重试计数
        waitVideoPollCount = 0
        layoutDone = false
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

    private var waitVideoPollCount = 0
    private var layoutDone = false // 布局是否已在PRE阶段应用完成
    private var jsLevelRetryCount = 0 // JS层重载重试次数（v.load()，不刷新整页）
    private val MAX_JS_RETRIES = 2 // JS层最多重试2次，之后升级为整页刷新

    /**
     * 启动视频等待轮询。确保全局只有一个Runnable在运行：
     * - loadChannel 重置 waitVideoStarted=false
     * - onPageStarted 延迟1秒 或 onPageFinished 立即 调用 startWaitVideo()
     * - startWaitVideo() 检查 waitVideoStarted，只有false才创建新Runnable
     */
    private fun startWaitVideo() {
        if (waitVideoStarted) return
        waitVideoStarted = true

        val runnable = object : Runnable {
            override fun run() {
                if (fullscreenDone) return
                waitVideoPollCount++
                // 前15次300ms快速检测（video出现+布局阶段），之后1000ms检查播放
                val delay = if (waitVideoPollCount <= 15) 300L else 1000L
                webView.evaluateJavascript(
                    """
                    (function(){
                        function setStage(s){ try{ document.title="CCTV|"+s; }catch(e){} }

                        // ===== 总超时（从页面开始加载计时） =====
                        var startTs = window.__cctvStartTs || Date.now();
                        var totalMs = Date.now() - startTs;
                        if(totalMs > 50000){
                            // 50秒无论任何状态都超时重试（卫视频道WASM DRM最长不应超过50秒）
                            setStage("TOTAL_TIMEOUT_"+Math.round(totalMs/1000)+"s");
                            console.error("CCTV_TOTAL_TIMEOUT after "+Math.round(totalMs/1000)+"s");
                            return JSON.stringify({state:"VIDEO_ERROR", code:-5, msg:"total_timeout_50s"});
                        }

                        // --- 1. 寻找视频元素 ---
                        var v = document.querySelector("video.video-js");
                        if(!v){
                            // WAIT_VIDEO 超时检测（20秒内必须出现video元素）
                            if(totalMs > 20000){
                                setStage("NO_VIDEO_TIMEOUT_"+Math.round(totalMs/1000)+"s");
                                console.error("CCTV_NO_VIDEO_TIMEOUT after "+Math.round(totalMs/1000)+"s");
                                return JSON.stringify({state:"VIDEO_ERROR", code:-4, msg:"no_video_timeout_20s"});
                            }
                            setStage("NO_VIDEO_"+Math.round(totalMs/1000)+"s");
                            return JSON.stringify({state:"WAIT_VIDEO", elapsed:totalMs});
                        }

                        // --- 1b. 检查视频错误（HEVC/codec不支持 / 播放过程中error事件） ---
                        if(v.error || window.__cctvVideoError){
                            var err = v.error || window.__cctvVideoError;
                            var ec = err.code;
                            var em = err.msg || err.message || "";
                            setStage("VIDEO_ERR_"+ec);
                            console.error("CCTV_VIDEO_ERROR code="+ec+" msg="+em);
                            return JSON.stringify({state:"VIDEO_ERROR", code:ec, msg:em});
                        }

                        // --- 1c. 统一停滞检测（不依赖paused状态！） ---
                        // 只要 readyState<2 且 currentTime===0，不论paused是true还是false，都计时
                        // 因为 v.play() 立即把 paused=false，但实际上流还没下载到任何数据
                        var noProgress = (v.readyState < 2) && (v.currentTime === 0);
                        if(noProgress){
                            if(!window.__cctvNoProgStart) window.__cctvNoProgStart = Date.now();
                            var noProgMs = Date.now() - window.__cctvNoProgStart;
                            if(noProgMs > 25000){
                                // 25秒内没有任何进展：readyState<2 且 currentTime始终=0
                                setStage("NO_PROGRESS_"+Math.round(noProgMs/1000)+"s");
                                console.error("CCTV_NO_PROGRESS after "+Math.round(noProgMs/1000)+"s rs="+v.readyState+" paused="+v.paused);
                                return JSON.stringify({state:"VIDEO_ERROR", code:-2, msg:"no_progress_25s"});
                            }
                        } else {
                            window.__cctvNoProgStart = null;
                        }

                        // --- 1d. playingNoData 检测（可选补充：play()后在"播放"状态但readyState<2持续一段时间）
                        // 这种情况 currentTime 可能>0但readyState又掉回<2（直播网络抖动时瞬间不算）
                        var playingNoData = (!v.paused) && (v.readyState < 2);
                        if(playingNoData){
                            if(!window.__cctvStuckStart) window.__cctvStuckStart = Date.now();
                            var stuckMs = Date.now() - window.__cctvStuckStart;
                            if(stuckMs > 20000){
                                setStage("STUCK_PLAY_"+Math.round(stuckMs/1000)+"s");
                                console.error("CCTV_STUCK_PLAY after "+Math.round(stuckMs/1000)+"s");
                                return JSON.stringify({state:"VIDEO_ERROR", code:-3, msg:"playing_stuck_20s"});
                            }
                        } else {
                            window.__cctvStuckStart = null;
                        }

                        // ========================================================================
                        // 阶段A（一次性，在 v.readyState < 2 时）：布局预先应用
                        // ========================================================================
                        if(!window.__cctvLayoutDone && v.readyState < 2){
                            window.__cctvLayoutDone = true;
                            var Wpx = window.innerWidth + "px";
                            var Hpx = window.innerHeight + "px";

                            document.body.style.setProperty("margin","0","important");
                            document.body.style.setProperty("padding","0","important");
                            document.body.style.setProperty("background","#000","important");
                            document.body.style.setProperty("overflow","hidden","important");
                            document.documentElement.style.setProperty("margin","0","important");
                            document.documentElement.style.setProperty("padding","0","important");
                            document.documentElement.style.setProperty("background","#000","important");
                            document.documentElement.style.setProperty("overflow","hidden","important");

                            var app = document.getElementById("app");
                            if(app){
                                app.style.setProperty("position","fixed","important");
                                app.style.setProperty("left","0px","important");
                                app.style.setProperty("top","0px","important");
                                app.style.setProperty("width",Wpx,"important");
                                app.style.setProperty("height",Hpx,"important");
                                app.style.setProperty("background","#000","important");
                                app.style.setProperty("overflow","hidden","important");
                                app.style.setProperty("z-index","999999","important");
                                app.style.setProperty("margin","0","important");
                                app.style.setProperty("padding","0","important");
                                app.style.setProperty("max-width","none","important");
                                app.style.setProperty("min-width","0","important");
                            }

                            // 回溯路径（不删节点！只设样式）
                            var path = [];
                            var p = v, depth = 25;
                            while(p && depth-- > 0){
                                path.push(p);
                                if(p.id === "app") break;
                                p = p.parentElement;
                            }
                            var pathSet = new Set(path);
                            for(var di = 0; di < path.length; di++){
                                var node = path[di];
                                if(node === v || node === app) continue;
                                node.style.setProperty("position","absolute","important");
                                node.style.setProperty("left","0px","important");
                                node.style.setProperty("top","0px","important");
                                node.style.setProperty("width","100%","important");
                                node.style.setProperty("height","100%","important");
                                node.style.setProperty("margin","0","important");
                                node.style.setProperty("padding","0","important");
                                node.style.setProperty("overflow","hidden","important");
                                node.style.setProperty("background","#000","important");
                                node.style.setProperty("display","block","important");
                                node.style.setProperty("max-width","none","important");
                                node.style.setProperty("min-width","0","important");
                                node.style.setProperty("flex","none","important");
                                var parent = node.parentElement;
                                if(parent){
                                    var sibs = parent.children;
                                    for(var sj = 0; sj < sibs.length; sj++){
                                        var s = sibs[sj];
                                        if(!pathSet.has(s)){
                                            s.style.setProperty("display","none","important");
                                            s.style.setProperty("visibility","hidden","important");
                                            s.style.setProperty("opacity","0","important");
                                        }
                                    }
                                }
                            }

                            var vc = document.querySelector(".video-con");
                            if(vc){
                                vc.style.setProperty("position","absolute","important");
                                vc.style.setProperty("left","0px","important");
                                vc.style.setProperty("top","0px","important");
                                vc.style.setProperty("width",Wpx,"important");
                                vc.style.setProperty("height",Hpx,"important");
                                vc.style.setProperty("background","#000","important");
                                vc.style.setProperty("z-index","2","important");
                                vc.style.setProperty("margin","0","important");
                                vc.style.setProperty("padding","0","important");
                            }
                            var vb = document.querySelector('[id^="vodbox"]');
                            if(vb){
                                vb.style.setProperty("position","absolute","important");
                                vb.style.setProperty("left","0px","important");
                                vb.style.setProperty("top","0px","important");
                                vb.style.setProperty("width",Wpx,"important");
                                vb.style.setProperty("height",Hpx,"important");
                                vb.style.setProperty("background","#000","important");
                                vb.style.setProperty("z-index","1","important");
                                vb.style.setProperty("margin","0","important");
                                vb.style.setProperty("padding","0","important");
                                vb.style.setProperty("overflow","hidden","important");
                                for(var vk = 0; vk < vb.children.length; vk++){
                                    var vbc = vb.children[vk];
                                    var cls = (vbc.className||"").toString();
                                    if(cls.indexOf("video-con") < 0){
                                        vbc.style.setProperty("display","none","important");
                                        vbc.style.setProperty("visibility","hidden","important");
                                        vbc.style.setProperty("opacity","0","important");
                                    }
                                }
                            }
                            v.style.setProperty("position","absolute","important");
                            v.style.setProperty("left","0px","important");
                            v.style.setProperty("top","0px","important");
                            v.style.setProperty("width",Wpx,"important");
                            v.style.setProperty("height",Hpx,"important");
                            v.style.setProperty("object-fit","contain","important");
                            v.style.setProperty("background","#000","important");
                            v.style.setProperty("z-index","3","important");
                            v.style.setProperty("opacity","1","important");
                            v.style.setProperty("visibility","visible","important");
                            v.style.setProperty("display","block","important");

                            setStage("LAYOUT_PRE_rs"+v.readyState);
                            return JSON.stringify({state:"LAYOUT_PRE", rs:v.readyState});
                        }

                        // ========================================================================
                        // 阶段B：视频就绪判定 + play
                        // ========================================================================
                        var hasTime = v.currentTime > 0;
                        var videoReady = (v.readyState>=2) || hasTime;

                        if(!videoReady){
                            // 尝试点击播放器按钮 + v.play()
                            var pb = document.querySelector(".play.play2");
                            if(pb && v.paused){ try{ pb.click(); }catch(e){} }
                            try{ v.play(); }catch(e){}
                            var waitInfo = "rs"+v.readyState+" p"+(v.paused?"Y":"N")+" t"+Math.round(v.currentTime*10)/10;
                            if(window.__cctvNoProgStart){
                                waitInfo += " np"+Math.round((Date.now()-window.__cctvNoProgStart)/1000);
                            }
                            if(window.__cctvStuckStart){
                                waitInfo += " sk"+Math.round((Date.now()-window.__cctvStuckStart)/1000);
                            }
                            setStage("WAIT_RDY_"+waitInfo);
                            return JSON.stringify({state:"WAIT_READY",rs:v.readyState,paused:v.paused,ct:v.currentTime});
                        }

                        // 像素级最后校验（只用style，不改DOM）
                        var Wpx2 = window.innerWidth + "px";
                        var Hpx2 = window.innerHeight + "px";
                        var vc2 = document.querySelector(".video-con");
                        if(vc2){
                            vc2.style.setProperty("width",Wpx2,"important");
                            vc2.style.setProperty("height",Hpx2,"important");
                        }
                        var vb2 = document.querySelector('[id^="vodbox"]');
                        if(vb2){
                            vb2.style.setProperty("width",Wpx2,"important");
                            vb2.style.setProperty("height",Hpx2,"important");
                        }
                        v.style.setProperty("width",Wpx2,"important");
                        v.style.setProperty("height",Hpx2,"important");
                        v.style.setProperty("left","0px","important");
                        v.style.setProperty("top","0px","important");
                        v.style.setProperty("opacity","1","important");
                        v.style.setProperty("visibility","visible","important");

                        try{ v.play(); }catch(e){}

                        var r = v.getBoundingClientRect();
                        setStage("OK_"+Math.round(r.width)+"x"+Math.round(r.height));
                        return JSON.stringify({
                            state:"FULLSCREEN",
                            finalRect:{x:r.x,y:r.y,w:r.width,h:r.height}
                        });
                    })();
                    """,
                ) { result ->
                    Log.d("CCTV_FULLSCREEN", result)
                    when {
                        result.contains("FULLSCREEN") -> {
                            fullscreenDone = true
                            waitVideoRunnable = null
                            waitVideoStarted = false
                            jsLevelRetryCount = 0
                            hideSystemUI()
                        }
                        result.contains("VIDEO_ERROR") -> {
                            // 停止轮询，进入重试流程
                            waitVideoRunnable = null
                            waitVideoStarted = false
                            handleVideoError(result)
                            return@evaluateJavascript
                        }
                    }
                }
                if (!fullscreenDone) {
                    waitVideoRunnable = this
                    handler.postDelayed(this, delay)
                }
            }
        }
        waitVideoRunnable = runnable
        handler.post(runnable)
    }

    /**
     * 视频错误处理 - 分层重试策略：
     * 1. 先尝试 JS 层 v.load() 重载（轻量，不重建WebView/页面） - 最多2次
     * 2. JS层重试失败后，整页刷新 loadUrl - 最多3次
     */
    private fun handleVideoError(errorResult: String) {
        val chName = ChannelManager.channelNames.getOrElse(currentChannel - 1) { "unknown" }

        // 第一阶段：JS层重载（尝试v.load()，不刷新整页，更快恢复）
        if (jsLevelRetryCount < MAX_JS_RETRIES) {
            jsLevelRetryCount++
            Log.e("CCTV_VIDEO", "视频错误(JS重试 $jsLevelRetryCount/$MAX_JS_RETRIES) -> v.load(): $errorResult")
            // 重置JS端的超时时间戳和停滞标记
            webView.evaluateJavascript(
                """
                (function(){
                    try{
                        window.__cctvStartTs=Date.now();
                        window.__cctvNoProgStart=null;
                        window.__cctvStuckStart=null;
                        window.__cctvVideoError=null;
                        var v=document.querySelector('video.video-js');
                        if(v){
                            try{ v.pause(); }catch(e){}
                            try{ v.load(); }catch(e){}
                            // 尝试点击 .play.play2 按钮触发播放器重新鉴权
                            setTimeout(function(){
                                try{
                                    var pb=document.querySelector('.play.play2');
                                    if(pb){ pb.click(); }
                                    var v2=document.querySelector('video.video-js');
                                    if(v2){ v2.play(); }
                                }catch(e){}
                            }, 500);
                        }
                    }catch(e){}
                })();
                """.trimIndent(), null
            )
            // 2秒后重新启动轮询（给JS重载留时间）
            handler.postDelayed({
                if (!fullscreenDone) {
                    waitVideoPollCount = 0

                }
            }, 2000)
            return
        }

        // 第二阶段：整页刷新（JS层重试已用完）
        videoRetryCount++
        jsLevelRetryCount = 0
        if (videoRetryCount <= maxVideoRetries) {
            Log.e("CCTV_VIDEO", "视频错误(整页刷新 $videoRetryCount/$maxVideoRetries)，3秒后重载页面: $errorResult")
            // 渐退延迟：1次3秒，2次5秒，3次8秒
            val delayMs = when (videoRetryCount) {
                1 -> 3000L
                2 -> 5000L
                else -> 8000L
            }
            handler.postDelayed({
                Log.d("CCTV_VIDEO", "[$chName] 整页刷新 (第$videoRetryCount 次)")
                fullscreenDone = false
                waitVideoStarted = false
                waitVideoPollCount = 0
                layoutDone = false
                loadingProgress.visibility = View.VISIBLE
                webView.loadUrl(ChannelManager.getChannelUrl(currentChannel))
            }, delayMs)
        } else {
            Log.e("CCTV_VIDEO", "[$chName] 重试次数已用完(JS=$MAX_JS_RETRIES + 整页=$maxVideoRetries)，停止重试")
        }
    }

    // 4. 使用新的 WindowInsetsControllerCompat API 替代废弃的 systemUiVisibility
    private fun hideSystemUI() {
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ===== 生命周期性能优化 =====

    override fun onPause() {
        super.onPause()
        // 暂停 WebView 的 JS 定时器和视频播放，释放 CPU
        webView.onPause()
        // 只停止 waitVideo 轮询 Runnble，不移除 onPageStarted 的延迟启动器（handler.removeCallbacksAndMessages太粗暴）
        waitVideoRunnable?.let { handler.removeCallbacks(it) }
        waitVideoRunnable = null
        waitVideoStarted = false
    }

    override fun onResume() {
        super.onResume()
        // 恢复 WebView
        webView.onResume()
        // 如果还未完成全屏，尝试继续轮询
        if (!fullscreenDone && !waitVideoStarted) {
            waitVideoPollCount = 0
            //startWaitVideo()
        }
    }

    override fun onDestroy() {
        // 彻底清理 WebView，防止内存泄漏
        waitVideoRunnable?.let { handler.removeCallbacks(it) }
        waitVideoRunnable = null
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