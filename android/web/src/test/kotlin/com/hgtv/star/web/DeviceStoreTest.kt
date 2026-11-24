// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.web

import com.hgtv.star.core.ContentRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeviceStoreTest {
    /** 換身分後落盤：重開伺服器（新的 DeviceStore）讀回的是新裝置與新檔案。 */
    @Test fun rotatedIdentitySurvivesRestart() {
        val file=File(Files.createTempDirectory("hgtv-device").toFile(),"device.json")
        val store=DeviceStore(file)
        store.profile()
        store.rotated("7000000000000001","8000000000000001",mapOf("cdid" to "new-cdid","openudid" to "00000000000000ab"))
        val reopened=DeviceStore(file)
        assertEquals(mapOf("device_id" to "7000000000000001","iid" to "8000000000000001"),reopened.identity())
        assertEquals("new-cdid",reopened.profile()["cdid"])
    }

    /** apply 會把落盤回呼掛到倉庫上，倉庫換身分時才寫得回來。 */
    @Test fun applyInstallsTheRotationCallback() {
        val file=File(Files.createTempDirectory("hgtv-device").toFile(),"device.json")
        val store=DeviceStore(file)
        store.rotated("7000000000000001","8000000000000001",mapOf("cdid" to "c","openudid" to "o"))
        val repository=ContentRepository()
        store.apply(repository)
        assertNotNull(repository.onIdentityRotated)
    }
}
