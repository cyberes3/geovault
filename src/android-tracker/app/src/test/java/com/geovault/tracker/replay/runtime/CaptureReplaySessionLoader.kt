package com.geovault.tracker.replay.runtime

import kotlinx.serialization.json.Json

object CaptureReplaySessionLoader {
    private const val SupportedSchemaVersion = 1

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun load(resourceName: String): CaptureReplaySessionDto {
        val stream = checkNotNull(
            CaptureReplaySessionLoader::class.java.classLoader?.getResourceAsStream("replay/$resourceName.json"),
        ) { "missing replay resource replay/$resourceName.json" }
        val session = stream.bufferedReader().use { reader ->
            json.decodeFromString<CaptureReplaySessionDto>(reader.readText())
        }
        require(session.schemaVersion == SupportedSchemaVersion) {
            "unsupported replay schemaVersion=${session.schemaVersion}"
        }
        require(session.rawFixes.isNotEmpty()) { "replay must contain rawFixes" }
        session.rawFixes.forEachIndexed { index, fix ->
            require(fix.index == index) { "rawFixes[$index] index mismatch: ${fix.index}" }
        }
        return session
    }
}
