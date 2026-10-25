package com.nemos.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class VerifyStatus {
    ACTIVE,
    INVALID_HWID,
    EXPIRED,
    NOT_FOUND,
    NETWORK_ERROR
}

data class VerifyResult(
    val status: VerifyStatus,
    val expiresAt: String? = null,
    val errorMessage: String? = null
)

object LicenseApiClient {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Отправляет POST /api/v1/license/verify с телом {"key": "...", "hwid": "..."}
     * на наш FastAPI сервер на Wispbyte.
     */
    suspend fun verifyLicense(
        baseUrl: String,
        licenseKey: String,
        hwid: String
    ): VerifyResult = withContext(Dispatchers.IO) {
        val cleanBaseUrl = baseUrl.trim().trimEnd('/')
        val endpoint = "$cleanBaseUrl/api/v1/license/verify"

        val payloadJson = JSONObject().apply {
            put("key", licenseKey.trim().uppercase())
            put("hwid", hwid.trim())
        }

        val requestBody = payloadJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .header("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext VerifyResult(
                        status = VerifyStatus.NETWORK_ERROR,
                        errorMessage = "HTTP ${response.code}: $rawBody"
                    )
                }

                val json = JSONObject(rawBody)
                val statusStr = json.optString("status", "NOT_FOUND").uppercase()
                val expiresAt = if (json.isNull("expires_at")) null else json.optString("expires_at")

                val parsedStatus = when (statusStr) {
                    "ACTIVE" -> VerifyStatus.ACTIVE
                    "INVALID_HWID" -> VerifyStatus.INVALID_HWID
                    "EXPIRED" -> VerifyStatus.EXPIRED
                    "NOT_FOUND" -> VerifyStatus.NOT_FOUND
                    else -> VerifyStatus.NOT_FOUND
                }

                VerifyResult(status = parsedStatus, expiresAt = expiresAt)
            }
        } catch (e: Exception) {
            VerifyResult(
                status = VerifyStatus.NETWORK_ERROR,
                errorMessage = e.localizedMessage ?: "Ошибка подключения к серверу"
            )
        }
    }
}
