package com.geovault.common.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSigningCertificatesTest {

    @Test
    fun matchWhenApkSignerIsInInstalledLineage() {
        val shared = byteArrayOf(1, 2, 3, 4)
        val older = byteArrayOf(9, 9, 9)
        assertTrue(
            ApkSigningCertificates.match(
                apkSignerCerts = listOf(shared),
                installedLineageCerts = listOf(older, shared),
            ),
        )
    }

    @Test
    fun rejectWhenNoSharedCertificate() {
        assertFalse(
            ApkSigningCertificates.match(
                apkSignerCerts = listOf(byteArrayOf(1, 2)),
                installedLineageCerts = listOf(byteArrayOf(3, 4)),
            ),
        )
    }

    @Test
    fun rejectEmptyCertificateLists() {
        assertFalse(
            ApkSigningCertificates.match(
                apkSignerCerts = emptyList(),
                installedLineageCerts = listOf(byteArrayOf(1)),
            ),
        )
        assertFalse(
            ApkSigningCertificates.match(
                apkSignerCerts = listOf(byteArrayOf(1)),
                installedLineageCerts = emptyList(),
            ),
        )
    }
}
