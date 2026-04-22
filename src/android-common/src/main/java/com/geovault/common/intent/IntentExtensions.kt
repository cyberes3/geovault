package com.geovault.common.intent

import android.content.Intent
import androidx.core.content.IntentCompat
import java.io.Serializable

/**
 * API-safe, reified replacement for the deprecated [Intent.getSerializableExtra] overload.
 *
 * Prefer this over hand-rolling per-app copies of the same inline extension.
 */
inline fun <reified T : Serializable> Intent.getSerializableExtraCompat(key: String): T? =
    IntentCompat.getSerializableExtra(this, key, T::class.java)
