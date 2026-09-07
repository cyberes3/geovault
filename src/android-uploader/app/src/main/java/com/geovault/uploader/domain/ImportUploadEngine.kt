package com.geovault.uploader.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ImportUploadEngine(
    private val uploader: ImportFileUploader,
) {
    fun run(session: UploadSession, suffixEnabled: Boolean): Flow<UploadSession> = flow {
        var current = session.reduce(UploadEvent.BatchRequested)
        emit(current)
        val work = current.workItems()
        if (work.isEmpty()) {
            return@flow
        }
        for (id in work) {
            currentCoroutineContext().ensureActive()
            current = current.reduce(UploadEvent.ItemStarted(id))
            emit(current)
            val item = current.items.getValue(id)
            val finalName = FilenamePolicy.withOptionalSuffix(item.displayName, suffixEnabled)
            val outcome = uploader.upload(item.file.uri, finalName, current.generation)
            current = when (outcome) {
                ImportUploadOutcome.Success -> current.reduce(UploadEvent.ItemSucceeded(id))
                is ImportUploadOutcome.Failed -> current.reduce(
                    UploadEvent.ItemFailed(id, outcome.failure),
                )
                ImportUploadOutcome.Cancelled -> {
                    emit(current.reduce(UploadEvent.InFlightCancelled(id)))
                    return@flow
                }
            }
            emit(current)
        }
    }
}
