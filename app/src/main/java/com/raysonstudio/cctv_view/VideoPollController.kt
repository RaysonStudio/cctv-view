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
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar

/**
 * 视频就绪轮询与重试控制器。
 *
 * 职责：
 * - 在页面加载后启动 300ms/1000ms 分阶段轮询（前 15 次快速检测，之后慢速检查）
 * - 阶段A（readyState<2 一次性）：预先应用全屏布局，避免解码器 Surface 在播放后失效
 * - 阶段B：视频就绪后回调 [Callback.onVideoFullscreen] 隐藏系统 UI
 * - 失败重试分层：先 JS 层 `v.load()`（最多 [MAX_JS_RETRIES] 次），再整页 `loadUrl`（最多 [MAX_VIDEO_RETRIES] 次）
 *
 * 所有轮询/重试状态封装在此，外部只需在生命周期与频道切换时调用对应方法。
 *
 * 注意：当前 [scheduleFromPageStart] / [scheduleFromPageFinished] / [onResume] 中的
 * `startWaitVideo()` 调用被注释（与原 MainActivity 行为一致），保留以便需要时直接启用。
 */
class VideoPollController(
    private val webView: WebView,
    private val handler: Handler,
    private val loadingProgress: ProgressBar,
    private val callback: Callback
) {

    interface Callback {
        /** 视频已成功全屏，调用方应隐藏系统 UI */
        fun onVideoFullscreen()
        /** 整页刷新重试时，调用方应重新加载当前频道 URL */
        fun reloadCurrentChannel()
        /** 取得当前频道（1-based），用于日志和重试 */
        fun getCurrentChannel(): Int
    }

    private var fullscreenDone = false
    private var waitVideoRunnable: Runnable? = null
    private var waitVideoStarted = false
    private var waitVideoPollCount = 0
    private var layoutDone = false
    private var videoRetryCount = 0
    private var jsLevelRetryCount = 0

    companion object {
        private const val MAX_VIDEO_RETRIES = 3 // 最多整页刷新 3 次
        private const val MAX_JS_RETRIES = 2     // JS 层 v.load() 最多 2 次，之后升级为整页刷新
    }

    /** 仅供日志输出使用：返回当前轮询/重试状态摘要（保留与原 MainActivity 一致的日志格式） */
    fun debugState(): String =
        "fullscreenDone=$fullscreenDone waitVideoStarted=$waitVideoStarted"

    /** 由 Activity.loadChannel 调用：切换频道时重置全部轮询/重试状态，并暂停旧视频 */
    fun resetForChannelChange() {
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
        // 停止旧的轮询 Runnable（注意：只移除 waitVideoRunnable，不移除 onPageStarted 的 1 秒延迟启动器）
        waitVideoRunnable?.let { handler.removeCallbacks(it) }
        waitVideoRunnable = null
        waitVideoStarted = false
        fullscreenDone = false
        videoRetryCount = 0
        waitVideoPollCount = 0
        layoutDone = false
        jsLevelRetryCount = 0
    }

    /** 由 WebViewClient.onPageStarted 触发：1 秒后启动轮询（若未启动且未完成） */
    fun scheduleFromPageStart() {
        // 启动轮询：确保只有一个 Runnable 在运行
        // 取消之前任何遗留的轮询
        waitVideoRunnable?.let { handler.removeCallbacks(it) }
        waitVideoRunnable = null
        waitVideoStarted = false
        // 早期轮询：1 秒后开始（不等 onPageFinished，卫视频道 WASM DRM 初始化慢）
        handler.postDelayed({
            if (!fullscreenDone && !waitVideoStarted) {
                //startWaitVideo()
            }
        }, 1000)
    }

    /** 由 WebViewClient.onPageFinished 触发：立即启动轮询（若未启动且未完成） */
    fun scheduleFromPageFinished() {
        // onPageFinished 时如果轮询还没启动，立即启动（否则等待已有轮询继续）
        if (!fullscreenDone && !waitVideoStarted) {
            // 如果 onPageStarted 的 1 秒延迟还没到，这里立即启动轮询
            //startWaitVideo()
        }
    }

    /** 由 Activity.onResume 触发：未完成全屏时尝试继续轮询 */
    fun onResume() {
        // 如果还未完成全屏，尝试继续轮询
        if (!fullscreenDone && !waitVideoStarted) {
            waitVideoPollCount = 0
            //startWaitVideo()
        }
    }

    /** 由 Activity.onPause 触发：仅停止 waitVideo 轮询 Runnable，不移除 onPageStarted 的延迟启动器 */
    fun onPause() {
        waitVideoRunnable?.let { handler.removeCallbacks(it) }
        waitVideoRunnable = null
        waitVideoStarted = false
    }

    /** 由 Activity.onDestroy 触发：清理 Runnable 引用 */
    fun onDestroy() {
        waitVideoRunnable?.let { handler.removeCallbacks(it) }
        waitVideoRunnable = null
    }

    /**
     * 启动视频等待轮询。确保全局只有一个 Runnable 在运行：
     * - loadChannel 重置 waitVideoStarted=false
     * - onPageStarted 延迟 1 秒 或 onPageFinished 立即 调用 startWaitVideo()
     * - startWaitVideo() 检查 waitVideoStarted，只有 false 才创建新 Runnable
     */
    @Suppress("unused") // 当前在 scheduleFromPageStart/Finished/onResume 中被注释调用，保留以便启用
    private fun startWaitVideo() {
        if (waitVideoStarted) return
        waitVideoStarted = true

        val runnable = object : Runnable {
            override fun run() {
                if (fullscreenDone) return
                waitVideoPollCount++
                // 前15次300ms快速检测（video出现+布局阶段），之后1000ms检查播放
                val delay = if (waitVideoPollCount <= 15) 300L else 1000L
                webView.evaluateJavascript(WAIT_VIDEO_JS) { result ->
                    Log.d("CCTV_FULLSCREEN", result)
                    when {
                        result.contains("FULLSCREEN") -> {
                            fullscreenDone = true
                            waitVideoRunnable = null
                            waitVideoStarted = false
                            jsLevelRetryCount = 0
                            callback.onVideoFullscreen()
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
     * 1. 先尝试 JS 层 v.load() 重载（轻量，不重建 WebView/页面） - 最多 [MAX_JS_RETRIES] 次
     * 2. JS 层重试失败后，整页刷新 loadUrl - 最多 [MAX_VIDEO_RETRIES] 次
     */
    private fun handleVideoError(errorResult: String) {
        val currentChannel = callback.getCurrentChannel()
        val chName = ChannelManager.channelNames.getOrElse(currentChannel - 1) { "unknown" }

        // 第一阶段：JS 层重载（尝试 v.load()，不刷新整页，更快恢复）
        if (jsLevelRetryCount < MAX_JS_RETRIES) {
            jsLevelRetryCount++
            Log.e("CCTV_VIDEO", "视频错误(JS重试 $jsLevelRetryCount/$MAX_JS_RETRIES) -> v.load(): $errorResult")
            // 重置 JS 端的超时时间戳和停滞标记
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
            // 2 秒后重新启动轮询（给 JS 重载留时间）
            handler.postDelayed({
                if (!fullscreenDone) {
                    waitVideoPollCount = 0
                    //startWaitVideo()
                }
            }, 2000)
            return
        }

        // 第二阶段：整页刷新（JS 层重试已用完）
        videoRetryCount++
        jsLevelRetryCount = 0
        if (videoRetryCount <= MAX_VIDEO_RETRIES) {
            Log.e("CCTV_VIDEO", "视频错误(整页刷新 $videoRetryCount/$MAX_VIDEO_RETRIES)，3秒后重载页面: $errorResult")
            // 渐退延迟：1 次 3 秒，2 次 5 秒，3 次 8 秒
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
                callback.reloadCurrentChannel()
            }, delayMs)
        } else {
            Log.e("CCTV_VIDEO", "[$chName] 重试次数已用完(JS=$MAX_JS_RETRIES + 整页=$MAX_VIDEO_RETRIES)，停止重试")
        }
    }

    /**
     * 视频就绪轮询脚本：
     * - 调用 cctvClean() 兜底清理
     * - 总超时 50 秒、video 元素出现超时 20 秒、停滞超时 25 秒、播放但无数据超时 20 秒
     * - 阶段 A：readyState<2 时一次性预应用全屏布局
     * - 阶段 B：视频就绪后触发 play 并返回 FULLSCREEN 状态
     */
    private val WAIT_VIDEO_JS = """
        (function(){
            try{ if(window.cctvClean) window.cctvClean(); }catch(e){}
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
    """.trimIndent()
}
