package com.geovault.tracker.runtime

import com.geovault.common.concurrent.CommandCorrelation

sealed class TrackerRuntimeCommands {
    data class Start(
        val trigger: RuntimeTrigger,
        val reason: String,
        val token: CommandCorrelation.Token? = null,
    ) : TrackerRuntimeCommands()

    data class Stop(
        val reason: String,
        val token: CommandCorrelation.Token? = null,
    ) : TrackerRuntimeCommands()

    data class Recover(
        val reason: String,
    ) : TrackerRuntimeCommands()

    data class ReshowForeground(
        val reason: String,
    ) : TrackerRuntimeCommands()

    data class Heartbeat(
        val timestampMs: Long = System.currentTimeMillis(),
    ) : TrackerRuntimeCommands()

    data class SwitchRecordingTarget(
        val trackerId: String,
        val token: CommandCorrelation.Token,
    ) : TrackerRuntimeCommands()

    data class SendManualPoint(
        val reason: String = "manual_point",
    ) : TrackerRuntimeCommands()

    data class ServiceStarted(
        val trigger: RuntimeTrigger,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : TrackerRuntimeCommands()

    data class ServiceStopped(
        val reason: String,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : TrackerRuntimeCommands()

    data class StartupFailed(
        val reason: String,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : TrackerRuntimeCommands()

    data class UnexpectedDestroy(
        val wasTracking: Boolean,
        val timestampMs: Long = System.currentTimeMillis(),
    ) : TrackerRuntimeCommands()

    data class TaskRemoved(
        val timestampMs: Long = System.currentTimeMillis(),
    ) : TrackerRuntimeCommands()

    companion object {
        const val CORRELATION_RECORDING = "recording"
        const val ACTION_ENGINE_STOP = "com.geovault.tracker.ACTION_ENGINE_STOP"
    }
}
