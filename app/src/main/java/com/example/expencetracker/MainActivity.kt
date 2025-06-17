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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expencetracker.data.Expense
import com.example.expencetracker.data.ExpenseType
import com.example.expencetracker.ui.theme.ExpenceTrackerTheme
import com.example.expencetracker.viewmodel.ExpenseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.platform.LocalContext

data class TransactionInfo(
    val amount: String,
    val status: String, // "Credited" or "Debited"
    val acBal: String?,
    val time: String
)

class MainActivity : ComponentActivity() {
    private lateinit var messages: MutableList<String>
    private var reloadMessages: (() -> Unit)? = null

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

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val imageBitmap = result.data?.extras?.get("data") as? Bitmap
            // Handle the captured imageBitmap here (e.g., show in UI or save)
        }
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            takePictureLauncher.launch(cameraIntent)
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun parseTransactionSms(body: String, date: Long): TransactionInfo? {
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
        return TransactionInfo(amount, status, acBal, time)
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

    // Function to store transactions
    private fun storeTransactions(transactions: List<TransactionInfo>) {
        try {
            val file = File(filesDir, "transactions.json")
            val existingTransactions = getStoredTransactions().toMutableList()
            
            // Add new transactions, avoiding duplicates
            transactions.forEach { newTransaction ->
                val isDuplicate = existingTransactions.any { existing ->
                    existing.amount == newTransaction.amount &&
                    existing.status == newTransaction.status &&
                    existing.time == newTransaction.time
                }
                if (!isDuplicate) {
                    existingTransactions.add(newTransaction)
                }
            }
            
            // Save all transactions
            val jsonArray = JSONArray()
            existingTransactions.forEach { info ->
                val obj = JSONObject()
                obj.put("amount", info.amount)
                obj.put("status", info.status)
                obj.put("acBal", info.acBal)
                obj.put("time", info.time)
                jsonArray.put(obj)
            }
            
            // Write to file with proper error handling
            try {
                file.writeText(jsonArray.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                    amount = obj.getString("amount"),
                    status = obj.getString("status"),
                    acBal = obj.optString("acBal", null),
                    time = obj.getString("time")
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

    // Modified getAllSmsAndSave to use the new storage system
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
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date)?.let { info -> 
                        smsList.add(info)
                        // Store each transaction immediately after parsing
                        storeTransactions(listOf(info))
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
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date)?.let { info -> smsList.add(info) }
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
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date)?.let { info -> smsList.add(info) }
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
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date)?.let { info -> 
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
                val body = it.getString(bodyIdx)
                val date = it.getLong(dateIdx)
                
                if (transactionKeywords.any { keyword -> body.contains(keyword, ignoreCase = true) }) {
                    parseTransactionSms(body, date)?.let { info -> 
                        newTransactions.add(info)
                    }
                }
            }
        }
        
        // Store the new transactions
        if (newTransactions.isNotEmpty()) {
            storeTransactions(newTransactions)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkManageExternalStoragePermission()
        checkStoragePermission()
        checkSmsPermission()
        checkCameraPermission()
        enableEdgeToEdge()
        messages = mutableListOf()
        setContent {
            val messagesState = remember { mutableStateListOf<String>() }
            val loadingState = remember { mutableStateOf(false) }
            var selectedTab by remember { mutableStateOf(0) }
            reloadMessages = {
                loadingState.value = true
                lifecycleScope.launch {
                    val sms = withContext(Dispatchers.IO) { getAllSms() }
                    messagesState.clear()
                    messagesState.addAll(sms)
                    loadingState.value = false
                }
            }
            // Automatically load messages on app start
            reloadMessages?.invoke()
            ExpenceTrackerTheme {
                MainScreen(
                    selectedTab = selectedTab,
                    onTabSelected = { tabIdx ->
                        selectedTab = tabIdx
                        if (tabIdx == 1) {
                            // Message tab
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
                                    }
                                }
                                requestPermissionLauncher.launch(Manifest.permission.READ_SMS)
                            }
                        }
                    },
                    messages = messagesState,
                    loading = loadingState.value
                )
            }
        }
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
    val tabs = listOf("Home", "Re-Scan", "Stored Transactions")

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
                        label = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> HomeScreen(expenseViewModel)
                1 -> MessageScreen(messages, loading)
                2 -> TransactionsJsonScreen(forceUpdate = true)
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: ExpenseViewModel) {
    val context = LocalContext.current
    val storedTransactions = remember {
        val activity = context as? MainActivity
        activity?.getStoredTransactions() ?: emptyList()
    }
    
    // Calculate balance from both expenses and stored transactions
    val storedBalance = storedTransactions.fold(0.0) { acc, transaction ->
        val amount = transaction.amount.replace(",", "").toDoubleOrNull() ?: 0.0
        acc + (if (transaction.status == "Credited") amount else -amount)
    }
    val expenseBalance by remember { derivedStateOf { viewModel.getTotalBalance() } }
    val totalBalance = storedBalance + expenseBalance

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
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
                "SMS Transaction",
                "${if (transaction.status == "Debited") "-" else "+"}₹${transaction.amount}"
            )
        } + viewModel.expenses.value.map { expense ->
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
fun TransactionsJsonScreen(forceUpdate: Boolean = false) {
    val context = LocalContext.current
    val transactions = remember(forceUpdate) {
        val activity = context as? MainActivity
        activity?.getStoredTransactions() ?: emptyList()
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
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "₹${transaction.amount}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (transaction.status == "Credited") 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = transaction.status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (transaction.status == "Credited") 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.error
                            )
                        }
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
