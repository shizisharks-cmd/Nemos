package com.nemos.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class VerifyResponse(
    val status: String,
    val message: String? = null,
    val expiresAt: String? = null
)

object ApiClient {
    suspend fun verifyKey(serverUrl: String, key: String, hwid: String): Result<VerifyResponse> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val formattedUrl = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                    serverUrl.trimEnd('/')
                } else {
                    "http://${serverUrl.trimEnd('/')}"
                }
                val url = URL("$formattedUrl/api/verify")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }

                val body = JSONObject().apply {
                    put("key", key)
                    put("hwid", hwid)
                }.toString()

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }

                val json = JSONObject(text)
                val status = json.optString("status", if (code in 200..299) "ok" else "error")
                val msg = json.optString("message", null)
                val exp = json.optString("expires_at", json.optString("expiresAt", "2026-12-31"))

                Result.success(VerifyResponse(status = status, message = msg, expiresAt = exp))
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }
}
