package com.geovault.common.logging

import org.junit.Test

class GeoVaultCaptureLogTest {

    @Test
    fun loggingCalls_doNotThrowInPlainJvmTests() {
        GeoVaultCaptureLog.v("test", "verbose")
        GeoVaultCaptureLog.d("test", "debug")
        GeoVaultCaptureLog.i("test", "info")
        GeoVaultCaptureLog.w("test", "warn")
        GeoVaultCaptureLog.e("test", "error")
        GeoVaultCaptureLog.wtf("test", "assert")
    }
}
