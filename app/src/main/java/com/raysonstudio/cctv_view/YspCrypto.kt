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

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * 央视频 JCE / cKey 协议所需的加密原语：
 * - TEA 分组加密（16 轮，大端，delta=0x9E3779B9）
 * - TEA 数据包加密（含随机填充、前后块 CBC 风格异或链）
 * - cksum 校验和（0x83 滚动哈希，掩码 0x7FFFFFFF）
 *
 * 移植自 akiralereal/iptv extractors/yangshipin/ckey.js（纯标准库可复现）。
 */
object YspCrypto {

    /** 大端读 32 位无符号 */
    fun be32(b: ByteArray, off: Int = 0): UInt =
        ((b[off].toInt() and 0xff).toUInt() shl 24) or
        ((b[off + 1].toInt() and 0xff).toUInt() shl 16) or
        ((b[off + 2].toInt() and 0xff).toUInt() shl 8) or
        (b[off + 3].toInt() and 0xff).toUInt()

    /** 大端写 32 位无符号 */
    fun putBe32(v: UInt, out: ByteArray, off: Int) {
        out[off] = (v shr 24).toByte()
        out[off + 1] = (v shr 16).toByte()
        out[off + 2] = (v shr 8).toByte()
        out[off + 3] = v.toByte()
    }

    fun be32Bytes(v: UInt): ByteArray = ByteArray(4).also { putBe32(v, it, 0) }

    fun be16Bytes(v: Int): ByteArray = byteArrayOf(
        ((v shr 8) and 0xff).toByte(), (v and 0xff).toByte()
    )

    fun be64Bytes(v: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = ((v ushr ((7 - i) * 8)) and 0xff).toByte()
        return out
    }

    /** 0x83 滚动校验和（Python: v = (0x83 * v + b) & 0x7fffffff） */
    fun cksum(buf: ByteArray): Int {
        var v = 0L
        for (b in buf) v = (0x83L * v + (b.toInt() and 0xff)) and 0x7fffffffL
        return v.toInt()
    }

    /** TEA 单块加密（8 字节明文 + 16 字节密钥 -> 8 字节密文） */
    fun teaBlock(block: ByteArray, key: ByteArray): ByteArray {
        require(block.size == 8 && key.size == 16)
        var y = be32(block, 0)
        var z = be32(block, 4)
        val k0 = be32(key, 0)
        val k1 = be32(key, 4)
        val k2 = be32(key, 8)
        val k3 = be32(key, 12)
        var s = 0u
        repeat(16) {
            s += 0x9E3779B9u
            y += (((z shl 4) + k0) xor (z + s) xor ((z shr 5) + k1))
            z += (((y shl 4) + k2) xor (y + s) xor ((y shr 5) + k3))
        }
        val out = ByteArray(8)
        putBe32(y, out, 0)
        putBe32(z, out, 4)
        return out
    }

    /**
     * TEA 数据包加密（与 ckey.js 的 teaEncrypt 一致）：
     * 明文 = [填充头(1)][随机填充 pad][随机(2)][data][7 个 0]
     * 每 8 字节块：mixed = plain ^ prevCipher；cipher = teaBlock(mixed) ^ prevPlain
     */
    fun teaPkt(data: ByteArray, key: ByteArray): ByteArray {
        val rnd = Random.Default
        val pad = (8 - ((data.size + 10) % 8)) % 8
        val plain = ByteArray(1 + pad + 2 + data.size + 7)
        plain[0] = ((rnd.nextInt(256) and 0xf8) or pad).toByte()
        rnd.nextBytes(plain, 1, 1 + pad)
        rnd.nextBytes(plain, 1 + pad, 1 + pad + 2)
        System.arraycopy(data, 0, plain, 1 + pad + 2, data.size)
        // 尾部 7 字节保持 0

        val out = ByteArrayOutputStream(plain.size)
        var pp = ByteArray(8)
        var pc = ByteArray(8)
        var off = 0
        while (off < plain.size) {
            val mixed = ByteArray(8) { i -> (plain[off + i].toInt() xor pc[i].toInt()).toByte() }
            val enc = teaBlock(mixed, key)
            val cipher = ByteArray(8) { i -> (enc[i].toInt() xor pp[i].toInt()).toByte() }
            out.write(cipher)
            pp = mixed
            pc = cipher
            off += 8
        }
        return out.toByteArray()
    }

    /** gzip 压缩（与 Python gzip.compress 兼容，mtime=0） */
    fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** gzip 解压 */
    fun gunzip(data: ByteArray): ByteArray =
        java.util.zip.GZIPInputStream(data.inputStream()).use { it.readBytes() }

    /** base64url（+ -> _，/ -> -，去 padding） */
    fun b64Url(data: ByteArray): String =
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            .replace('+', '_')
            .replace('/', '-')
            .trimEnd('=')

    /** 字节异或 */
    fun xor(data: ByteArray, mask: ByteArray): ByteArray =
        ByteArray(data.size) { i -> (data[i].toInt() xor mask[i % mask.size].toInt()).toByte() }

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }

    fun hexUpper(data: ByteArray): String =
        data.joinToString("") { "%02X".format(it) }

    /** 字符串 -> 大端 uint16 长度前缀 */
    fun lp(s: String): ByteArray {
        val d = s.toByteArray(Charsets.UTF_8)
        return be16Bytes(d.size) + d
    }

    fun lp(b: ByteArray): ByteArray = be16Bytes(b.size) + b
}
