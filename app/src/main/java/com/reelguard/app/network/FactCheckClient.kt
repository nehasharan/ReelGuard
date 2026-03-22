package com.reelguard.app.network

import android.util.Log
import com.reelguard.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fact-Check API Client
 *
 * Sends extracted screen content to your backend server, which runs
 * the LLM-powered fact-checking pipeline and returns a verdict.
 *
 * Your backend (not included here) would:
 * 1. Receive the extracted text
 * 2. Use an LLM to identify specific factual claims
 * 3. Search the web for each claim
 * 4. Have the LLM assess each claim against sources
 * 5. Return a structured verdict
 *
 * NOTE: Replace BASE_URL with your actual backend URL.
 * Never call the LLM API directly from the app — always go through
 * your server to protect your API key.
 */
class FactCheckClient {

    companion object {
        private const val TAG = "FactCheckClient"

        // TODO: Replace with your backend URL
        private const val BASE_URL = "http://192.168.1.207:8000/api"
        private const val ENDPOINT_CHECK = "$BASE_URL/fact-check"

        private const val TIMEOUT_MS = 15_000
    }

    /**
     * Send content to the fact-checking backend and parse the response.
     * Returns null on any error (network, parse, timeout).
     */
    suspend fun checkContent(content: ScreenContent): FactCheckResult? {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("app_package", content.appPackage)
                    put("text", content.mergedText())
                    put("timestamp", content.timestamp)
                }

                val response = postJson(ENDPOINT_CHECK, requestBody)
                parseFactCheckResponse(response, content)
            } catch (e: Exception) {
                Log.e(TAG, "Fact-check API error", e)
                null
            }
        }
    }

    /**
     * Parse the JSON response from the backend into our data model.
     *
     * Expected response format:
     * {
     *   "overall_verdict": "LIKELY_FALSE",
     *   "summary": "The claim about X is contradicted by ...",
     *   "claims": [
     *     {
     *       "text": "Product Y cures cancer",
     *       "verdict": "LIKELY_FALSE",
     *       "explanation": "No scientific evidence supports this...",
     *       "sources": ["https://who.int/...", "https://pubmed.ncbi.nlm.nih.gov/..."]
     *     }
     *   ]
     * }
     */
    private fun parseFactCheckResponse(
        json: JSONObject,
        originalContent: ScreenContent
    ): FactCheckResult {
        val claimsArray = json.optJSONArray("claims") ?: JSONArray()
        val claims = mutableListOf<Claim>()

        for (i in 0 until claimsArray.length()) {
            val claimJson = claimsArray.getJSONObject(i)
            val sources = mutableListOf<String>()
            val sourcesArray = claimJson.optJSONArray("sources") ?: JSONArray()
            for (j in 0 until sourcesArray.length()) {
                sources.add(sourcesArray.getString(j))
            }

            claims.add(
                Claim(
                    text = claimJson.getString("text"),
                    verdict = VerdictLevel.valueOf(
                        claimJson.optString("verdict", "UNVERIFIED")
                    ),
                    explanation = claimJson.optString("explanation", ""),
                    sources = sources
                )
            )
        }

        return FactCheckResult(
            sourceApp = originalContent.appPackage,
            extractedText = originalContent.mergedText().take(500),
            claims = claims,
            overallVerdict = VerdictLevel.valueOf(
                json.optString("overall_verdict", "UNVERIFIED")
            ),
            summary = json.optString("summary", "Could not determine verdict")
        )
    }

    // ─── HTTP Helpers ───

    private fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                // TODO: Add auth header
                // setRequestProperty("Authorization", "Bearer $API_KEY")
            }

            // Write request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            // Read response
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }

            if (responseCode !in 200..299) {
                Log.e(TAG, "API returned $responseCode: $responseText")
                throw RuntimeException("API error: $responseCode")
            }

            JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}
