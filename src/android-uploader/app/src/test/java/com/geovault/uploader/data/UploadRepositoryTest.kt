package com.geovault.uploader.data

import android.net.Uri
import com.geovault.common.auth.AuthSessionService
import com.geovault.common.auth.ServerConfigService
import com.geovault.uploader.domain.ImportUploadOutcome
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class UploadRepositoryTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun upload_sendsMultipartFilename() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"job_id":"ignored"}"""))
        val file = tempKml()
        val outcome = repository(signedIn = true).upload(Uri.fromFile(file), "site.kml", generation = 1L)
        assertEquals(ImportUploadOutcome.Success, outcome)
        val recorded = server.takeRequest()
        assertEquals("/api/item/import/upload", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"file\""))
        assertTrue(body.contains("filename=\"site.kml\""))
    }

    @Test
    fun upload_unauthorizedBecomesFailedApiFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        val outcome = repository(signedIn = true).upload(
            Uri.fromFile(tempKml()),
            "site.kml",
            generation = 2L,
        )
        val failed = outcome as ImportUploadOutcome.Failed
        assertEquals(401, failed.failure.httpCode)
        assertEquals("expired", failed.failure.serverMessage)
        assertEquals("importUpload", failed.failure.operation)
    }

    @Test
    fun upload_notSignedInDoesNotHitServer() = runBlocking {
        val outcome = repository(signedIn = false).upload(
            Uri.fromFile(tempKml()),
            "site.kml",
            generation = 3L,
        )
        val failed = outcome as ImportUploadOutcome.Failed
        assertEquals("Not signed in", failed.failure.serverMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancelActiveUpload_returnsCancelled() = runBlocking {
        server.enqueue(
            MockResponse()
                .setSocketPolicy(SocketPolicy.NO_RESPONSE)
                .setResponseCode(200)
                .setBody("ok"),
        )
        val repo = repository(signedIn = true)
        val deferred = async(Dispatchers.IO) {
            repo.upload(Uri.fromFile(tempKml()), "site.kml", generation = 9L)
        }
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        checkNotNull(recorded) { "upload request never reached the server" }
        repo.cancelActiveUpload(9L)
        assertEquals(ImportUploadOutcome.Cancelled, deferred.await())
    }

    private fun repository(signedIn: Boolean): UploadRepository {
        return UploadRepository(
            contentResolver = RuntimeEnvironment.getApplication().contentResolver,
            serverConfigService = FakeServerConfig(server.url("/").toString().trimEnd('/')),
            authSessionService = FakeAuthSession(signedIn),
            httpClient = OkHttpClient(),
        )
    }

    private fun tempKml(): File {
        return File.createTempFile("site", ".kml").apply { writeText("<kml/>") }
    }

    private class FakeServerConfig(private val url: String) : ServerConfigService {
        override fun getServerUrl(): String = url
        override fun setServerUrl(url: String) = Unit
        override fun normalizeServerUrl(url: String): String = url
        override fun getNormalizedServerUrl(): String = url
        override fun resolveServerUrlToCanonical(url: String) = Result.success(url)
    }

    private class FakeAuthSession(private val signedIn: Boolean) : AuthSessionService {
        override fun isLoggedIn(): Boolean = signedIn
        override fun getCachedUserEmail(): String? = if (signedIn) "user@example.test" else null
        override fun fetchUserStatus(callback: (String?) -> Unit) = callback(getCachedUserEmail())
        override fun revokeCurrentSession() = Unit
        override fun handleAuthFailure() = Unit
    }
}
