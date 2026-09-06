package com.geovault.common.update

import android.content.pm.SigningInfo

object ApkSigningCertificates {
    fun match(apkSignerCerts: List<ByteArray>, installedLineageCerts: List<ByteArray>): Boolean {
        if (apkSignerCerts.isEmpty() || installedLineageCerts.isEmpty()) return false
        val installed = installedLineageCerts.map { it.toList() }.toSet()
        return apkSignerCerts.any { it.toList() in installed }
    }

    fun currentSignerCerts(signingInfo: SigningInfo): List<ByteArray> {
        return signingInfo.apkContentsSigners.map { it.toByteArray() }
    }

    fun lineageCerts(signingInfo: SigningInfo): List<ByteArray> {
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.map { it.toByteArray() }
    }
}
