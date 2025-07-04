package com.example.expencetracker

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log

class ChatGptService() {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mediaType = "text/plain".toMediaType()

    suspend fun summarizeMessage(message: String): String = withContext(Dispatchers.IO) {
        val prompt = message
        Log.d("ChatGptService", "[REQUEST] summarizeMessage prompt: $prompt")
        val requestBody = gson.toJson(
            mapOf(
                "model" to "llama3",
                "prompt" to prompt,
                "stream" to false
            )
        ).toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://b0e8-2401-4900-1cd0-e2dc-8cd8-c186-5ff1-55f2.ngrok-free.app/api/generate")
            .addHeader("Content-Type", "text/plain")
            .addHeader("User-Agent", "Mozilla/5.0")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string()
            val json = gson.fromJson(body, Map::class.java)
            val summary = json["response"]?.toString() ?: "No summary"
            Log.d("ChatGptService", "[RESPONSE] summarizeMessage summary: $summary")
            summary
        } else {
            val error = "Error: ${response.code} - ${response.message}"
            Log.d("ChatGptService", "[RESPONSE] summarizeMessage error: $error")
            error
        }
    }

    suspend fun summarizeTransaction(transaction: TransactionInfo): String = withContext(Dispatchers.IO) {
        val textToSummarize = if (transaction.originalSmsText.isNotEmpty()) {
            transaction.originalSmsText
        } else {
            "Amount: ${transaction.amount}\nType: ${transaction.status}\nTime: ${transaction.time}\nBalance: ${transaction.acBal ?: "Not available"}"
        }
        val instruction = """
Summarize the following transaction SMS in this format:\nExpense Type: Credit or Debit\nAmount: XXX\nCurrency: INR\nTimestamp: DDMMYYHHMM\nSource:\nCategory:\nSubCategory:\nCustom1:\nCustom2:\n\nSMS: $textToSummarize
""".trimIndent()
        val prompt = instruction
        Log.d("ChatGptService", "[REQUEST] summarizeTransaction prompt: $prompt")
        val requestBody = gson.toJson(
            mapOf(
                "model" to "llama3",
                "prompt" to prompt,
                "stream" to false
            )
        ).toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://b0e8-2401-4900-1cd0-e2dc-8cd8-c186-5ff1-55f2.ngrok-free.app/api/generate")
            .addHeader("Content-Type", "text/plain")
            .addHeader("User-Agent", "Mozilla/5.0")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string()
            val json = gson.fromJson(body, Map::class.java)
            val summary = json["response"]?.toString() ?: "Transaction: ₹${transaction.amount} ${transaction.status}"
            Log.d("ChatGptService", "[RESPONSE] summarizeTransaction summary: $summary")
            summary
        } else {
            val errorBody = response.body?.string()
            val error = "Transaction: ₹${transaction.amount} ${transaction.status}"
            Log.d("ChatGptService", "[RESPONSE] summarizeTransaction error: $error, error body: $errorBody")
            error
        }
    }

    suspend fun summarizeRawSms(smsText: String): String = withContext(Dispatchers.IO) {
        val instruction = """
Summarize the following transaction SMS in this format:\nExpense Type: Credit or Debit\nAmount: XXX\nCurrency: INR\nTimestamp: DDMMYYHHMM\nSource:\nCategory:\nSubCategory:\nCustom1:\nCustom2:\n\nSMS: $smsText
""".trimIndent()
        val prompt = instruction
        Log.d("ChatGptService", "[REQUEST] summarizeRawSms prompt: $prompt")
        val requestBody = gson.toJson(
            mapOf(
                "model" to "llama3",
                "prompt" to prompt,
                "stream" to false
            )
        ).toRequestBody(mediaType)
        val request = Request.Builder()
            .url("https://b0e8-2401-4900-1cd0-e2dc-8cd8-c186-5ff1-55f2.ngrok-free.app/api/generate")
            .addHeader("Content-Type", "text/plain")
            .addHeader("User-Agent", "Mozilla/5.0")
            .post(requestBody)
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string()
            val json = gson.fromJson(body, Map::class.java)
            val summary = json["response"]?.toString() ?: "Unable to summarize"
            Log.d("ChatGptService", "[RESPONSE] summarizeRawSms summary: $summary")
            summary
        } else {
            val errorBody = response.body?.string()
            val error = "Unable to summarize SMS"
            Log.d("ChatGptService", "[RESPONSE] summarizeRawSms error: $error, error body: $errorBody")
            error
        }
    }
}