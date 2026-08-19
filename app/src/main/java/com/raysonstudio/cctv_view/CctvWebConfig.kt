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

import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import java.net.HttpURLConnection
import java.net.URL

/**
 * 央视频 WebView 配置、URL 拦截策略与 CSS 注入工具集中在此。
 *
 * - [EARLY_CSS]：在 HTML <head> 中同步注入的全屏 CSS，从渲染管线源头隐藏非播放器元素
 * - [TRUSTED_DOMAINS] / [VIDEO_EXTENSIONS] / [BLOCK_KEYWORDS]：拦截策略的放行/拦截清单
 * - [applySettings]：WebSettings 统一配置（桌面 Chrome UA、性能开关等）
 * - [interceptMainFrame]：同步抓取首页 HTML 并注入全屏 CSS
 * - [buildPageStartScript]：onPageStarted 阶段使用的兜底清理 + 监听 JS
 */
object CctvWebConfig {

    // 在 HTML <head> 中同步注入的全屏 CSS（避免 JS 异步注入与页面渲染竞争）
    val EARLY_CSS: String = buildString {
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

    // 央视频自家域名：全部放行（包含播放器API、时间同步、鉴权、WASM DRM等关键请求）
    val TRUSTED_DOMAINS = listOf(
        "yangshipin.cn",   // 所有子域：www/sapi/csapi/player-api/h5access/s/aatc-api/btrace/pcsite
        "ysp.cctv.cn",     // CDN直播流域名
        "cctv.cn"          // CCTV通用域
    )

    // 视频流后缀
    val VIDEO_EXTENSIONS = setOf(
        ".m3u8", ".mpd", ".m4s", ".m4a", ".m4v", ".ts", ".aac", ".ac3", ".ec3",
        ".mp4", ".webm", ".ogg", ".ogv", ".flv", ".mov", ".vp9", ".av1",
        ".wasm"  // WASM DRM 解密模块（卫视频道需要）
    )

    // 明确需要拦截的第三方资源（非央视频域名）
    val BLOCK_KEYWORDS = listOf(
        "sentry", "rum",                        // 监控
        "google", "baidu", "cnzz", "umeng"      // 第三方统计
    )

    /**
     * 配置 WebView 的 WebSettings，返回桌面 Chrome UA。
     */
    fun applySettings(webView: WebView): String {
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
        val userAgent = settings.userAgentString
        settings.loadsImagesAutomatically = false
        settings.blockNetworkImage = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // ===== 性能优化 =====
        // 预加载 DNS：央视频核心域名提前解析
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.settings.offscreenPreRaster = true // 离屏预渲染
        }
        // 视口设置：确保 100% 尺寸正确映射到 WebView 像素
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // 禁用不需要的功能
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setGeolocationEnabled(false)
        // 启用 WebView 调试（可通过 chrome://inspect 查看 console 日志）
        WebView.setWebContentsDebuggingEnabled(true)

        return userAgent
    }

