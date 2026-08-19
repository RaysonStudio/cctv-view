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

import android.graphics.Bitmap
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 央视频首页的 [WebViewClient]：
 * - 主文档同步拦截央视频 TV 首页 HTML，注入全屏 CSS（[CctvWebConfig.interceptMainFrame]）
 * - 第三方资源（监控/统计/图片/字体）拦截，省流量并避免广告
 * - 央视频自家域名 / 视频流 / 核心脚本全部放行
 *
 * 与 UI 状态相关的回调（loading 切换、JS 清理、轮询调度等）通过 [listener] 暴露给 Activity，
 * 让 WebViewClient 自身保持无状态、可单测。
 */
class CctvWebViewClient(
    private val userAgent: String,
    private val listener: Listener
) : WebViewClient() {

    interface Listener {
        /** 主文档开始加载：Activity 应显示 loading/splash 并执行 onPageStarted 阶段的 JS */
        fun onPageStarted(url: String?)

        /** 主文档加载完成：Activity 应隐藏 loading 并执行 cctvClean 兜底 */
        fun onPageFinished(url: String?)

        /** 主文档即将可见：Activity 应再次执行 cctvClean */
        fun onPageCommitVisible(url: String?)

        /** 主文档加载失败（非 2xx、网络异常等）：Activity 应隐藏 loading 进度条 */
        fun onMainFrameError(description: String?)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null

        // 主文档：同步拦截央视频 TV 首页，在 <head> 中注入全屏 CSS
        if (request.isForMainFrame) {
            if (url.contains("yangshipin.cn") && url.contains("/tv/home")) {
                Log.d("CCTV_INTERCEPT", "HIT mainFrame url=$url")
                return CctvWebConfig.interceptMainFrame(request, url, userAgent)
            } else {
                Log.d("CCTV_INTERCEPT", "MISS mainFrame url=$url")
            }
        }

        // blob: / data:：放行（视频内部流）
        if (url.startsWith("blob:") || url.startsWith("data:")) return null

        val urlLower = url.lowercase()

        // 1. 央视频自家域名：全部放行（serverTime/play/鉴权/CDN等都是关键请求）
        for (domain in CctvWebConfig.TRUSTED_DOMAINS) {
            if (urlLower.contains(domain)) return null
        }

        // 2. 视频流 / 直播分片：放行
        for (ext in CctvWebConfig.VIDEO_EXTENSIONS) {
            if (urlLower.contains(ext)) return null
        }

        // 3. 核心脚本/样式：放行
        if (urlLower.endsWith(".js") || urlLower.endsWith(".js?") ||
            urlLower.endsWith(".css") || urlLower.endsWith(".css?") ||
            urlLower.contains("videojs") || urlLower.contains("video-js")
        ) return null

        // 4. 命中拦截关键词：取消
        for (b in CctvWebConfig.BLOCK_KEYWORDS) {
            if (urlLower.contains(b)) {
                Log.d("CCTV_BLOCK", "drop: ${url.take(80)}")
                return CctvWebConfig.createEmptyResponse()
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
            return CctvWebConfig.createEmptyResponse()
        }

        // 6. 其他请求：放行（避免误伤播放器需要的未知资源）
        return null
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
        listener.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        listener.onPageFinished(url)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        listener.onPageCommitVisible(url)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            listener.onMainFrameError(error?.description?.toString())
        }
    }
}
