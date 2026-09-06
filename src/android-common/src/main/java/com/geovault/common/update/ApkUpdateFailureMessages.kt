package com.geovault.common.update

import android.content.Context
import com.geovault.common.R
import java.io.IOException

object ApkUpdateFailureMessages {
    fun classify(context: Context, error: Throwable): String {
        val res = context.resources
        val msg = error.message.orEmpty()
        val httpMatch = Regex("HTTP (\\d+)").find(msg)?.groupValues?.getOrNull(1)
        if (httpMatch != null) {
            return res.getString(R.string.gv_update_error_http, httpMatch.toInt())
        }
        val disk = msg.contains("ENOSPC", ignoreCase = true) ||
            msg.contains("No space", ignoreCase = true)
        return when {
            disk -> res.getString(R.string.gv_update_error_disk)
            error is IllegalArgumentException &&
                (msg == "insecure_download_url" || msg == "invalid_download_url") ->
                res.getString(R.string.gv_update_error_insecure_url)
            error is IllegalStateException && msg == "apk_size_mismatch" ->
                res.getString(R.string.gv_update_error_apk_size_mismatch)
            error is IllegalStateException && msg == "no_install_handler" ->
                res.getString(R.string.gv_update_error_no_install_handler)
            error is IllegalStateException && msg == "version_downgrade" ->
                res.getString(R.string.gv_update_error_version_downgrade)
            error is IllegalStateException && msg == "apk_package_mismatch" ->
                res.getString(R.string.gv_update_error_apk_package_mismatch)
            error is IllegalStateException && msg == "apk_signing_mismatch" ->
                res.getString(R.string.gv_update_error_apk_signing_mismatch)
            error is IllegalStateException && msg == "apk_parse_failed" ->
                res.getString(R.string.gv_update_error_apk_parse)
            error is IOException -> res.getString(R.string.gv_update_error_network)
            else -> res.getString(R.string.gv_update_error_generic)
        }
    }
}
