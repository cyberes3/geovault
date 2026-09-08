package com.geovault.places.model

import java.util.UUID

@JvmInline
value class PlaceKey(val value: String) {
    val isServer: Boolean
        get() = value.startsWith(SERVER_PREFIX)
    val isLocal: Boolean
        get() = value.startsWith(LOCAL_PREFIX)

    fun shortToken(): String = value.substringAfter(":").take(6)

    companion object {
        private const val SERVER_PREFIX = "s:"
        private const val LOCAL_PREFIX = "l:"

        fun server(id: Int): PlaceKey = PlaceKey("$SERVER_PREFIX$id")

        fun local(id: String = UUID.randomUUID().toString()): PlaceKey = PlaceKey("$LOCAL_PREFIX$id")
    }
}
