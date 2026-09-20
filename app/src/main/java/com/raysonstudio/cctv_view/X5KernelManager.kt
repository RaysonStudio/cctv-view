package com.raysonstudio.cctv_view

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import com.tencent.smtt.export.external.TbsCoreSettings
import com.tencent.smtt.sdk.QbSdk
import com.tencent.smtt.sdk.TbsListener
import java.io.File

/**
 * 腾讯 X5 (TBS) 内核初始化与按架构自动下载。
 *
 * 策略（双保险）：
 * 1. QbSdk.initX5Environment：官方初始化，QbSdk 会按设备系统类型 + CPU 架构
 *    自动从腾讯 CDN 下载并安装合适的内核（64 位 / 32 位 / x86 等）。
 * 2. 若官方下载失败（onViewInitFinished 返回 false 且 canLoadX5 仍为 false），
 *    则根据 detectAbi 检测到的架构，通过 DownloadManager 显式下载对应版本的
 *    .tbs.apk 并 QbSdk.installLocalTbsCore 安装。
 *
 * 内核未就绪期间，com.tencent.smtt.sdk.WebView 会自动回退到系统 WebView，
 * 待内核安装完成后（通常下次冷启动）自动切换为 X5 内核。
 */
object X5KernelManager {

    private const val TAG = "X5Kernel"

    // TBS 内核版本：64 位与 32 位使用不同的内核包。
    // 下载地址与参考项目 CCTV_Viewer 一致，可按需替换为自有镜像。
    private const val TBS_VERSION_64 = 46007
    private const val TBS_VERSION_32 = 45738
    private const val TBS_URL_64 =
        "http://void-tech.cn/wp-content/uploads/2024/10/046007_x5.tbs_.apk"
    private const val TBS_URL_32 =
        "http://void-tech.cn/wp-content/uploads/2024/10/045738_x5.tbs_.apk"
    private const val TBS_FILE_64 = "046007_x5.tbs_.apk"
    private const val TBS_FILE_32 = "045738_x5.tbs_.apk"

    /** 设备 CPU 架构分类 */
    enum class Abi {
        ARM64, ARM32, X86_64, X86, UNKNOWN;

        val is64Bit: Boolean get() = this == ARM64 || this == X86_64
    }

    /** 某个架构对应的内核下载信息 */
    data class KernelInfo(
        val abi: Abi,
        val tbsVersion: Int,
        val url: String,
        val fileName: String,
    )

