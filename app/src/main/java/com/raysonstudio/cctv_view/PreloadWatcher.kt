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
import com.tencent.smtt.sdk.WebView

/**
 * 后台预载 WebView 的轻量就绪探测器。
 *
 * 与 [VideoPollController] 不同，这里只做最便宜的检测：每隔 500ms 用一行 JS
 * 查询 video 元素的 readyState/currentTime/error，不执行任何清理或布局 JS，
 * 避免在后台页面上重复运行重活、与前台播放抢 CPU。
 *
 * 用途：双缓冲换台时，前台保持观看当前频道，后台预载下一频道；
 * 探测到后台视频真正可播（READY）后通知 [Callback.onPreloadReady]，由 Activity 决定
 * 立即交换（用户正在切向该频道）或先暂停省电（空闲预载完成）。
 */
class PreloadWatcher(
    private val webView: WebView,
    private val handler: Handler,
    private val callback: Callback
) {

    interface Callback {
        /** 后台视频已就绪（readyState>=2 或已有播放进度） */
        fun onPreloadReady(webView: WebView)

        /** 后台视频出现错误（codec 不支持等），仅作日志/统计 */
        fun onPreloadError(webView: WebView, description: String)
    }

    private var started = false
    private var ready = false
    private var pollCount = 0
    private var runnable: Runnable? = null

    /** 是否已探测到视频就绪 */
    val isReady: Boolean get() = ready

    /** 开始探测（幂等；每次页面重新开始加载前应先 [reset]） */
    fun start() {
        if (started) return
        started = true
        ready = false
        pollCount = 0
        val r = object : Runnable {
            override fun run() {
                if (!started) return
                webView.evaluateJavascript(READY_JS) { result ->
                    if (!started) return@evaluateJavascript
                    when {
                        result.contains("READY") -> {
                            ready = true
                            callback.onPreloadReady(webView)
                        }
                        result.contains("ERROR") -> {
                            callback.onPreloadError(webView, result)
                            // 继续探测：页面自身可能有重试逻辑
                            reschedule(this)
                        }
                        else -> reschedule(this)
                    }
                }
            }
        }
        runnable = r
        handler.postDelayed(r, 300)
    }

    /** 停止探测并复位（页面重新加载/换台时调用） */
    fun reset() {
        started = false
        ready = false
        pollCount = 0
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }

    private fun reschedule(r: Runnable) {
        if (!started) return
        pollCount++
        // 最多探测 40 秒；之后交给 Activity 的看门狗兜底
        if (pollCount < 80) {
            handler.postDelayed(r, 500)
        } else {
            started = false
        }
    }

    companion object {
        // 就绪判定：后台页为"静音播放"状态，currentTime 前进 / readyState>=2 /
        // playing 事件 任一命中即视为真正可播，交换后取消静音即可无缝续播。
        private const val READY_JS = """
            (function(){
                if(window.__cctvPreloadReady) return 'READY';
                var v=document.querySelector('video.video-js');
                if(!v) return 'NOVIDEO';
                if(v.error) return 'ERROR:'+(v.error.code||-1);
                if(v.readyState>=2 || v.currentTime>0 || window.__cctvPreloadMeta) return 'READY';
                return 'WAIT:'+v.readyState;
            })();
        """
    }
}
