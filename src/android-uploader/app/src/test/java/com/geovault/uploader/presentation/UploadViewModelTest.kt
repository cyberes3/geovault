package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.geovault.common.files.GeoVaultFileIngest
import com.geovault.common.files.GeoVaultOpenableUriMetadata
import com.geovault.uploader.files.UploaderFileTypes
import com.geovault.common.intent.GeoVaultIncomingFileIntake
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.common.net.GeoVaultApiFailureMessages
import com.geovault.uploader.data.UploaderSettingsStore
import com.geovault.uploader.domain.ImportFileUploader
import com.geovault.uploader.domain.ImportUploadEngine
import com.geovault.uploader.domain.ImportUploadOutcome
import com.geovault.uploader.domain.UploadItemState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class UploadViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun ingest_appendsDedupsAndIgnoresMain() {
        val vm = viewModel()
        val first = tempKml("alpha")
        val second = tempKml("beta")
        assertTrue(vm.ingestIntent(send(first), deliveredToRunningInstance = false))
        assertEquals(1, vm.state.value.items.size)
        assertTrue(vm.state.value.isIncomingShareFlow)
        assertTrue(vm.ingestIntent(send(first), deliveredToRunningInstance = true))
        assertEquals(1, vm.state.value.items.size)
        assertTrue(vm.state.value.incomingCloseReturnsToSender)
        assertTrue(vm.ingestIntent(send(second), deliveredToRunningInstance = true))
        assertEquals(2, vm.state.value.items.size)
        assertFalse(vm.ingestIntent(Intent(Intent.ACTION_MAIN), deliveredToRunningInstance = false))
        assertEquals(2, vm.state.value.items.size)
    }

    @Test
    fun picker_setsLocalCloseAndRejectedDialog() {
        val vm = viewModel()
        val kml = tempKml("picked")
        val pdf = File.createTempFile("notes", ".pdf").apply { writeText("x") }
        assertTrue(vm.ingestPickerUris(listOf(Uri.fromFile(kml), Uri.fromFile(pdf))))
        val state = vm.state.value
        assertFalse(state.isIncomingShareFlow)
        assertEquals(1, state.items.size)
        assertTrue(state.showRejectedDialog)
        assertEquals(listOf(pdf.name), state.rejectedFileNames)
        vm.dismissRejectedFilesDialog()
        assertFalse(vm.state.value.showRejectedDialog)
    }

    @Test
    fun renameAndRemove_followIdleGuards() {
        val vm = viewModel()
        vm.ingestPickerUris(listOf(Uri.fromFile(tempKml("name"))))
        val id = vm.state.value.items.single().id
        vm.rename(id, "renamed")
        assertEquals("renamed.kml", vm.state.value.items.single().displayName)
        vm.removeItem(id)
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.showUploadAll.not())
    }

    @Test
    fun startUpload_successHidesUploadAll() {
        val uploader = ScriptedUploader(ImportUploadOutcome.Success)
        val vm = viewModel(uploader)
        vm.ingestPickerUris(listOf(Uri.fromFile(tempKml("ok"))))
        assertTrue(vm.state.value.showUploadAll)
        vm.startUpload()
        val state = vm.state.value
        assertFalse(state.showUploadAll)
        assertFalse(state.showCancel)
        assertEquals(UploadItemState.Succeeded, state.items.single().state)
    }

    @Test
    fun cancel_returnsQueuedAndShowsUploadAll() {
        val uploader = HangingUploader()
        val vm = viewModel(uploader)
        vm.ingestPickerUris(listOf(Uri.fromFile(tempKml("hold"))))
        vm.startUpload()
        assertTrue(vm.state.value.showCancel)
        vm.cancelUpload()
        val state = vm.state.value
        assertFalse(state.showCancel)
        assertTrue(state.showUploadAll)
        assertEquals(UploadItemState.Queued, state.items.single().state)
    }

    @Test
    fun newIngest_duringUpload_discardsPriorGeneration() {
        val uploader = HangingUploader()
        val vm = viewModel(uploader)
        vm.ingestPickerUris(listOf(Uri.fromFile(tempKml("first"))))
        vm.startUpload()
        val firstId = vm.state.value.items.single().id
        vm.ingestPickerUris(listOf(Uri.fromFile(tempKml("second"))))
        val state = vm.state.value
        assertEquals(2, state.items.size)
        assertTrue(state.showUploadAll)
        assertFalse(state.showCancel)
        assertTrue(state.items.all { it.state is UploadItemState.Queued })
        assertTrue(state.items.any { it.id == firstId })
    }

    @Test
    fun toUiState_derivesFlagsAndFormatsFailure() {
        val failed = GeoVaultApiFailure(httpCode = 401, serverMessage = "", operation = "importUpload")
        val ui = UploadViewModel.toUiState(
            session = com.geovault.uploader.domain.UploadSession(),
            rejectedFileNames = emptyList(),
            showRejectedDialog = false,
            incomingCloseReturnsToSender = false,
            isIncomingShareFlow = false,
        )
        assertEquals("0 Files", ui.fileCountLabel)
        assertFalse(ui.showUploadAll)
        val message = GeoVaultApiFailureMessages.format(failed)
        assertTrue(message.contains("Session expired. Sign in again."))
        assertFalse(message.contains("API key", ignoreCase = true))
    }

    private fun viewModel(uploader: ImportFileUploader = ScriptedUploader()): UploadViewModel {
        val app = RuntimeEnvironment.getApplication()
        val settings = UploaderSettingsStore(app, fileName = "vm_${System.nanoTime()}.settings")
        settings.preloadOnLaunch()
        return UploadViewModel(
            application = app,
            incomingIntake = GeoVaultIncomingFileIntake(
                GeoVaultFileIngest(
                    context = app,
                    catalog = UploaderFileTypes.catalog,
                    stageLongLivedGrants = false,
                ),
            ),
            metadata = GeoVaultOpenableUriMetadata(app.contentResolver),
            settingsStore = settings,
            engine = ImportUploadEngine(uploader),
            uploader = uploader,
        )
    }

    private fun send(file: File): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
        }
    }

    private fun tempKml(prefix: String): File {
        return File.createTempFile(prefix.padEnd(3, 'x'), ".kml").apply { writeText("<kml/>") }
    }

    private class ScriptedUploader(
        private vararg val outcomes: ImportUploadOutcome,
    ) : ImportFileUploader {
        private var index = 0

        override suspend fun upload(uri: Uri, finalFilename: String, generation: Long): ImportUploadOutcome {
            return if (index < outcomes.size) outcomes[index++] else ImportUploadOutcome.Success
        }

        override fun cancelActiveUpload(generation: Long) = Unit
    }

    private class HangingUploader : ImportFileUploader {
        private var gate = CompletableDeferred<ImportUploadOutcome>()

        override suspend fun upload(uri: Uri, finalFilename: String, generation: Long): ImportUploadOutcome {
            gate = CompletableDeferred()
            return gate.await()
        }

        override fun cancelActiveUpload(generation: Long) {
            gate.complete(ImportUploadOutcome.Cancelled)
        }
    }
}
