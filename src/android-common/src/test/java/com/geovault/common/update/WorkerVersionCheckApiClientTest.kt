package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerVersionCheckApiClientTest {

    private val uploaderAppName = GeoVaultAndroidReleaseIdentity.Uploader.WORKER_APP_NAME

    @Test
    fun `checkForUpdate maps successful payload`() {
        val client = FakeClient(
            response = WorkerVersionCheckApiClient.HttpResult(
                code = 200,
                message = "OK",
                body = """
                    {
                      "isLatest": false,
                      "appName": "$uploaderAppName",
                      "versionLabel": "v3",
                      "releasePageUrl": "https://example.test/release",
                      "releaseTag": "v3",
                      "releaseCommitSha": "ABC",
                      "localCommitSha": "DEF"
                    }
                """.trimIndent()
            )
        )

        val result = client.checkForUpdate(
            VersionCheckRequest(appName = uploaderAppName, localFullCommitSha = "f".repeat(40))
        )

        assertTrue(result is WorkerCheckApiResult.Success)
        val payload = (result as WorkerCheckApiResult.Success).payload
        assertEquals(uploaderAppName, payload.appName)
        assertEquals("abc", payload.releaseCommitSha)
        assertEquals("def", payload.localCommitSha)
        assertTrue(client.lastBody?.contains("\"appName\":\"$uploaderAppName\"") == true)
    }

    @Test
    fun `checkForUpdate maps 404 to NoMatch with parsed detail`() {
        val client = FakeClient(
            response = WorkerVersionCheckApiClient.HttpResult(
                code = 404,
                message = "Not Found",
                body = """{"error":"release_missing","detail":"No matching release found"}"""
            )
        )

        val result = client.checkForUpdate(
            VersionCheckRequest(appName = uploaderAppName, localFullCommitSha = "f".repeat(40))
        )

        assertTrue(result is WorkerCheckApiResult.NoMatch)
        assertEquals("release_missing: No matching release found", (result as WorkerCheckApiResult.NoMatch).detail)
    }

    @Test
    fun `checkForUpdate returns Failed on invalid JSON body`() {
        val client = FakeClient(
            response = WorkerVersionCheckApiClient.HttpResult(
                code = 200,
                message = "OK",
                body = "not-json"
            )
        )

        val result = client.checkForUpdate(
            VersionCheckRequest(appName = uploaderAppName, localFullCommitSha = "f".repeat(40))
        )

        assertTrue(result is WorkerCheckApiResult.Failed)
    }

    private class FakeClient(
        private val response: WorkerVersionCheckApiClient.HttpResult
    ) : WorkerVersionCheckApiClient(checkUrl = "https://example.test/check") {
        var lastBody: String? = null

        override fun executePost(url: String, requestBodyJson: String): HttpResult {
            lastBody = requestBodyJson
            return response
        }
    }
}
