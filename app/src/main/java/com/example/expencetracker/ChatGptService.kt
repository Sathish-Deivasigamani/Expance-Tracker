package com.example.expencetracker

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import android.net.Uri
import java.io.File

class ChatGptService {
    /**
     * Summarizes extracted text (from OCR) using OpenAI GPT
     * Usage: Call this after extracting text from an image using OCR
     */
    suspend fun summarizeExtractedText(text: String): String = withContext(Dispatchers.IO) {
        val prompt = "Please analyze this text from the image:\n$text"
        val requestBodyJson = mapOf(
            "model" to "gpt-4",
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a helpful assistant."),
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
                val summary = if (!choices.isNullOrEmpty()) {
                    val first = choices[0] as? Map<*, *>
                    val message = first?.get("message") as? Map<*, *>
                    message?.get("content")?.toString() ?: "No summary"
                } else {
                    "No summary"
                }
                Log.d("ChatGptService", "[RESPONSE] Text summary: $summary")
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
        // Extract status (Credited or Debited) from summary
        val statusRegex = Regex("(Credited|Debited|Credit|Debit|Paid|Received|Payment|Deposit|Withdrawn|Completed|Success|Failed)", RegexOption.IGNORE_CASE)
        val statusMatch = statusRegex.find(summary)
        val status = when (statusMatch?.value?.lowercase()) {
            "credited", "credit", "received", "deposit" -> "Credited"
            "debited", "debit", "paid", "withdrawn" -> "Debited"
            else -> "Image"
        }
        obj.put("status", status)
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
    private val apiKey = "api"
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

    /**
     * Saves a scanned image ByteArray as a high-quality JPEG file in internal storage
     * Returns the absolute file path
     */
    fun saveScannedImageHighQuality(context: android.content.Context, imageBytes: ByteArray, fileName: String = "scan_image.jpg"): String {
        try {
            // Decode ByteArray to Bitmap
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            val file = java.io.File(context.filesDir, fileName)
            // Save as JPEG with 95% quality
            val out = java.io.FileOutputStream(file)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            out.flush()
            out.close()
            return file.absolutePath
        } catch (e: Exception) {
            Log.e("ChatGptService", "Error saving high quality scan: ${e.localizedMessage}", e)
            return ""
        }
    }

    /**
     * Extracts text from image using ML Kit OCR
     * Requires ML Kit dependency: implementation 'com.google.mlkit:text-recognition:16.0.0'
     */
    suspend fun extractTextFromImage(context: android.content.Context, imageFilePath: String): String = withContext(Dispatchers.IO) {
        try {
            // Load bitmap from file
            val bitmap = android.graphics.BitmapFactory.decodeFile(imageFilePath)
            if (bitmap == null) {
                Log.e("ChatGptService", "[OCR] Failed to decode bitmap from file: $imageFilePath")
                return@withContext ""
            }

            // Preprocess: convert to grayscale and enhance contrast
            val preprocessedBitmap = preprocessBitmapForOcr(bitmap)

            // Create InputImage from preprocessed bitmap
            val image = InputImage.fromBitmap(preprocessedBitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = recognizer.process(image).await()
            Log.d("ChatGptService", "[OCR] Extracted text: ${result.text}")
            // Print extracted text for both scan and upload
            Log.i("ChatGptService", "[EXTRACTED_TEXT] $imageFilePath: ${result.text}")
            return@withContext result.text
        } catch (e: Exception) {
            Log.e("ChatGptService", "[OCR] Error extracting text: ${e.localizedMessage}", e)
            return@withContext ""
        }
    }

    // Preprocess bitmap: grayscale and contrast enhancement
    private fun preprocessBitmapForOcr(src: android.graphics.Bitmap): android.graphics.Bitmap {
        // Convert to grayscale
        val width = src.width
        val height = src.height
        val grayscale = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(grayscale)
        val paint = android.graphics.Paint()
        val colorMatrix = android.graphics.ColorMatrix()
        colorMatrix.setSaturation(0f)
        // Increase contrast (1.5x)
        val contrast = 1.5f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = android.graphics.ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        colorMatrix.postConcat(contrastMatrix)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return grayscale
    }

    /**
     * Full workflow: Save scan, extract text, summarize, and store
     * Use this for scanned images to ensure OCR is used before GPT
     */
    suspend fun processScannedImageWithOcrAndGpt(context: android.content.Context, imageBytes: ByteArray, fileName: String = "scan_image.jpg"): String {
        // Save high quality scan
        val filePath = saveScannedImageHighQuality(context, imageBytes, fileName)
        Log.d("ChatGptService", "[SCAN] Saved scan at: $filePath")
        // Extract text using OCR
        val extractedText = extractTextFromImage(context, filePath)
        Log.d("ChatGptService", "[SCAN] OCR extracted: $extractedText")
        // Check if OCR result is empty or too short
        if (extractedText.isBlank() || extractedText.length < 10) {
            Log.e("ChatGptService", "[SCAN] OCR failed or text too short. Prompting user to retake or check image.")
            return "Could not extract text from the image. Please ensure the bill is well-lit, in focus, and all text is visible. Try retaking the photo."
        }
        // Summarize with GPT
        val summary = summarizeExtractedText(extractedText)
        Log.d("ChatGptService", "[SCAN] GPT summary: $summary")
        // Store summary
        storeImageSummary(context, summary)
        return summary
    }
}
