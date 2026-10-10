package com.example.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ShivoidHttpHelper {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        // Prioritize IPv4 loopback (127.0.0.1) when resolving "localhost" on Android
        // to prevent connection refused errors against local automation servers like Automate.
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname.equals("localhost", ignoreCase = true)) {
                    listOf(
                        InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)),
                        InetAddress.getByAddress("localhost", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1))
                    )
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        })
        .build()

    fun sanitizeUrl(rawUrl: String, baseUrl: String? = null): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return baseUrl ?: "http://127.0.0.1:8080/"
        }
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        // Handle relative paths (e.g. "/task" or "/webhook")
        if (trimmed.startsWith("/")) {
            val base = (baseUrl ?: "http://127.0.0.1:8080/").trim().trimEnd('/')
            return "$base$trimmed"
        }
        // Handle port-only (e.g. ":8080" or ":8080/task")
        if (trimmed.startsWith(":")) {
            return "http://127.0.0.1$trimmed"
        }
        // Default to http for local development / automation endpoints
        if (trimmed.startsWith("127.0.0.1") ||
            trimmed.startsWith("localhost") ||
            trimmed.startsWith("10.") ||
            trimmed.startsWith("192.168.")
        ) {
            return "http://$trimmed"
        }
        return "https://$trimmed"
    }

    suspend fun executeGet(url: String, headersJson: String?): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        val sanitizedUrl = sanitizeUrl(url)
        result.put("operation", "get")
        result.put("endpoint", sanitizedUrl)

        try {
            val reqBuilder = Request.Builder()
                .url(sanitizedUrl)
                .header("User-Agent", "SHI.VOID-Client/1.0")
                .header("Accept", "*/*")
                .get()

            parseHeaders(headersJson).forEach { (k, v) ->
                reqBuilder.header(k, v)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                val isSuccess = response.isSuccessful
                val bodyStr = response.body?.string() ?: ""

                result.put("httpDelivered", true)
                result.put("success", isSuccess)
                result.put("status", if (isSuccess) "success" else "error")
                result.put("statusCode", code)
                result.put("message", response.message)
                result.put("body", bodyStr)

                val respHeaders = JSONObject()
                response.headers.forEach { pair ->
                    respHeaders.put(pair.first, pair.second)
                }
                result.put("headers", respHeaders)

                if (!isSuccess) {
                    result.put("errorType", "HTTP_ERROR")
                    result.put("error", "HTTP $code ${response.message}: $bodyStr".trim())
                }
            }
        } catch (e: Exception) {
            populateExceptionError(result, e, sanitizedUrl)
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
        val sanitizedUrl = sanitizeUrl(url)
        result.put("operation", "post")
        result.put("endpoint", sanitizedUrl)

        try {
            val effectiveContentType = if (contentType.isNullOrBlank() || contentType == "[object Object]") {
                "application/json; charset=utf-8"
            } else {
                contentType
            }
            val cleanBody = if (body == "[object Object]") "{}" else (body ?: "")
            val mediaType = effectiveContentType.toMediaTypeOrNull()
            val requestBody = cleanBody.toRequestBody(mediaType)
            val reqBuilder = Request.Builder()
                .url(sanitizedUrl)
                .header("User-Agent", "SHI.VOID-Client/1.0")
                .header("Accept", "application/json, text/plain, */*")
                .post(requestBody)

            parseHeaders(headersJson).forEach { (k, v) ->
                if (!k.equals("Content-Type", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val code = response.code
                val isSuccess = response.isSuccessful
                val bodyStr = response.body?.string() ?: ""

                result.put("httpDelivered", true)
                result.put("success", isSuccess)
                result.put("status", if (isSuccess) "success" else "error")
                result.put("statusCode", code)
                result.put("message", response.message)
                result.put("body", bodyStr)

                val respHeaders = JSONObject()
                response.headers.forEach { pair ->
                    respHeaders.put(pair.first, pair.second)
                }
                result.put("headers", respHeaders)

                if (!isSuccess) {
                    result.put("errorType", "HTTP_ERROR")
                    result.put("error", "HTTP $code ${response.message}: $bodyStr".trim())
                }
            }
        } catch (e: Exception) {
            populateExceptionError(result, e, sanitizedUrl)
        }
        result
    }

    suspend fun callAutomate(endpointUrl: String, payloadJson: String?): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        val sanitizedUrl = sanitizeUrl(endpointUrl)
        result.put("operation", "automate")
        result.put("endpoint", sanitizedUrl)

        try {
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val cleanPayload = when {
                payloadJson.isNullOrBlank() -> "{}"
                payloadJson.trim() == "[object Object]" -> "{}"
                else -> {
                    val t = payloadJson.trim()
                    if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
                        t
                    } else {
                        // Plain text or primitive message - wrap as JSON object
                        val wrapper = JSONObject()
                        wrapper.put("message", t)
                        wrapper.toString()
                    }
                }
            }
            val requestBody = cleanPayload.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(sanitizedUrl)
                .header("User-Agent", "SHI.VOID-Automate-Client/1.0")
                .header("Accept", "application/json, text/plain, */*")
                .header("Content-Type", "application/json; charset=utf-8")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val code = response.code
                val isSuccess = response.isSuccessful
                val bodyStr = response.body?.string() ?: ""

                result.put("httpDelivered", true)
                result.put("success", isSuccess)
                result.put("status", if (isSuccess) "success" else "error")
                result.put("statusCode", code)
                result.put("message", response.message)
                result.put("body", bodyStr)

                val respHeaders = JSONObject()
                response.headers.forEach { pair ->
                    respHeaders.put(pair.first, pair.second)
                }
                result.put("headers", respHeaders)

                if (!isSuccess) {
                    result.put("errorType", "HTTP_ERROR")
                    val explanation = when (code) {
                        404 -> "HTTP 404 Not Found at $sanitizedUrl. Check that your Automate 'HTTP request receive' block Request URL matches this path and is actively running."
                        400 -> "HTTP 400 Bad Request at $sanitizedUrl. Check that your payload matches the Automate flow's expected format."
                        401, 403 -> "HTTP $code Forbidden/Unauthorized at $sanitizedUrl."
                        500 -> "HTTP 500 Internal Server Error: The Automate receiver flow encountered an error while processing."
                        else -> "HTTP $code ${response.message}: $bodyStr".trim()
                    }
                    result.put("error", explanation)
                }
            }
        } catch (e: Exception) {
            populateExceptionError(result, e, sanitizedUrl, isAutomate = true)
        }
        result
    }

    private fun populateExceptionError(
        result: JSONObject,
        e: Exception,
        url: String,
        isAutomate: Boolean = false
    ) {
        val errorType = when (e) {
            is ConnectException -> "CONNECTION_REFUSED"
            is SocketTimeoutException -> "TIMEOUT"
            is UnknownHostException -> "UNKNOWN_HOST"
            is MalformedURLException, is IllegalArgumentException -> "INVALID_URL"
            else -> "NETWORK_ERROR"
        }

        val explanation = when (e) {
            is ConnectException -> {
                if (isAutomate) {
                    "Connection refused to $url. Verify that your Automate flow is started and its 'HTTP request receive' block is actively listening on this port."
                } else {
                    "Connection refused to $url. The target host is unreachable or not listening."
                }
            }
            is SocketTimeoutException -> {
                "Connection or read timed out after waiting for $url."
            }
            is UnknownHostException -> {
                "Unable to resolve host for $url: ${e.message}."
            }
            is MalformedURLException, is IllegalArgumentException -> {
                "Invalid URL format: '$url' (${e.message})."
            }
            else -> e.message ?: "Network communication error"
        }

        result.put("httpDelivered", false)
        result.put("success", false)
        result.put("status", "error")
        result.put("statusCode", 0)
        result.put("errorType", errorType)
        result.put("error", explanation)
        result.put("message", e.javaClass.simpleName)
        result.put("body", "")
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
