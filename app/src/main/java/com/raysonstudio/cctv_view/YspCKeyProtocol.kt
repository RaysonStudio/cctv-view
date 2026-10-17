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

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * bkliveinfo + cKey 备用协议：当 JCE 返回死域名（或频道被列入强制名单）时使用。
 * 用 TEA 加密的 cKey 参数请求 bkliveinfo.ysp.cctv.cn，换取明文 HLS 播放地址。
 *
 * 移植自 funwukong/ysp-live（原 akiralereal/iptv extractors/yangshipin/ckey.js）。
 */
object YspCKeyProtocol {

    private const val TAG = "CCTV_BK"

    private const val BK_URL = "https://bkliveinfo.ysp.cctv.cn/"
    private const val PLATFORM = 4330403
    private const val APP_VER = "V8.22.1035.3031"

    private val TEA_KEY = hex("59b2f7cf725ef43c34fdd7c123411ed3")
    private val GTEA_KEY = hex("110DBEC10C23E7D2E56A1CAD6914EF1B")
    private val XOR_MASK = byteArrayOf(
        0x84.toByte(), 0x2e.toByte(), 0xed.toByte(), 0x08.toByte(),
        0xf0.toByte(), 0x66.toByte(), 0xe6.toByte(), 0xea.toByte(),
        0x48.toByte(), 0xb4.toByte(), 0xca.toByte(), 0xa9.toByte(),
        0x91.toByte(), 0xed.toByte(), 0x6f.toByte(), 0xf3.toByte()
    )
    private val GXOR_MASK = byteArrayOf(
        0xb3.toByte(), 0xc9.toByte(), 0x53.toByte(), 0xa0.toByte(),
        0x69.toByte(), 0x13.toByte(), 0xad.toByte(), 0x4d.toByte()
    )

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private data class CKeyResult(val cKey: String, val guid: String, val ts: Int, val flowId: String)

    private fun tail(v: String): String = if (v.length >= 5) v.takeLast(5) else ""

    /** cKey 内部 guard 字段 */
    private fun ckGuard(ts: Int, guid: String): String {
        val body = ByteArrayOutputStream()
        body.write(YspCrypto.be32Bytes(ts.toUInt()))
        body.write(YspCrypto.lp(tail(guid)))
        body.write(YspCrypto.lp(tail("null")))
        body.write(YspCrypto.lp(tail("null")))
        body.write(YspCrypto.lp("-1"))
        val plain = YspCrypto.lp(body.toByteArray())
        var enc = YspCrypto.teaPkt(plain, GTEA_KEY) +
            YspCrypto.be32Bytes(YspCrypto.cksum(plain).toUInt())
        enc = YspCrypto.xor(enc, GXOR_MASK)
        return YspCrypto.hexUpper(enc)
    }

    /** 生成 cKey（--01 开头 + base64url） */
    private fun makeCKey(channelId: String): CKeyResult {
        val ts = (System.currentTimeMillis() / 1000).toInt()
        val guid = buildString {
            repeat(16) { append("0123456789abcdef".random()) }
        }
        val guard = ckGuard(ts, guid)
        val uid = buildString {
            repeat(4) { append("0123456789abcdef".random()) }
        }.uppercase()

        val body = ByteArrayOutputStream()
        body.write(hex("0000004200000004000004d2"))
        body.write(YspCrypto.be32Bytes(PLATFORM.toUInt()))
        body.write(YspCrypto.be32Bytes(0u))
        body.write(YspCrypto.be32Bytes(ts.toUInt()))
        body.write(YspCrypto.lp("dcgh"))
        body.write(YspCrypto.lp("_zj1A5Gh6QYcxWjIUGos2w=="))
        body.write(YspCrypto.lp(APP_VER))
        body.write(YspCrypto.lp(channelId))
        body.write(YspCrypto.lp(guid))
        body.write(YspCrypto.be32Bytes(1u))
        body.write(YspCrypto.be32Bytes(1u))
        body.write(YspCrypto.lp(uid))
        body.write(YspCrypto.lp("nil"))
        body.write(YspCrypto.lp("57eab0c4-2c58-44c6-8ae9-dd2757525dc5"))
        body.write(YspCrypto.lp("nil"))
        body.write(YspCrypto.lp("v0.1.000"))
        body.write(YspCrypto.lp("com.cctv.yangshipin.app.iphone"))
        body.write(YspCrypto.lp(PLATFORM.toString()))
        body.write(YspCrypto.lp("ex_json_bus"))
        body.write(YspCrypto.lp("ex_json_vs"))
        body.write(YspCrypto.lp(guard))

        val bodyBytes = body.toByteArray()
        val pkt = ByteArray(2 + bodyBytes.size)
        pkt[0] = ((bodyBytes.size ushr 8) and 0xff).toByte()
        pkt[1] = (bodyBytes.size and 0xff).toByte()
        System.arraycopy(bodyBytes, 0, pkt, 2, bodyBytes.size)
        // pkt[18:22] = cksum(pkt) 大端
        val cs = YspCrypto.cksum(pkt)
        pkt[18] = ((cs ushr 24) and 0xff).toByte()
        pkt[19] = ((cs ushr 16) and 0xff).toByte()
        pkt[20] = ((cs ushr 8) and 0xff).toByte()
        pkt[21] = (cs and 0xff).toByte()

        var enc = YspCrypto.teaPkt(pkt, TEA_KEY) + YspCrypto.be32Bytes(YspCrypto.cksum(pkt).toUInt())
        enc = YspCrypto.xor(enc, XOR_MASK)
        val b64 = YspCrypto.b64Url(enc)
        val flowId = buildString {
            repeat(32) { append("0123456789ABCDEF".random()) }
        } + "_" + PLATFORM

        return CKeyResult("--01$b64", guid, ts, flowId)
    }

