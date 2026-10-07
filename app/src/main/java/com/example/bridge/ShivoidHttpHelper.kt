package com.example.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ShivoidHttpHelper {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun executeGet(url: String, headersJson: String?): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        try {
            val reqBuilder = Request.Builder().url(url).get()
            parseHeaders(headersJson).forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            client.newCall(reqBuilder.build()).execute().use { response ->
                result.put("success", response.isSuccessful)
                result.put("statusCode", response.code)
                result.put("message", response.message)
                result.put("body", response.body?.string() ?: "")

                val respHeaders = JSONObject()
                response.headers.forEach { pair ->
                    respHeaders.put(pair.first, pair.second)
                }
                result.put("headers", respHeaders)
            }
        } catch (e: Exception) {
            result.put("success", false)
            result.put("statusCode", 0)
            result.put("error", e.message ?: "Network error")
            result.put("body", "")
        }
        result
    }

    suspend fun executePost(
        url: String,
        body: String?,
        contentType: String?,
        headersJson: String?
    ): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        try {
            val mediaType = (contentType ?: "application/json; charset=utf-8").toMediaTypeOrNull()
            val requestBody = (body ?: "").toRequestBody(mediaType)
            val reqBuilder = Request.Builder().url(url).post(requestBody)

            parseHeaders(headersJson).forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            client.newCall(reqBuilder.build()).execute().use { response ->
                result.put("success", response.isSuccessful)
                result.put("statusCode", response.code)
                result.put("message", response.message)
                result.put("body", response.body?.string() ?: "")

                val respHeaders = JSONObject()
                response.headers.forEach { pair ->
                    respHeaders.put(pair.first, pair.second)
                }
                result.put("headers", respHeaders)
            }
        } catch (e: Exception) {
            result.put("success", false)
            result.put("statusCode", 0)
            result.put("error", e.message ?: "Network error")
            result.put("body", "")
        }
        result
    }

    suspend fun callAutomate(endpointUrl: String, payloadJson: String?): JSONObject = withContext(Dispatchers.IO) {
        // Automate typically accepts HTTP POST with JSON or form data, or HTTP GET with query params
        // We send a POST request with the JSON payload and Automate user agent
        val result = JSONObject()
        try {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = (payloadJson ?: "{}").toRequestBody(mediaType)
            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("User-Agent", "SHIVOID-Automate-Client/1.0")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                result.put("success", response.isSuccessful)
                result.put("statusCode", response.code)
                result.put("message", response.message)
                result.put("body", response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            result.put("success", false)
            result.put("statusCode", 0)
            result.put("error", e.message ?: "Automate communication error")
            result.put("body", "")
        }
        result
    }

    private fun parseHeaders(headersJson: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (!headersJson.isNullOrBlank()) {
            try {
                val json = JSONObject(headersJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.optString(key, "")
                }
            } catch (_: Exception) { }
        }
        return map
    }
}
