package com.geovault.common.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

class GeoVaultRetrofitCallTest {
    private interface SampleApi {
        @GET("/item")
        fun getItem(): Call<SampleBody>
    }

    data class SampleBody(val name: String)

    @Test
    fun await_returnsBodyOnSuccess() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"name":"camp"}""").setResponseCode(200))
            val api = api(server)
            val body = runBlocking { api.getItem().await("getItem") }
            assertEquals("camp", body.name)
        }
    }

    @Test
    fun await_throwsApiFailureOnHttpError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"error":"nope"}""").setResponseCode(400))
            val api = api(server)
            val error = runCatching { runBlocking { api.getItem().await("getItem") } }.exceptionOrNull()
            assertTrue(error is GeoVaultApiFailure)
            assertEquals(400, (error as GeoVaultApiFailure).httpCode)
        }
    }

    @Test
    fun bodyOrThrow_rejectsEmptySuccessBody() {
        val response = retrofit2.Response.success<SampleBody>(null)
        val error = runCatching { response.bodyOrThrow("empty") }.exceptionOrNull()
        assertTrue(error is GeoVaultApiFailure)
        assertEquals("Empty response", (error as GeoVaultApiFailure).serverMessage)
    }

    private fun api(server: MockWebServer): SampleApi {
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SampleApi::class.java)
    }
}
