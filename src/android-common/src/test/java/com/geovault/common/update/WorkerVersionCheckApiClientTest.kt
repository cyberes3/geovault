package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkerVersionCheckApiClientTest {
    @Test
    fun returnsSuccessAndParsesPayload() {
        val client = FakeWorkerVersionCheckApiClient(
            httpResult = WorkerVersionCheckApiClient.HttpResult(
                code = 200,
                message = "OK",
                body = """
                    {
                      "isLatest": false,
                      "appName": "GeoVault Live Tracker",
                      "versionLabel": "2026-03-26 9e89dc347d",
                      "latestApkUrl": "https://example/tracker.apk",
                      "releasePageUrl": "https://example/release",
                      "releaseTag": "tracker-2026-03-26-9e89dc347d",
                      "releaseCommitSha": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                      "localCommitSha": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                      "releasesRepo": "cyberes/geovault-app-release",
                      "codeRepo": "cyberes/geovault"
                    }
                """.trimIndent()
            )
        )

        val result = client.checkForUpdate(
            VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )

        assertTrue(result is WorkerCheckApiResult.Success)
        val success = result as WorkerCheckApiResult.Success
        assertEquals(false, success.payload.isLatest)
        assertEquals(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            success.payload.releaseCommitSha
        )
        assertTrue(client.capturedBody.contains("\"appName\":\"GeoVault Live Tracker\""))
    }

    @Test
    fun mapsNotFoundToNoMatch() {
        val client = FakeWorkerVersionCheckApiClient(
            httpResult = WorkerVersionCheckApiClient.HttpResult(
                code = 404,
                message = "Not Found",
                body = """{"error":"no_matching_release","detail":"No release asset matched"}"""
            )
        )
        val result = client.checkForUpdate(
            VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        assertTrue(result is WorkerCheckApiResult.NoMatch)
    }

    @Test
    fun returnsFailedOnMalformedSuccessPayload() {
        val client = FakeWorkerVersionCheckApiClient(
            httpResult = WorkerVersionCheckApiClient.HttpResult(
                code = 200,
                message = "OK",
                body = """{"isLatest":false,"appName":"GeoVault Live Tracker"}"""
            )
        )
        val result = client.checkForUpdate(
            VersionCheckRequest(
                appName = "GeoVault Live Tracker",
                localFullCommitSha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            )
        )
        assertTrue(result is WorkerCheckApiResult.Failed)
    }

    private class FakeWorkerVersionCheckApiClient(
        private val httpResult: WorkerVersionCheckApiClient.HttpResult?
    ) : WorkerVersionCheckApiClient(checkUrl = "https://example.test/check") {
        var capturedUrl: String? = null
        var capturedBody: String = ""

        override fun executePost(url: String, requestBodyJson: String): HttpResult? {
            capturedUrl = url
            capturedBody = requestBodyJson
            return httpResult
        }
    }
}