    /**
     * 请求 bkliveinfo，返回可用的 HLS 播放地址列表（bklive- 域名优先）。
     *
     * @param channelId streamId/cnlid（如 2024078201）
     * @param livePid   直播 pid（如 600001859）
     * @param defn      清晰度（fhd/shd）
     */
    fun fetchBkUrls(channelId: String, livePid: String, defn: String = "fhd"): List<String> {
        val t = makeCKey(channelId)
        val params = mapOf(
            "atime" to "120",
            "livepid" to livePid,
            "cnlid" to channelId,
            "appVer" to APP_VER,
            "app_version" to "300090",
            "caplv" to "1",
            "cmd" to "2",
            "defn" to defn,
            "device" to "iPhone",
            "encryptVer" to "4.2",
            "getpreviewinfo" to "0",
            "hevclv" to "0",
            "lang" to "zh-Hans_CN",
            "livequeue" to "0",
            "logintype" to "1",
            "nettype" to "1",
            "newnettype" to "1",
            "newplatform" to PLATFORM.toString(),
            "platform" to PLATFORM.toString(),
            "sdtfrom" to "v3021",
            "spacode" to "23",
            "spaudio" to "1",
            "spdemuxer" to "6",
            "spdrm" to "2",
            "spdynamicrange" to "1",
            "spflv" to "1",
            "spflvaudio" to "1",
            "sphdrfps" to "60",
            "sphttps" to "1",
            "spvcode" to android.util.Base64.encodeToString(
                "H(30:1080,60:1080|30:1080,60:1080)".toByteArray(), android.util.Base64.NO_WRAP
            ),
            "spvideo" to "4",
            "stream" to "1",
            "system" to "1",
            "sysver" to "ios18.2.1",
            "uhd_flag" to "0",
            "cKey" to t.cKey,
            "guid" to t.guid,
            "fntick" to t.ts.toString(),
            "flowid" to t.flowId,
            "playbacktime" to "0"
        )
        val q = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val url = BK_URL + "?" + q

        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "qqlive")
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("bkliveinfo HTTP $code")
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val iret = json.optInt("iretcode", -1)
            if (iret != 0) {
                throw IllegalStateException("bkliveinfo iretcode=$iret errinfo=${json.optString("errinfo")}")
            }

            val urls = ArrayList<String>()
            val pu = json.optString("playurl", "")
            if (pu.isNotEmpty()) urls.add(pu)
            val bu = json.opt("backurl_list") ?: json.opt("backurlList") ?: json.opt("backurl")
            when (bu) {
                is org.json.JSONArray -> for (i in 0 until bu.length()) {
                    val it = bu.opt(i)
                    when (it) {
                        is String -> urls.add(it)
                        is JSONObject -> {
                            val u = it.optString("url", "")
                            if (u.isEmpty()) urls.add(it.optString("playurl", ""))
                            else urls.add(u)
                        }
                    }
                }
                is String -> urls.addAll(bu.split(';', ',').filter { it.isNotBlank() })
            }

            val usable = urls.distinct().filter { it.isNotEmpty() && ".cctv." in it }
            if (usable.isEmpty()) throw IllegalStateException("no usable playurl")
            // bklive- 备用 CDN 更稳定，优先
            return usable.sortedBy { if ("bklive-" in it) 0 else 1 }
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "bkliveinfo json error: ${e.message}")
            throw IllegalStateException("bkliveinfo bad json")
        } finally {
            connection?.disconnect()
        }
    }
}
