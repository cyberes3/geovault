package com.geovault.uploader.domain

import com.geovault.common.files.GeoVaultFileRef
import com.geovault.uploader.presentation.UploaderMessageFormatter
import com.geovault.common.net.GeoVaultApiFailure

data class UploadItemId(val value: String)

sealed interface UploadItemState {
    data object Queued : UploadItemState
    data object Uploading : UploadItemState
    data object Succeeded : UploadItemState
    data class Failed(val failure: GeoVaultApiFailure) : UploadItemState
}

data class UploadItem(
    val id: UploadItemId,
    val file: GeoVaultFileRef,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long?,
    val state: UploadItemState = UploadItemState.Queued,
)

sealed interface UploadSessionPhase {
    data object Idle : UploadSessionPhase
    data class Running(
        val workTotal: Int,
        val workCompleted: Int,
        val currentId: UploadItemId?,
    ) : UploadSessionPhase
    data class Finished(val succeeded: Int, val failed: Int) : UploadSessionPhase
}

sealed interface UploadEvent {
    data class ItemsAppended(val items: List<UploadItem>) : UploadEvent
    data class ItemRenamed(val id: UploadItemId, val displayName: String) : UploadEvent
    data class ItemRemoved(val id: UploadItemId) : UploadEvent
    data object BatchRequested : UploadEvent
    data class ItemStarted(val id: UploadItemId) : UploadEvent
    data class ItemSucceeded(val id: UploadItemId) : UploadEvent
    data class ItemFailed(val id: UploadItemId, val failure: GeoVaultApiFailure) : UploadEvent
    data class InFlightCancelled(val id: UploadItemId) : UploadEvent
    data object CancelRequested : UploadEvent
}

data class UploadSession(
    val generation: Long = 0L,
    val items: Map<UploadItemId, UploadItem> = emptyMap(),
    val order: List<UploadItemId> = emptyList(),
    val phase: UploadSessionPhase = UploadSessionPhase.Idle,
    val statusMessage: String = "",
) {
    fun workItems(): List<UploadItemId> {
        return order.filter { id ->
            when (items[id]?.state) {
                UploadItemState.Queued -> true
                is UploadItemState.Failed -> true
                else -> false
            }
        }
    }

    fun itemList(): List<UploadItem> = order.mapNotNull(items::get)

    fun reduce(event: UploadEvent): UploadSession {
        return when (event) {
            is UploadEvent.ItemsAppended -> appendItems(event.items)
            is UploadEvent.ItemRenamed -> rename(event.id, event.displayName)
            is UploadEvent.ItemRemoved -> remove(event.id)
            UploadEvent.BatchRequested -> startBatch()
            is UploadEvent.ItemStarted -> startItem(event.id)
            is UploadEvent.ItemSucceeded -> completeItem(event.id, succeeded = true, failure = null)
            is UploadEvent.ItemFailed -> completeItem(event.id, succeeded = false, failure = event.failure)
            is UploadEvent.InFlightCancelled -> cancelInFlight(event.id)
            UploadEvent.CancelRequested -> cancelBatch()
        }
    }

    private fun appendItems(incoming: List<UploadItem>): UploadSession {
        val existingUris = order.mapNotNull { items[it]?.file?.uri }.toSet()
        val added = incoming.filter { it.file.uri !in existingUris }
        if (added.isEmpty()) return this
        val nextItems = items.toMutableMap()
        val nextOrder = order.toMutableList()
        added.forEach { item ->
            nextItems[item.id] = item
            nextOrder.add(item.id)
        }
        return copy(
            items = nextItems,
            order = nextOrder,
            phase = if (phase is UploadSessionPhase.Running) phase else UploadSessionPhase.Idle,
            statusMessage = if (phase is UploadSessionPhase.Running) statusMessage else "",
        )
    }

    private fun rename(id: UploadItemId, displayName: String): UploadSession {
        if (phase is UploadSessionPhase.Running) return this
        val item = items[id] ?: return this
        if (item.state !is UploadItemState.Queued) return this
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return this
        return copy(items = items + (id to item.copy(displayName = trimmed)))
    }

    private fun remove(id: UploadItemId): UploadSession {
        if (phase is UploadSessionPhase.Running) return this
        val item = items[id] ?: return this
        if (item.state !is UploadItemState.Queued && item.state !is UploadItemState.Failed) {
            return this
        }
        return copy(
            items = items - id,
            order = order.filterNot { it == id },
            phase = UploadSessionPhase.Idle,
            statusMessage = "",
        )
    }

    private fun startBatch(): UploadSession {
        if (phase is UploadSessionPhase.Running) return this
        val work = workItems()
        if (work.isEmpty()) {
            return copy(
                phase = UploadSessionPhase.Idle,
                statusMessage = "No valid files to upload",
            )
        }
        return copy(
            phase = UploadSessionPhase.Running(
                workTotal = work.size,
                workCompleted = 0,
                currentId = null,
            ),
            statusMessage = "",
        )
    }

    private fun startItem(id: UploadItemId): UploadSession {
        val running = phase as? UploadSessionPhase.Running ?: return this
        val item = items[id] ?: return this
        return copy(
            items = items + (id to item.copy(state = UploadItemState.Uploading)),
            phase = running.copy(currentId = id),
            statusMessage = UploaderMessageFormatter.uploadProgress(
                running.workCompleted + 1,
                running.workTotal,
            ),
        )
    }

    private fun completeItem(
        id: UploadItemId,
        succeeded: Boolean,
        failure: GeoVaultApiFailure?,
    ): UploadSession {
        val running = phase as? UploadSessionPhase.Running ?: return this
        val item = items[id] ?: return this
        val nextState = if (succeeded) {
            UploadItemState.Succeeded
        } else {
            UploadItemState.Failed(checkNotNull(failure))
        }
        val nextItems = items + (id to item.copy(state = nextState))
        val completed = running.workCompleted + 1
        if (completed < running.workTotal) {
            return copy(
                items = nextItems,
                phase = running.copy(workCompleted = completed, currentId = null),
            )
        }
        val succeededCount = nextItems.values.count { it.state is UploadItemState.Succeeded }
        val failedCount = nextItems.values.count { it.state is UploadItemState.Failed }
        return copy(
            items = nextItems,
            phase = UploadSessionPhase.Finished(succeeded = succeededCount, failed = failedCount),
            statusMessage = UploaderMessageFormatter.uploadSummary(
                succeeded = succeededCount,
                failed = failedCount,
                cancelled = false,
            ),
        )
    }

    private fun cancelInFlight(id: UploadItemId): UploadSession {
        return requeueUploading(preferId = id)
    }

    private fun cancelBatch(): UploadSession {
        return requeueUploading(preferId = null)
    }

    private fun requeueUploading(preferId: UploadItemId?): UploadSession {
        val nextItems = items.mapValues { (itemId, item) ->
            if (item.state is UploadItemState.Uploading || itemId == preferId) {
                item.copy(state = UploadItemState.Queued)
            } else {
                item
            }
        }
        return copy(
            items = nextItems,
            phase = UploadSessionPhase.Idle,
            statusMessage = UploaderMessageFormatter.uploadSummary(
                succeeded = 0,
                failed = 0,
                cancelled = true,
            ),
        )
    }
}
