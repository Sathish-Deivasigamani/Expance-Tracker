package com.example.expencetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.expencetracker.data.Expense
import com.example.expencetracker.data.ExpenseType
import com.example.expencetracker.ui.theme.ExpenceTrackerTheme
import com.example.expencetracker.viewmodel.ExpenseViewModel

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, you can now read SMS
        } else {
            // Permission denied, handle accordingly
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkSmsPermission()
        enableEdgeToEdge()
        setContent {
            ExpenceTrackerTheme {
                MainScreen()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(expenseViewModel: ExpenseViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Home", "Add", "History")

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
                                    0 -> Icons.Default.Home
                                    1 -> Icons.Default.Add
                                    else -> Icons.Default.List
                                },
                                contentDescription = title
                            )
                        },
                        label = { Text(title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> HomeScreen(expenseViewModel)
                1 -> AddExpenseScreen(expenseViewModel)
                2 -> HistoryScreen(expenseViewModel)
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: ExpenseViewModel) {
    val balance by remember { derivedStateOf { viewModel.getTotalBalance() } }

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
                    text = "$${String.format("%.2f", balance)}",
                    style = MaterialTheme.typography.headlineLarge
                )
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

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    ExpenceTrackerTheme {
        MainScreen()
    }
}
