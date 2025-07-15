package com.example.expencetracker

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ChatGptService {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mediaType = "application/json".toMediaType()

    private val apiKey = "API KEY"
    private val apiUrl = "https://api.openai.com/v1/chat/completions"

    private suspend fun sendPrompt(prompt: String): String = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(
            mapOf(
                "model" to "gpt-3.5-turbo",
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to prompt
                    )
                ),
                "stream" to false
            )
        ).toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = gson.fromJson(body, Map::class.java)
                val choices = json["choices"] as? List<*>
                val summary = if (choices != null && choices.isNotEmpty()) {
                    val first = choices[0] as? Map<*, *>
                    val message = first?.get("message") as? Map<*, *>
                    message?.get("content")?.toString() ?: "No summary"
                } else {
                    "No summary"
                }
                Log.d("ChatGptService", "[RESPONSE] ChatGPT summary: $summary")
                return@withContext summary
            } else {
                val error = "Error: ${response.code} - ${response.message}"
                Log.e("ChatGptService", error)
                return@withContext error
            }
        } catch (e: Exception) {
            Log.e("ChatGptService", "Network error: ${e.localizedMessage}", e)
            return@withContext "Network error: ${e.localizedMessage}"
        }
    }

    suspend fun summarizeMessage(message: String): String {
        Log.d("ChatGptService", "[REQUEST] summarizeMessage prompt: $message")
        return sendPrompt(message)
    }

    suspend fun summarizeTransaction(transaction: TransactionInfo): String {
        val textToSummarize = if (transaction.originalSmsText.isNotEmpty()) {
            transaction.originalSmsText
        } else {
            "Amount: ${transaction.amount}\nType: ${transaction.status}\nTime: ${transaction.time}\nBalance: ${transaction.acBal ?: "Not available"}"
        }
        val prompt = """
Summarize the following transaction SMS in this format:\nExpense Type: Credit or Debit\nAmount: XXX\nCurrency: INR\nTimestamp: DDMMYYHHMM\nSource:\nCategory:\nSubCategory:\nCustom1:\nCustom2:\n\nSMS: $textToSummarize
        """.trimIndent()
        Log.d("ChatGptService", "[REQUEST] summarizeTransaction prompt: $prompt")
        return sendPrompt(prompt)
    }

    suspend fun summarizeRawSms(smsText: String): String {
        val prompt = """
Summarize the following transaction SMS in this format:\nExpense Type: Credit or Debit\nAmount: XXX\nCurrency: INR\nTimestamp: DDMMYYHHMM\nSource:\nCategory:\nSubCategory:\nCustom1:\nCustom2:\n\nSMS: $smsText
        """.trimIndent()
        Log.d("ChatGptService", "[REQUEST] summarizeRawSms prompt: $prompt")
        return sendPrompt(prompt)
    }
}
