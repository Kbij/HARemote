package com.koen.haremote.network

import android.content.Context
import com.koen.haremote.data.ButtonConfig
import com.koen.haremote.data.HostConfig
import com.koen.haremote.data.HttpMethod
import com.koen.haremote.location.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Fires the plain HTTP REST calls used for the Home Assistant buttons. Home Assistant is
 * reached over the local LAN (http), so there is no TLS handling here on purpose.
 *
 * Background GPS location updates no longer go through here — they use a persistent TCP
 * connection instead, see `network.tcp.TcpLocationClient`.
 */
object RestCaller {

    private const val TAG = "RestCall"

    // Home Assistant only answers a script call once the script itself finishes, and some
    // of Koen's scenes (AVR/TV power-on sequences with their own internal waits) legitimately
    // take longer than a few seconds - the old 8s readTimeout was tripping on those, not on
    // an actually broken connection. connectTimeout stays short: the host is on the same LAN,
    // so a slow *connect* really would mean something is wrong.
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    sealed class CallResult {
        data class Success(val code: Int) : CallResult()
        data class Failure(val message: String) : CallResult()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

    /**
     * Builds the full URL from [host]'s base URL + base path and the button's own [path] -
     * the part that used to be repeated in full on every button. Returns null when there's
     * no host to build against, or the host has no base URL configured yet.
     */
    fun buildUrl(host: HostConfig?, path: String): String? {
        if (host == null || host.baseUrl.isBlank()) return null
        return listOf(host.baseUrl, host.basePath, path)
            .map { it.trim('/') }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    suspend fun invoke(context: Context, button: ButtonConfig, host: HostConfig?): CallResult =
        withContext(Dispatchers.IO) {
            val url = buildUrl(host, button.path)
            if (url == null) {
                DiagnosticLogger.log(context, TAG, "${button.label}: geen host/pad ingesteld")
                return@withContext CallResult.Failure("Geen host/pad ingesteld")
            }
            val startedAt = System.currentTimeMillis()
            DiagnosticLogger.log(context, TAG, "${button.label}: start ${button.method} $url")
            val result = try {
                val builder = Request.Builder().url(url)
                val token = host?.bearerToken.orEmpty()
                if (token.isNotBlank()) {
                    builder.addHeader("Authorization", "Bearer $token")
                }
                when (button.method) {
                    HttpMethod.GET -> builder.get()
                    HttpMethod.POST -> {
                        val payload = button.body.ifBlank { "{}" }
                        builder.post(payload.toRequestBody(jsonMediaType))
                    }
                }
                execute(builder.build())
            } catch (e: Exception) {
                CallResult.Failure(e.message ?: "Onbekende fout")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            when (result) {
                is CallResult.Success ->
                    DiagnosticLogger.log(context, TAG, "${button.label}: OK (${result.code}) in ${durationMs}ms")
                is CallResult.Failure ->
                    DiagnosticLogger.log(context, TAG, "${button.label}: FOUT - ${result.message} (${durationMs}ms)")
            }
            result
        }

    private fun execute(request: Request): CallResult {
        client.newCall(request).execute().use { response ->
            return if (response.isSuccessful) {
                CallResult.Success(response.code)
            } else {
                CallResult.Failure("HTTP ${response.code}")
            }
        }
    }
}
