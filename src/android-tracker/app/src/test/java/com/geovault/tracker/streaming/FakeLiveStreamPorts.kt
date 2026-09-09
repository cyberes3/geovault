package com.geovault.tracker.streaming

import android.content.Context

internal class FakeLiveStreamPersistPort(
    initial: Pair<Set<String>, String?> = emptySet<String>() to null,
) : LiveStreamPersistPort {
    var stored: Pair<Set<String>, String?> = initial

    override fun commit(trackerIds: Set<String>, trackerName: String?) {
        stored = trackerIds to trackerName
    }

    override fun read(): Pair<Set<String>, String?> = stored

    override fun clear() {
        stored = emptySet<String>() to null
    }
}

internal class FakeLiveStreamHostPort(
    private val persist: FakeLiveStreamPersistPort,
    private val startResult: (Set<String>) -> LiveStreamApplyResult = { ids -> LiveStreamApplyResult.Started(ids) },
    private val stopResult: () -> LiveStreamStopResult = { LiveStreamStopResult.Stopped },
) : LiveStreamHostPort {
    val startedIds = mutableListOf<Set<String>>()
    var startAttempts = 0
    var stopCount = 0

    override fun apply(context: Context): LiveStreamApplyResult {
        startAttempts++
        val ids = persist.read().first
        val result = startResult(ids)
        if (result is LiveStreamApplyResult.Started) startedIds += ids
        return result
    }

    override fun stop(context: Context): LiveStreamStopResult {
        stopCount++
        return stopResult()
    }

    override fun reshow(context: Context) = Unit

    override fun cancelRetry() = Unit
}
