package com.example.expencetracker

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log

class ChatGptService(private val apiKey: String) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun summarizeMessage(message: String): String = withContext(Dispatchers.IO) {
        val prompt = """
            Summarize this SMS transaction in a single line, extracting amount, type (credit/debit), and any other key info:
            $message
        """.trimIndent()

        val requestBody = gson.toJson(
            mapOf(
                "model" to "gpt-3.5-turbo",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "You are a financial SMS summarizer."),
                    mapOf("role" to "user", "content" to prompt)
                ),
                "max_tokens" to 60
            )
        ).toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string()
            val json = gson.fromJson(body, Map::class.java)
            val choices = json["choices"] as? List<*>
            val messageObj = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            messageObj?.get("content")?.toString() ?: "No summary"
        } else {
            "Error: ${response.code} - ${response.message}"
        }
    }

    suspend fun summarizeTransaction(transaction: TransactionInfo): String = withContext(Dispatchers.IO) {
        Log.d("ChatGptService", "Starting transaction summarization for: ₹${transaction.amount} ${transaction.status}")
        
        // Use the original SMS text if available, otherwise fall back to formatted data
        val textToSummarize = if (transaction.originalSmsText.isNotEmpty()) {
            Log.d("ChatGptService", "Using original SMS text for summarization")
            transaction.originalSmsText
        } else {
            Log.d("ChatGptService", "Original SMS text not available, using formatted data")
            """
            Amount: ${transaction.amount}
            Type: ${transaction.status}
            Time: ${transaction.time}
            Balance: ${transaction.acBal ?: "Not available"}
            """.trimIndent()
        }

        val prompt = """
            Summarize this financial SMS transaction in a clear, user-friendly way. Extract the key information and present it in natural language:
            
            $textToSummarize
            
            Please provide a concise summary that a user would understand easily.
        """.trimIndent()

        Log.d("ChatGptService", "Sending prompt to ChatGPT: $prompt")

        val requestBody = gson.toJson(
            mapOf(
                "model" to "gpt-3.5-turbo",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "You are a financial transaction summarizer. Provide clear, concise summaries of banking SMS transactions."),
                    mapOf("role" to "user", "content" to prompt)
                ),
                "max_tokens" to 100,
                "temperature" to 0.3
            )
        ).toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string()
            val json = gson.fromJson(body, Map::class.java)
            val choices = json["choices"] as? List<*>
            val messageObj = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val summary = messageObj?.get("content")?.toString() ?: "Transaction: ₹${transaction.amount} ${transaction.status}"
            
            Log.d("ChatGptService", "ChatGPT Summary Generated: $summary")
            Log.i("ChatGptService", "=== TRANSACTION SUMMARY ===")
            Log.i("ChatGptService", "Original SMS: ${transaction.originalSmsText}")
            Log.i("ChatGptService", "Parsed Amount: ₹${transaction.amount}")
            Log.i("ChatGptService", "Parsed Status: ${transaction.status}")
            Log.i("ChatGptService", "AI Summary: $summary")
            Log.i("ChatGptService", "==========================")
            
            summary
        } else {
            val errorMsg = "Transaction: ₹${transaction.amount} ${transaction.status}"
            Log.e("ChatGptService", "API Error: ${response.code} - ${response.message}")
            Log.e("ChatGptService", "Using fallback summary: $errorMsg")
            errorMsg
        }
    }

    // New method to summarize raw SMS text directly
    suspend fun summarizeRawSms(smsText: String): String = withContext(Dispatchers.IO) {
        Log.d("ChatGptService", "Starting raw SMS summarization")
        
        val prompt = """
            Summarize this financial SMS transaction in a clear, user-friendly way. Extract the key information and present it in natural language:
            
            $smsText
            
            Please provide a concise summary that a user would understand easily.
        """.trimIndent()

        Log.d("ChatGptService", "Sending raw SMS to ChatGPT: $prompt")

        val requestBody = gson.toJson(
            mapOf(
                "model" to "gpt-3.5-turbo",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "You are a financial transaction summarizer. Provide clear, concise summaries of banking SMS transactions."),
                    mapOf("role" to "user", "content" to prompt)
                ),
                "max_tokens" to 100,
                "temperature" to 0.3
            )
        ).toRequestBody(mediaType)

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string()
            val json = gson.fromJson(body, Map::class.java)
            val choices = json["choices"] as? List<*>
            val messageObj = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val summary = messageObj?.get("content")?.toString() ?: "Unable to summarize"
            
            Log.d("ChatGptService", "Raw SMS Summary Generated: $summary")
            Log.i("ChatGptService", "=== RAW SMS SUMMARY ===")
            Log.i("ChatGptService", "Original SMS: $smsText")
            Log.i("ChatGptService", "AI Summary: $summary")
            Log.i("ChatGptService", "======================")
            
            summary
        } else {
            val errorMsg = "Unable to summarize SMS"
            Log.e("ChatGptService", "API Error: ${response.code} - ${response.message}")
            Log.e("ChatGptService", "Using fallback summary: $errorMsg")
            errorMsg
        }
    }
} 