    /** 检测当前设备 CPU 架构 */
    fun detectAbi(): Abi {
        // API 21+ 使用 SUPPORTED_ABIS（按优先级排列）；老系统回退 CPU_ABI / CPU_ABI2
        @Suppress("DEPRECATION")
        val abis: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2)
        }
        for (abi in abis) {
            val a = abi.lowercase()
            when {
                a.contains("arm64") || a.contains("aarch64") -> return Abi.ARM64
                a.contains("x86_64") || a.contains("amd64") -> return Abi.X86_64
                a.contains("x86") -> return Abi.X86
                a.contains("arm") -> return Abi.ARM32
            }
        }
        return Abi.UNKNOWN
    }

    /** 根据架构返回对应的内核包信息 */
    fun kernelInfoFor(abi: Abi): KernelInfo = when (abi) {
        Abi.ARM64, Abi.X86_64 ->
            KernelInfo(abi, TBS_VERSION_64, TBS_URL_64, TBS_FILE_64)
        else ->
            KernelInfo(abi, TBS_VERSION_32, TBS_URL_32, TBS_FILE_32)
    }

    /** 应用启动时调用一次：配置 TBS、注册监听、初始化并触发按架构自动下载 */
    fun initialize(context: Context) {
        val app = context.applicationContext

        initTbsSettings()
        try {
            // 允许在非 WiFi 网络下自动下载内核（电视盒子多为有线/无线网络）
            QbSdk.setDownloadWithoutWifi(true)
        } catch (e: Exception) {
            Log.w(TAG, "setDownloadWithoutWifi failed: " + e.message)
        }

        // 监听内核下载 / 安装进度
        QbSdk.setTbsListener(object : TbsListener {
            override fun onDownloadFinish(code: Int) {
                Log.i(TAG, "TBS download finished code=" + code)
            }

            override fun onInstallFinish(code: Int) {
                Log.i(TAG, "TBS install finished code=" + code + " canLoadX5=" + QbSdk.canLoadX5(app))
            }

            override fun onDownloadProgress(progress: Int) {
                Log.d(TAG, "TBS download progress=" + progress)
            }
        })

        val abi = detectAbi()
        Log.i(TAG, "device ABI=" + abi + " (64bit=" + abi.is64Bit + ")")

        // 官方初始化：QbSdk 内部按系统类型 + 架构自动选择并下载合适内核
        QbSdk.initX5Environment(app, object : QbSdk.PreInitCallback {
            override fun onCoreInitFinished() {
                Log.i(TAG, "X5 core init finished")
            }

            override fun onViewInitFinished(success: Boolean) {
                val canLoad = QbSdk.canLoadX5(app)
                Log.i(TAG, "X5 view init finished success=" + success +
                    " canLoadX5=" + canLoad + " tbsVersion=" + QbSdk.getTbsVersion(app))
                // 官方下载失败时，走显式的按架构下载兜底
                if (!success && !canLoad) {
                    downloadKernelForAbi(app, abi)
                }
            }
        })
    }

    /** 是否已可加载 X5 内核 */
    fun isX5Ready(context: Context): Boolean = QbSdk.canLoadX5(context.applicationContext)

    /** 显式按架构下载内核并安装（官方下载失败时的兜底） */
    fun downloadKernelForAbi(context: Context, abi: Abi) {
        val app = context.applicationContext
        val info = kernelInfoFor(abi)

        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        val target = File(dir, info.fileName)

        // 已下载过则直接安装
        if (target.exists() && target.length() > 0) {
            installCore(app, info.tbsVersion, target.absolutePath)
            return
        }

        try {
            val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(info.url))
            request.setTitle("腾讯 X5 内核下载")
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, info.fileName)
            val id = dm.enqueue(request)
            Log.i(TAG, "kernel download enqueued id=" + id + " abi=" + info.abi +
                " version=" + info.tbsVersion)
            pollDownload(dm, id, app, info, target)
        } catch (e: Exception) {
            Log.e(TAG, "downloadKernelForAbi failed: " + e.message)
        }
    }

    /** 后台轮询下载进度，完成后安装内核 */
    private fun pollDownload(
        dm: DownloadManager,
        id: Long,
        app: Context,
        info: KernelInfo,
        target: File,
    ) {
        Thread {
            var downloading = true
            while (downloading) {
                try {
                    Thread.sleep(1000)
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor = dm.query(query)
                    if (cursor != null) {
                        cursor.use { c ->
                            if (c.moveToFirst()) {
                                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                when (status) {
                                    DownloadManager.STATUS_SUCCESSFUL -> {
                                        downloading = false
                                        Log.i(TAG, "kernel download success -> install version=" + info.tbsVersion)
                                        installCore(app, info.tbsVersion, target.absolutePath)
                                    }
                                    DownloadManager.STATUS_FAILED -> {
                                        downloading = false
                                        Log.e(TAG, "kernel download failed")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "pollDownload error: " + e.message)
                }
            }
        }.start()
    }

    /** 安装本地已下载的内核包 */
    private fun installCore(context: Context, version: Int, path: String) {
        try {
            QbSdk.reset(context)
            QbSdk.installLocalTbsCore(context, version, path)
            Log.i(TAG, "installLocalTbsCore version=" + version + " path=" + path)
        } catch (e: Exception) {
            Log.e(TAG, "installLocalTbsCore failed: " + e.message)
        }
    }

    /** 配置 TBS 加速项（快速 classloader / DEX 加载服务） */
    private fun initTbsSettings() {
        try {
            val map = HashMap<String, Any>(2)
            map[TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER] = true
            map[TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE] = true
            QbSdk.initTbsSettings(map)
        } catch (e: Exception) {
            Log.w(TAG, "initTbsSettings failed: " + e.message)
        }
    }
}
