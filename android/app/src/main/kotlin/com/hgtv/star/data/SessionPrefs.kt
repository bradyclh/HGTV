// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.data

import android.annotation.TargetApi
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 登录态文件（`native-session-v1`）的加密包装：接口只保留 [Library] 用到的那几个方法，
 * 调用方写法与 [SharedPreferences] 相同。
 *
 * - 字符串值用 Android Keystore 里不可导出的 AES-256-GCM 密钥加密，落盘为 `v1:<base64(iv+密文)>`；
 *   拿到 xml 文件（root、备份、误拷贝）也拿不到 Cookie / x-tt-token。
 * - 读到旧版明文值照常返回，下次写入时变成密文，升级不掉登录。
 * - 密钥丢失或解密失败（例如系统清掉了 Keystore）当作「没有登录态」，让用户重新登录，
 *   不回落成明文，也不抛到界面上。
 * - API 23 以下没有 Keystore AES，只能原样存（这些系统本来就不在主要支持范围）。
 * - 登录态**只**存在应用私有目录；不要再写 `/sdcard` 等共享存储（任何有存储权限的应用都能读写）。
 */
class SessionPrefs(private val prefs: SharedPreferences) {
    private companion object {
        const val PREFIX = "v1:"
        const val ALIAS = "hgtv-session-v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    fun getString(key: String, default: String?): String? {
        val raw = prefs.getString(key, null) ?: return default
        if (!raw.startsWith(PREFIX)) return raw
        return decrypt(raw.substring(PREFIX.length)) ?: default
    }

    fun getLong(key: String, default: Long) = prefs.getLong(key, default)

    fun edit() = Editor(prefs.edit())

    inner class Editor(private val editor: SharedPreferences.Editor) {
        fun putString(key: String, value: String?): Editor {
            if (value == null) editor.remove(key) else editor.putString(key, encrypt(value))
            return this
        }
        fun putLong(key: String, value: Long): Editor { editor.putLong(key, value); return this }
        fun remove(key: String): Editor { editor.remove(key); return this }
        fun apply() = editor.apply()
    }

    @TargetApi(23)
    private fun encrypt(value: String): String {
        if (Build.VERSION.SDK_INT < 23 || value.isEmpty()) return value
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            PREFIX + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }.getOrElse {
            // 加密不可用时不写明文：宁可这一次不保存登录态。
            Log.w("HGTV", "Session encryption unavailable; value not persisted")
            ""
        }
    }

    @TargetApi(23)
    private fun decrypt(encoded: String): String? {
        if (Build.VERSION.SDK_INT < 23) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    @TargetApi(23)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