    /**
     * 把 <style> 块注入到 HTML <head> 中（若找不到 </head> 则回退到 </html> 或文档首部）。
     */
    fun injectCssIntoHtml(html: String, css: String): String {
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

    /**
     * 构造一个空的 204 响应，用于拦截第三方资源（图片/字体/监控脚本等）。
     */
    fun createEmptyResponse(): WebResourceResponse {
        val emptyStream = java.io.ByteArrayInputStream(ByteArray(0))
        return WebResourceResponse(
            "text/plain", "utf-8", 204,
            "No Content", emptyMap<String, String>(), emptyStream
        )
    }

    /**
     * 同步抓取央视频 TV 首页 HTML，注入 [EARLY_CSS] 后以 [WebResourceResponse] 形式返回。
     * 复制原请求头与 Cookie，保证上游鉴权逻辑正常。
     */
    fun interceptMainFrame(
        request: WebResourceRequest,
        url: String,
        userAgent: String
    ): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) {
            Log.d("CCTV_INTERCEPT", "SKIP non-GET method=${request.method} url=$url")
            return null
        }
        Log.d("CCTV_INTERCEPT", "START fetch url=$url")
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
            connection.setRequestProperty("User-Agent", userAgent)

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
                Log.w("CCTV_INTERCEPT", "FAIL non-2xx code=$responseCode url=$url")
                return null
            }

            val contentType = connection.contentType ?: "text/html; charset=utf-8"
            val charset = contentType.substringAfter("charset=", "utf-8")
                .trim(' ', '\"', '\'')
                .ifEmpty { "utf-8" }

            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val modifiedHtml = injectCssIntoHtml(html, EARLY_CSS)
            val bytes = modifiedHtml.toByteArray(Charsets.UTF_8)
            Log.d("CCTV_INTERCEPT", "OK code=$responseCode html=${html.length} modified=${modifiedHtml.length} cssLen=${EARLY_CSS.length} url=$url")

            WebResourceResponse(
                contentType.substringBefore(";"),
                charset,
                responseCode,
                connection.responseMessage ?: "OK",
                emptyMap(),
                java.io.ByteArrayInputStream(bytes)
            )
        } catch (e: Exception) {
            Log.e("CCTV_INTERCEPT", "EXCEPTION ${e.javaClass.simpleName}: ${e.message} url=$url")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 构建 onPageStarted 阶段的兜底 JS：
     * - 重置全局状态标记
     * - 若同步 HTML 注入未生效，则用 JS 补充 CSS
     * - 调用 cctvClean() 清理非播放器元素（含 MutationObserver 持续监听 SPA 动态节点）
     * - 绑定 video error/abort 事件，便于轮询阶段读取错误码
     */
    fun buildPageStartScript(): String {
        val cssJs = EARLY_CSS.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        return """
            (function(){
                console.log('[CCTV_JS] onPageStarted');
                try{ if(window.cctvObserver){ window.cctvObserver.disconnect(); window.cctvObserver=null; } }catch(e){}
                try{ if(window.cctvClean){ window.cctvClean(); } }catch(e){}
                window.__cctvInited=false;
                window.__cctvLayoutDone=false;
                window.__cctvStallStart=null;
                window.__cctvStuckStart=null;
                window.__cctvVideoError=null;
                window.__cctvStartTs=Date.now();
                // 兜底：若同步 HTML 注入未生效，则通过 JS 补充 CSS
                var hasStyle=!!document.getElementById('cctv-early-css');
                console.log('[CCTV_JS] earlyCss exists='+hasStyle);
                if(!hasStyle){
                    try{
                        var s=document.createElement('style');
                        s.id='cctv-early-css';
                        s.textContent='$cssJs';
                        if(document.head){document.head.appendChild(s);}
                        else{document.documentElement.appendChild(s);}
                        console.log('[CCTV_JS] fallback CSS injected');
                    }catch(e){
                        console.log('[CCTV_JS] fallback CSS inject error: '+e);
                    }
                }
                var v=document.querySelector('video.video-js');
                console.log('[CCTV_JS] video found='+(v?1:0)+' size='+(v?v.videoWidth+'x'+v.videoHeight:'none')+' readyState='+(v?v.readyState:-1));

                // ===== 安全删除冗余元素（只删除不在视频播放路径上的节点） =====
                window.__cctvCleaning=false;
                window.cctvClean=function(){
                    if(window.__cctvCleaning) return;
                    window.__cctvCleaning=true;
                    var totalRemoved=0;
                    var totalHidden=0;
                    try{
                        var v=document.querySelector('video.video-js');
                        var selectors=[
                            // 左右侧栏目
                            '.tv-zhan','.tv-zhan-shadow','.tv-zhan-con','.tv-zhan-title','.tv-zhan-kai','.tv-zhan-list',
                            '.tv-main-con-r','.tv-main-con-r-tab','.tv-main-con-r-list',
                            // 播放器控制/提示
                            '.own-toast','.y-full-control','.y-full','.y-full-bg','.con.poster','.loading',
                            '[class*="control-outside"]','.progress-bar','.progress-btn-wrapper','.voice','.bei','.pip',
                            '.videoFull','.full','.volume-muted-tip-container','.video-status-tip',
                            // 广告/推广
                            '[class*="advert"]','[class*="promotion"]','[class*="banner"]','[class*="ad-"]',
                            // 导航/页眉页脚
                            'header','footer','nav','.header','.footer','.nav','.navbar',
                            // 央视频常见其他冗余
                            '.ysp-header','.ysp-footer','.ysp-nav','.ysp-menu','.ysp-drawer',
                            '.tv-home-title','.tv-home-header','.channel-list','.program-list',
                            '.live-list','.recommend','.related','.comment','.chat','.danmu',
                            // 弹窗浮层
                            '.dialog','.modal','.popup','.mask','.overlay','.toast'
                        ];
                        for(var i=0;i<selectors.length;i++){
                            try{
                                var nodes=document.querySelectorAll(selectors[i]);
                                if(nodes.length>0) console.log('[CCTV_CLEAN] selector='+selectors[i]+' matched='+nodes.length);
                                for(var j=0;j<nodes.length;j++){
                                    var node=nodes[j];
                                    if(!node.parentNode) continue;
                                    // 安全：不删除包含 video 或被 video 包含的节点
                                    if(v && (node.contains(v) || v.contains(node))){
                                        continue;
                                    }
                                    // 先隐藏再删除，双保险防止闪现
                                    try{
                                        node.style.setProperty('display','none','important');
                                        node.style.setProperty('visibility','hidden','important');
                                        node.style.setProperty('opacity','0','important');
                                        node.style.setProperty('pointer-events','none','important');
                                        node.parentNode.removeChild(node);
                                        totalRemoved++;
                                    }catch(e){
                                        totalHidden++;
                                    }
                                }
                            }catch(e){
                                console.log('[CCTV_CLEAN] selector='+selectors[i]+' error='+e);
                            }
                        }

                        // 兜底1：删除 #app 下不在视频路径上的兄弟分支
                        if(v){
                            var app=document.getElementById('app');
                            if(app){
                                var appKids=Array.from(app.children);
                                for(var ak=0;ak<appKids.length;ak++){
                                    var kid=appKids[ak];
                                    var onPath=false;
                                    var vp=kid;
                                    while(vp){
                                        if(vp===v){ onPath=true; break; }
                                        vp=vp.parentElement;
                                    }
                                    if(!onPath){
                                        // 也可能是 video 的祖先容器
                                        var anc=v.parentElement;
                                        while(anc){
                                            if(anc===kid){ onPath=true; break; }
                                            anc=anc.parentElement;
                                        }
                                    }
                                    if(!onPath && kid!==v){
                                        try{
                                            kid.style.setProperty('display','none','important');
                                            kid.parentNode.removeChild(kid);
                                            totalRemoved++;
                                        }catch(e){ totalHidden++; }
                                    }
                                }
                            }
                        }

                        // 兜底2：body 下除 #app / script / style 外全部删除
                        var bodyKids=Array.from(document.body.children);
                        for(var bk=0;bk<bodyKids.length;bk++){
                            var bc=bodyKids[bk];
                            var tag=bc.tagName.toLowerCase();
                            if(bc.id!=='app' && tag!=='script' && tag!=='style'){
                                try{
                                    bc.style.setProperty('display','none','important');
                                    bc.parentNode.removeChild(bc);
                                    totalRemoved++;
                                }catch(e){ totalHidden++; }
                            }
                        }

                        // 兜底3：删除页面中所有图片（央视频不需要显示网页图片）
                        var imgs=document.querySelectorAll('img, picture, figure, svg, canvas');
                        for(var ii=0;ii<imgs.length;ii++){
                            var img=imgs[ii];
                            if(v && (img.contains(v) || v.contains(img))) continue;
                            try{
                                img.style.setProperty('display','none','important');
                                img.parentNode.removeChild(img);
                                totalRemoved++;
                            }catch(e){ totalHidden++; }
                        }
                    }catch(e){
                        console.log('[CCTV_CLEAN] top error='+e);
                    }
                    console.log('[CCTV_CLEAN] done videoFound='+(v?1:0)+' removed='+totalRemoved+' hidden='+totalHidden);
                    window.__cctvCleaning=false;
                };
                window.cctvClean();
                // 多次延迟重清，应对央视频 SPA 动态插入
                setTimeout(function(){ window.cctvClean(); }, 500);
                setTimeout(function(){ window.cctvClean(); }, 1500);
                setTimeout(function(){ window.cctvClean(); }, 3500);
                setTimeout(function(){ window.cctvClean(); }, 7000);
                if(!window.cctvObserver){
                    window.cctvObserver=new MutationObserver(function(mutations){
                        clearTimeout(window.__cctvCleanTimer);
                        window.__cctvCleanTimer=setTimeout(function(){ window.cctvClean(); }, 600);
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
        """.trimIndent()
    }
}
