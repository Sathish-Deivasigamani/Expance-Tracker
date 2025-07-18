package com.example.expencetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expencetracker.data.Expense
import com.example.expencetracker.data.ExpenseType
import com.example.expencetracker.ui.theme.ExpenceTrackerTheme
import com.example.expencetracker.viewmodel.ExpenseViewModel
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

data class TransactionInfo(
    val id: String = UUID.randomUUID().toString(),
    val amount: String,
    val status: String, // "Credited" or "Debited"
    val acBal: String?,
    val time: String,
    val originalSmsText: String = "",
    val summary: String = "" // Add summary field
)

class MainActivity : ComponentActivity() {
    // ScanDialog composable for scan tab
    @Composable
    fun ScanDialog(onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Scan Transaction", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.List, contentDescription = "Camera")
            Spacer(Modifier.width(8.dp))
            Text("Camera")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGallery, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.List, contentDescription = "Gallery")
            Spacer(Modifier.width(8.dp))
            Text("Upload from Gallery")
        }
    }
}

    private lateinit var messages: MutableList<String>
    private var reloadMessages: (() -> Unit)? = null
    private val chatGptService = ChatGptService()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, reload messages if needed
            reloadMessages?.invoke()
        } else {
            // Permission denied, handle accordingly
        }
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Storage permission granted
        } else {
            // Storage permission denied
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Camera permission granted
        } else {
            // Camera permission denied
        }
    }

    private var showImageSummaryDialog by mutableStateOf(false)
    private var imageSummaryText by mutableStateOf("")
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val imageBitmap: Bitmap? = when {
                data?.extras?.get("data") is Bitmap -> data.extras?.get("data") as? Bitmap
                data?.data != null -> {
                    val uri = data.data
                    try {
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    } catch (e: Exception) {
                        null
                    }
                }
                else -> null
            }
            if (imageBitmap != null) {
                // Convert bitmap to base64
                val outputStream = java.io.ByteArrayOutputStream()
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val imageBytes = outputStream.toByteArray()
                val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT)
                // Send to ChatGPT for summarization
                summarizeImageWithChatGpt(base64Image)
            }
        }
    }

    private fun summarizeImageWithChatGpt(base64Image: String) {
        lifecycleScope.launch {
            try {
                imageSummaryText = "Summarizing..."
                showImageSummaryDialog = true
                val summary = chatGptService.summarizeImage(base64Image)
                imageSummaryText = summary
                // Store the summary as a transaction
                chatGptService.storeImageSummary(this@MainActivity, summary)
            } catch (e: Exception) {
                imageSummaryText = "Error: ${e.message}"
            }
        }
    }
    @Composable
    fun ImageSummaryDialog(show: Boolean, summary: String, onDismiss: () -> Unit) {
        if (show) {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Image Summary") },
                text = { Text(summary) },
                confirmButton = {
                    Button(onClick = onDismiss) { Text("OK") }
                }
            )
        }
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            takePictureLauncher.launch(cameraIntent)
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        takePictureLauncher.launch(intent)
    }

    fun parseTransactionSms(body: String, date: Long, address: String?): TransactionInfo? {
        val amountRegex = Regex("(?:INR|Rs\\.?|₹)\\s?([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
        val creditedRegex = Regex("credited", RegexOption.IGNORE_CASE)
        val debitedRegex = Regex("debited", RegexOption.IGNORE_CASE)
        val acBalRegex = Regex("(?:A/C\\s*bal(?:ance)?|bal(?:ance)?|Avail(?:able)?\\s*bal(?:ance)?)[^\\d]*([\\d,]+\\.?\\d*)", RegexOption.IGNORE_CASE)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val amount = amountRegex.find(body)?.groupValues?.getOrNull(1) ?: return null
        val status = when {
            creditedRegex.containsMatchIn(body) -> "Credited"
            debitedRegex.containsMatchIn(body) -> "Debited"
            else -> "Unknown"
        }
        val acBal = acBalRegex.find(body)?.groupValues?.getOrNull(1)
        val time = dateFormat.format(Date(date))
        val id = UUID.nameUUIDFromBytes((address.orEmpty() + body + date).toByteArray()).toString()
        return TransactionInfo(id = id, amount = amount, status = status, acBal = acBal, time = time, originalSmsText = body)
    }

    private fun saveTransactionsJson(transactions: List<TransactionInfo>) {
        val file = File(filesDir, "transactions.json")
        val jsonArray = JSONArray()
        transactions.forEach { info ->
            val obj = JSONObject()
            obj.put("amount", info.amount)
            obj.put("status", info.status)
            obj.put("acBal", info.acBal)
            obj.put("time", info.time)
            jsonArray.put(obj)
        }
        file.writeText(jsonArray.toString())
    }

    private fun saveAllTransactions(transactions: List<TransactionInfo>) {
        val file = File(filesDir, "transactions.json")
        val jsonArray = JSONArray()
        transactions.forEach { info ->
            val obj = JSONObject()
            obj.put("id", info.id)
            obj.put("amount", info.amount)
            obj.put("status", info.status)
            obj.put("acBal", info.acBal)
            obj.put("time", info.time)
            obj.put("originalSmsText", info.originalSmsText)
            obj.put("summary", info.summary)
            jsonArray.put(obj)
        }
        file.writeText(jsonArray.toString())
    }

    // Function to store transactions
    fun storeTransactions(transactions: List<TransactionInfo>) {
        try {
            val file = File(filesDir, "transactions.json")
            val existingTransactions = getStoredTransactions().toMutableList()
            val existingIds = existingTransactions.map { it.id }.toSet()
            
            // Add new transactions, avoiding duplicates
            transactions.forEach { newTransaction ->
                if (newTransaction.id !in existingIds) {
                    existingTransactions.add(newTransaction)
                }
            }
            
            // Save all transactions
            saveAllTransactions(existingTransactions)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Function to get stored transactions
    fun getStoredTransactions(): List<TransactionInfo> {
        return try {
            val file = File(filesDir, "transactions.json")
            if (!file.exists()) {
                return emptyList()
            }
            val jsonString = file.readText()
            if (jsonString.isBlank()) {
                return emptyList()
            }
            val jsonArray = JSONArray(jsonString)
            List(jsonArray.length()) { i ->
                val obj = jsonArray.getJSONObject(i)
                TransactionInfo(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    amount = obj.getString("amount"),
                    status = obj.getString("status"),
                    acBal = obj.optString("acBal", null),
                    time = obj.getString("time"),
                    originalSmsText = obj.optString("originalSmsText", ""),
                    summary = obj.optString("summary", "")
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveTransactionsJsonToProject(transactions: List<TransactionInfo>) {
        // Path to the project directory (for development/testing only)
        val file = File(filesDir, "transactions.json")
        val jsonArray = JSONArray()
        transactions.forEach { info ->
            val obj = JSONObject()
            obj.put("amount", info.amount)
            obj.put("status", info.status)
            obj.put("acBal", info.acBal)
            obj.put("time", info.time)
            jsonArray.put(obj)
        }
        file.writeText(jsonArray.toString())
    }

    private fun saveTransactionsJsonExternal(transactions: List<TransactionInfo>) {
        val dir = File(getExternalFilesDir(null), "ExpenceTrackerData")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "transactions.json")
        val jsonArray = JSONArray()
        transactions.forEach { info ->
            val obj = JSONObject()
            obj.put("amount", info.amount)
            obj.put("status", info.status)
            obj.put("acBal", info.acBal)
            obj.put("time", info.time)
            jsonArray.put(obj)
        }
        file.writeText(jsonArray.toString())
    }

    // Process transaction using ChatGPT and store summary
    fun processTransactionWithChatGpt(transaction: TransactionInfo) {
        Log.d("MainActivity", "[ChatGPT REQUEST] Transaction: id=${transaction.id}, amount=₹${transaction.amount}, status=${transaction.status}, sms='${transaction.originalSmsText}'")
        lifecycleScope.launch {
            try {
                val summary = chatGptService.summarizeTransaction(transaction)
                Log.d("MainActivity", "[ChatGPT RESPONSE] id=${transaction.id}, summary='$summary'")
                // Update transaction with summary and save
                val updatedTransaction = transaction.copy(summary = summary)
                updateTransaction(updatedTransaction)
                Log.i("MainActivity", "ChatGPT processing completed successfully for transaction: ₹${transaction.amount}")
                Log.i("MainActivity", "Stored summary: $summary")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing transaction with ChatGPT: ${e.message}", e)
            }
        }
    }

    // Process multiple transactions with ChatGPT
    fun processTransactionsWithChatGpt(transactions: List<TransactionInfo>) {
        transactions.forEach { transaction ->
            processTransactionWithChatGpt(transaction)
        }
    }

    // Modified getAllSmsAndSave to use ChatGPT summarization
    fun getAllSmsAndSave(limit: Int = 50): List<TransactionInfo> {
        val transactionKeywords = listOf(
            "debited", "credited", "withdrawn", "deposited", "transferred", "transaction", "txn", "payment", "purchase", "spent", "paid", "received", "sent", "transfer"
        )
        val smsList = mutableListOf<TransactionInfo>()
        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER + " LIMIT $limit"
        )
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val address = it.getString(addressIdx)
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date, address)?.let { info -> 
                        smsList.add(info)
                        // Store each transaction immediately after parsing
                        storeTransactions(listOf(info))
                        // Process with ChatGPT for summarization
                        processTransactionWithChatGpt(info)
                    }
                }
            }
        }
        return smsList
    }

    private fun getAllSmsAndSaveToProject(limit: Int = 50): List<String> {
        val transactionKeywords = listOf(
            "debited", "credited", "withdrawn", "deposited", "transferred", "transaction", "txn", "payment", "purchase", "spent", "paid", "received", "sent", "transfer"
        )
        val smsList = mutableListOf<TransactionInfo>()
        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER + " LIMIT $limit"
        )
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val address = it.getString(addressIdx)
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date, address)?.let { info -> smsList.add(info) }
                }
            }
        }
        saveTransactionsJsonToProject(smsList)
        return smsList.map { info ->
            "Amount: ${info.amount}\nStatus: ${info.status}\nAcBal: ${info.acBal ?: "-"}\nTime: ${info.time}"
        }
    }

    // Change visibility to public so it can be called from Composable
    fun getAllSmsAndSaveExternal(limit: Int = 50): List<String> {
        val transactionKeywords = listOf(
            "debited", "credited", "withdrawn", "deposited", "transferred", "transaction", "txn", "payment", "purchase", "spent", "paid", "received", "sent", "transfer"
        )
        val smsList = mutableListOf<TransactionInfo>()
        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER + " LIMIT $limit"
        )
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val address = it.getString(addressIdx)
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date, address)?.let { info -> smsList.add(info) }
                }
            }
        }
        saveTransactionsJsonExternal(smsList)
        return smsList.map { info ->
            "Amount: ${info.amount}\nStatus: ${info.status}\nAcBal: ${info.acBal ?: "-"}\nTime: ${info.time}"
        }
    }

    private fun getAllSms(limit: Int = 50): List<String> {
        val transactionKeywords = listOf(
            "debited", "credited", "withdrawn", "deposited", "transferred", "transaction", "txn", "payment", "purchase", "spent", "paid", "received", "sent", "transfer"
        )
        val smsList = mutableListOf<TransactionInfo>()
        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER + " LIMIT $limit"
        )
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            while (it.moveToNext()) {
                val address = it.getString(addressIdx)
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date, address)?.let { info -> 
                        smsList.add(info)
                    }
                }
            }
        }
        // Store all found transactions
        if (smsList.isNotEmpty()) {
            storeTransactions(smsList)
        }
        // Return formatted strings for display
        return smsList.map { info ->
            "Amount: ${info.amount}\nStatus: ${info.status}\nAcBal: ${info.acBal ?: "-"}\nTime: ${info.time}"
        }
    }

    // Function to scan and store SMS transactions
    fun scanAndStoreTransactions(limit: Int = 50) {
        val transactionKeywords = listOf(
            "debited", "credited", "withdrawn", "deposited", "transferred", "transaction", "txn", "payment", "purchase", "spent", "paid", "received", "sent", "transfer"
        )
        val newTransactions = mutableListOf<TransactionInfo>()
        
        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER + " LIMIT $limit"
        )
        
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            
            while (it.moveToNext()) {
                val address = it.getString(addressIdx)
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date, address)?.let { info -> 
                        newTransactions.add(info)
                    }
                }
            }
        }
        
        // Store the new transactions
        if (newTransactions.isNotEmpty()) {
            storeTransactions(newTransactions)
            // Process with ChatGPT for summarization
            processTransactionsWithChatGpt(newTransactions)
        }
    }

    // Function to clear all stored transactions
    fun clearStoredTransactions() {
        try {
            val file = File(filesDir, "transactions.json")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateTransaction(updatedTransaction: TransactionInfo) {
        try {
            val transactions = getStoredTransactions().toMutableList()
            val index = transactions.indexOfFirst { it.id == updatedTransaction.id }
            if (index != -1) {
                transactions[index] = updatedTransaction
                saveAllTransactions(transactions)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteTransaction(transactionToDelete: TransactionInfo) {
        try {
            val transactions = getStoredTransactions().toMutableList()
            transactions.removeAll { it.id == transactionToDelete.id }
            saveAllTransactions(transactions)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Process existing transactions with ChatGPT (for manual triggering)
    fun processExistingTransactionsWithChatGpt() {
        val existingTransactions = getStoredTransactions()
        // Only process transactions that don't already have summaries
        val transactionsToProcess = existingTransactions.filter { it.summary.isBlank() }
        processTransactionsWithChatGpt(transactionsToProcess)
    }

    // Function to migrate existing transactions to include originalSmsText
    fun migrateExistingTransactions() {
        try {
            val existingTransactions = getStoredTransactions()
            Log.d("MainActivity", "Found ${existingTransactions.size} existing transactions to migrate")
            
            // Clear existing transactions since we can't recover original SMS text
            clearStoredTransactions()
            Log.d("MainActivity", "Cleared existing transactions for migration")
            
            // Re-scan SMS to get transactions with original SMS text
            scanAndStoreTransactions()
            Log.d("MainActivity", "Migration completed - re-scanned SMS for original text")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error during migration: ${e.message}", e)
        }
    }

    // Function to update home screen data (called from SMS receiver)
    fun updateHomeScreenData() {
        // This will trigger a recomposition of the home screen
        // The home screen will fetch fresh data when it becomes visible
        runOnUiThread {
            // Force a UI update by triggering a recomposition
            // The home screen will automatically refresh when the tab is selected
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        messages = mutableListOf()
        setContent {
            var showPermissionsDialog by remember { mutableStateOf(true) }
            val context = this
            val permissions = listOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA
            )
            val allPermissionsGranted = permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (showPermissionsDialog && !allPermissionsGranted) {
                PermissionsDialog(
                    onGrant = {
                        showPermissionsDialog = false
                        permissions.forEach { perm ->
                            when (perm) {
                                Manifest.permission.READ_SMS -> requestPermissionLauncher.launch(Manifest.permission.READ_SMS)
                                Manifest.permission.READ_EXTERNAL_STORAGE -> requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                Manifest.permission.CAMERA -> requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    },
                    onDismiss = { showPermissionsDialog = false }
                )
            } else {
                // Register SMS receiver for real-time updates
                val smsReceiver = SmsReceiver()
                val filter = android.content.IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                registerReceiver(smsReceiver, filter)
                // Check if existing transactions need migration
                val existingTransactions = getStoredTransactions()
                if (existingTransactions.isNotEmpty() && existingTransactions.any { it.originalSmsText.isEmpty() }) {
                    Log.d("MainActivity", "Found transactions without original SMS text, triggering migration")
                    migrateExistingTransactions()
                }
                // Ensure all transactions without a summary are sent to ChatGPT on app open
                processExistingTransactionsWithChatGpt()
                val messagesState = remember { mutableStateListOf<String>() }
                val loadingState = remember { mutableStateOf(false) }
                var selectedTab by remember { mutableStateOf(0) }
                val tabs = listOf("Home", "Stored Transactions")
                reloadMessages = {
                    loadingState.value = true
                    lifecycleScope.launch {
                        val sms = withContext(Dispatchers.IO) { getAllSms() }
                        messagesState.clear()
                        messagesState.addAll(sms)
                        loadingState.value = false
                        // After reload, process any new unsummarized transactions
                        processExistingTransactionsWithChatGpt()
                    }
                }
                // Automatically load messages on app start
                reloadMessages?.invoke()
                ExpenceTrackerTheme {
                    MainScreen(
                        selectedTab = selectedTab,
                        onTabSelected = { tabIdx ->
                            selectedTab = tabIdx
                            // Automatically scan messages when switching to any tab
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                                reloadMessages?.invoke()
                            } else {
                                reloadMessages = {
                                    loadingState.value = true
                                    lifecycleScope.launch {
                                        val sms = withContext(Dispatchers.IO) { getAllSms() }
                                        messagesState.clear()
                                        messagesState.addAll(sms)
                                        loadingState.value = false
                                        // After reload, process any new unsummarized transactions
                                        processExistingTransactionsWithChatGpt()
                                    }
                                }
                                requestPermissionLauncher.launch(Manifest.permission.READ_SMS)
                            }
                        },
                        messages = messagesState,
                        loading = loadingState.value
                    )
                    ImageSummaryDialog(
                        show = showImageSummaryDialog,
                        summary = imageSummaryText,
                        onDismiss = { showImageSummaryDialog = false }
                    )
                }
            }
        }
    }

    @Composable
    fun PermissionsDialog(onGrant: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Permissions Required") },
            text = {
                Text("This app needs SMS, Storage, and Camera permissions to function properly. Please grant all permissions.")
            },
            confirmButton = {
                Button(onClick = onGrant) { Text("Grant Permissions") }
            },
            dismissButton = {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        )
    }

    private fun checkManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:" + packageName)
                startActivity(intent)
            } else {
                // Permission already granted
            }
        } else {
            // For devices below Android 11, handle legacy permissions if needed
        }
    }

    private fun checkStoragePermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Storage permission is already granted
            }
            else -> {
                // Request the storage permission
                requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    }

    private fun checkSmsPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is already granted
            }
            else -> {
                // Request the permission
                requestPermissionLauncher.launch(Manifest.permission.READ_SMS)
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            // Camera permission already granted
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    expenseViewModel: ExpenseViewModel = viewModel(),
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    onScanClick: () -> Unit = {},
    messages: List<String> = emptyList(),
    loading: Boolean = false
) {
    val tabs = listOf("Home", "Scan", "Stored Transactions")
    val context = LocalContext.current
    var storedTransactions by remember {
        mutableStateOf((context as? MainActivity)?.getStoredTransactions()?.asReversed() ?: emptyList())
    }
    val refreshTransactions = {
        storedTransactions = (context as? MainActivity)?.getStoredTransactions()?.asReversed() ?: emptyList()
    }
    // Auto-refresh data when tab changes or when new data is available
    LaunchedEffect(selectedTab) {
        refreshTransactions()
    }
    // Periodically refresh data to catch new transactions
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000) // Refresh every 5 seconds
            refreshTransactions()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expense Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = when (index) {
                                    0 -> Icons.Filled.Home
                                    1 -> Icons.Filled.List
                                    2 -> Icons.Filled.List
                                    else -> Icons.Filled.Home
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> {
                    // Refresh data when Home tab is selected
                    val refreshedStoredTransactions = (context as? MainActivity)?.getStoredTransactions() ?: emptyList()
                    HomeScreen(expenseViewModel, refreshedStoredTransactions)
                }
                1 -> {
                    // Show ScanDialog for Scan tab
                    (context as? MainActivity)?.ScanDialog(
                        onCamera = { (context as? MainActivity)?.openCamera() },
                        onGallery = { (context as? MainActivity)?.openGallery() }
                    )
                }
                2 -> TransactionsJsonScreen(forceUpdate = true, onTransactionsUpdated = refreshTransactions)
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: ExpenseViewModel, storedTransactions: List<TransactionInfo>) {
    val context = LocalContext.current
    val expenses by viewModel.expenses.collectAsState()
    val storedBalance = storedTransactions.fold(0.0) { acc, transaction ->
        val amount = transaction.amount.replace(",", "").toDoubleOrNull() ?: 0.0
        acc + (if (transaction.status == "Credited") amount else -amount)
    }
    val expenseBalance = expenses.fold(0.0) { acc, expense ->
        when (expense.type) {
            ExpenseType.INCOME -> acc + expense.amount
            ExpenseType.EXPENSE -> acc - expense.amount
        }
    }
    val totalBalance = storedBalance + expenseBalance
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Total Balance",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "₹${String.format("%.2f", totalBalance)}",
                    style = MaterialTheme.typography.headlineLarge
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Recent Transactions",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Show both stored transactions and expenses
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        val allTransactions = storedTransactions.map { transaction ->
            Triple(
                transaction.time,
                if (transaction.summary.isNotBlank()) transaction.summary else "SMS Transaction",
                "${if (transaction.status == "Debited") "-" else "+"}₹${transaction.amount}"
            )
        } + expenses.map { expense ->
            Triple(
                dateFormat.format(expense.date),
                expense.description,
                "${if (expense.type == ExpenseType.EXPENSE) "-" else "+"}₹${String.format("%.2f", expense.amount)}"
            )
        }
        
        allTransactions.sortedByDescending { 
            try {
                dateFormat.parse(it.first)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }.take(5).forEach { (time, description, amount) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = time,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = amount,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (amount.startsWith("-"))
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun AddExpenseScreen(viewModel: ExpenseViewModel) {
    var amount by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextField(
            value = category,
            onValueChange = { category = it },
            label = { Text("Category") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = isExpense,
                onClick = { isExpense = true },
                label = { Text("Expense") }
            )
            FilterChip(
                selected = !isExpense,
                onClick = { isExpense = false },
                label = { Text("Income") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                amount.toDoubleOrNull()?.let { amountValue ->
                    viewModel.addExpense(
                        Expense(
                            amount = amountValue,
                            description = description,
                            category = category,
                            type = if (isExpense) ExpenseType.EXPENSE else ExpenseType.INCOME
                        )
                    )
                    amount = ""
                    description = ""
                    category = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Transaction")
        }
    }
}

@Composable
fun HistoryScreen(viewModel: ExpenseViewModel) {
    val expenses by viewModel.expenses.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        expenses.forEach { expense ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = expense.description,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = expense.category,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "${if (expense.type == ExpenseType.EXPENSE) "-" else "+"}$${String.format("%.2f", expense.amount)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (expense.type == ExpenseType.EXPENSE)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun MessageScreen(messages: List<String>, loading: Boolean) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Messages:", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            messages.forEach { msg ->
                Text(msg, style = MaterialTheme.typography.bodyMedium)
                Divider()
            }
        }
    }
}

@Composable
fun EditTransactionDialog(
    transaction: TransactionInfo,
    onDismiss: () -> Unit,
    onSave: (TransactionInfo) -> Unit
) {
    var amount by remember { mutableStateOf(transaction.amount) }
    var status by remember { mutableStateOf(transaction.status) }
    var summary by remember { mutableStateOf(transaction.summary) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Transaction") },
        text = {
            Column {
                TextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    RadioButton(
                        selected = status == "Credited",
                        onClick = { status = "Credited" }
                    )
                    Text("Credited", modifier = Modifier.align(Alignment.CenterVertically))
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(
                        selected = status == "Debited",
                        onClick = { status = "Debited" }
                    )
                    Text("Debited", modifier = Modifier.align(Alignment.CenterVertically))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Summary") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(transaction.copy(amount = amount, status = status, summary = summary))
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun TransactionsJsonScreen(forceUpdate: Boolean = false, onTransactionsUpdated: () -> Unit) {
    val context = LocalContext.current
    var transactions by remember(forceUpdate) {
        mutableStateOf(
            (context as? MainActivity)?.getStoredTransactions()?.asReversed() ?: emptyList()
        )
    }
    var editingTransaction by remember { mutableStateOf<TransactionInfo?>(null) }
    val refreshTransactions = {
        transactions = (context as? MainActivity)?.getStoredTransactions()?.asReversed() ?: emptyList()
    }
    if (editingTransaction != null) {
        EditTransactionDialog(
            transaction = editingTransaction!!,
            onDismiss = { editingTransaction = null },
            onSave = { updatedTransaction ->
                (context as? MainActivity)?.updateTransaction(updatedTransaction)
                editingTransaction = null
                refreshTransactions()
                onTransactionsUpdated()
            }
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Stored Transactions",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        if (transactions.isEmpty()) {
            Text(
                "No transactions found",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            transactions.forEach { transaction ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "₹${transaction.amount}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (transaction.status == "Credited") 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.error
                            )
                            Row {
                                IconButton(onClick = { editingTransaction = transaction }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = {
                                    (context as? MainActivity)?.deleteTransaction(transaction)
                                    refreshTransactions()
                                    onTransactionsUpdated()
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                        Text(
                            text = transaction.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (transaction.status == "Credited")
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Time: ${transaction.time}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (transaction.acBal != null) {
                            Text(
                                text = "Balance: ₹${transaction.acBal}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (transaction.summary.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Summary: ${transaction.summary}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ExpenceTrackerTheme {
        MainScreen()
    }
}

// Example template for your SmsReceiver:
// class SmsReceiver : BroadcastReceiver() {
//     override fun onReceive(context: Context, intent: Intent) {
//         // Parse the SMS from the intent, extract body, date, address
//         // val transaction = (context as? MainActivity)?.parseTransactionSms(body, date, address)
//         // if (transaction != null) {
//         //     (context as? MainActivity)?.storeTransactions(listOf(transaction))
//         //     (context as? MainActivity)?.processTransactionWithChatGpt(transaction)
//         // }
//     }
// }
