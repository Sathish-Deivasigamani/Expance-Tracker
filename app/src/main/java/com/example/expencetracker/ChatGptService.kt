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
    /**
     * Stores a summarized image result as a TransactionInfo in transactions.json
     */
    fun storeImageSummary(context: android.content.Context, summary: String) {
        val file = java.io.File(context.filesDir, "transactions.json")
        val existing = if (file.exists()) file.readText() else ""
        val jsonArray = if (existing.isNotBlank()) org.json.JSONArray(existing) else org.json.JSONArray()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
        val obj = org.json.JSONObject()
        obj.put("id", java.util.UUID.randomUUID().toString())
        // Extract amount from summary using regex (₹ or Rs)
        val amountRegex = Regex("(?:INR|Rs\\.?|₹)\\s?([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
        val match = amountRegex.find(summary)
        val amount = match?.groupValues?.getOrNull(1) ?: "-"
        obj.put("amount", amount)
        obj.put("status", "Image")
        obj.put("acBal", "-")
        obj.put("time", now)
        obj.put("originalSmsText", "[Image]")
        obj.put("summary", summary)
        jsonArray.put(obj)
        file.writeText(jsonArray.toString())
    }
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mediaType = "application/json".toMediaType()

    // ❗ Replace with your actual API key, ideally use secure storage instead of hardcoding
    private val apiKey = "API Key"
    private val apiUrl = "https://api.openai.com/v1/chat/completions"

    /**
     * Summarizes an image using GPT-4o Vision model
     */
    suspend fun summarizeImage(base64Image: String): String = withContext(Dispatchers.IO) {
        Log.d("ChatGptService", "[REQUEST] summarizeImage called with base64 length: ${base64Image.length}")

        val prompt = "Summarize the content of this receipt or transaction image. Extract key details such as amount, date, merchant, and payment method."

        val requestBodyJson = mapOf(
            "model" to "gpt-4o", // ✅ Use GPT-4o for vision tasks
            "messages" to listOf(
                mapOf(
                    "role" to "user",
                    "content" to listOf(
                        mapOf("type" to "text", "text" to prompt),
                        mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64Image"))
                    )
                )
            ),
            "max_tokens" to 512
        )

        val requestBody = gson.toJson(requestBodyJson).toRequestBody(mediaType)
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful && body != null) {
                val json = gson.fromJson(body, Map::class.java)
                val choices = json["choices"] as? List<*>
                val summary = if (!choices.isNullOrEmpty()) {
                    val first = choices[0] as? Map<*, *>
                    val message = first?.get("message") as? Map<*, *>
                    message?.get("content")?.toString() ?: "No summary"
                } else {
                    "No summary"
                }
                Log.d("ChatGptService", "[RESPONSE] Vision summary: $summary")
                return@withContext summary
            } else {
                Log.e("ChatGptService", "Error: ${response.code} - ${response.message}")
                return@withContext "Error: ${response.code} - ${response.message}"
            }
        } catch (e: Exception) {
            Log.e("ChatGptService", "Exception: ${e.localizedMessage}", e)
            return@withContext "Exception: ${e.localizedMessage}"
        }
    }

    /**
     * Sends a generic text prompt to GPT-3.5 or GPT-4
     */
    private suspend fun sendPrompt(prompt: String): String = withContext(Dispatchers.IO) {
        val requestBodyJson = mapOf(
            "model" to "gpt-3.5-turbo",
            "messages" to listOf(
                mapOf("role" to "user", "content" to prompt)
            )
        )

        val requestBody = gson.toJson(requestBodyJson).toRequestBody(mediaType)
        val request = Request.Builder()
            .url(apiUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful && body != null) {
                val json = gson.fromJson(body, Map::class.java)
                val choices = json["choices"] as? List<*>
                val result = if (!choices.isNullOrEmpty()) {
                    val first = choices[0] as? Map<*, *>
                    val message = first?.get("message") as? Map<*, *>
                    message?.get("content")?.toString() ?: "No response"
                } else {
                    "No response"
                }
                Log.d("ChatGptService", "[RESPONSE] Text summary: $result")
                return@withContext result
            } else {
                Log.e("ChatGptService", "Error: ${response.code} - ${response.message}")
                return@withContext "Error: ${response.code} - ${response.message}"
            }
        } catch (e: Exception) {
            Log.e("ChatGptService", "Exception: ${e.localizedMessage}", e)
            return@withContext "Exception: ${e.localizedMessage}"
        }
    }

    suspend fun summarizeMessage(message: String): String {
        Log.d("ChatGptService", "[REQUEST] summarizeMessage: $message")
        return sendPrompt(message)
    }

    suspend fun summarizeTransaction(transaction: TransactionInfo): String {
        val textToSummarize = if (transaction.originalSmsText.isNotEmpty()) {
            transaction.originalSmsText
        } else {
            """
                Amount: ${transaction.amount}
                Type: ${transaction.status}
                Time: ${transaction.time}
                Balance: ${transaction.acBal ?: "Not available"}
            """.trimIndent()
        }

        val prompt = """
            Summarize the following transaction SMS in this format:
            Expense Type: Credit or Debit
            Amount: XXX
            Currency: INR
            Timestamp: DDMMYYHHMM
            Source:
            Category:
            SubCategory:
            Custom1:
            Custom2:

            SMS: $textToSummarize
        """.trimIndent()

        Log.d("ChatGptService", "[REQUEST] summarizeTransaction prompt: $prompt")
        return sendPrompt(prompt)
    }

    suspend fun summarizeRawSms(smsText: String): String {
        val prompt = """
            Summarize the following transaction SMS in this format:
            Expense Type: Credit or Debit
            Amount: XXX
            Currency: INR
            Timestamp: DDMMYYHHMM
            Source:
            Category:
            SubCategory:
            Custom1:
            Custom2:

            SMS: $smsText
        """.trimIndent()

        Log.d("ChatGptService", "[REQUEST] summarizeRawSms prompt: $prompt")
        return sendPrompt(prompt)
    }
}
