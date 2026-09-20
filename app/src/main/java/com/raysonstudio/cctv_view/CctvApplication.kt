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

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex

/**
 * 应用入口 Application。
 *
 * - [attachBaseContext]：安装 MultiDex（minSdk < 21 时 65536 方法数限制需要）
 * - [onCreate]：尽早初始化腾讯 X5 (TBS) 内核，触发内核下载/安装，
 *   使 [MainActivity] 创建 WebView 时能优先使用 X5 内核。
 */
class CctvApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        X5KernelManager.initialize(this)
    }
}
