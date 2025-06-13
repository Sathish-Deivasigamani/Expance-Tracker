package com.example.expencetracker.data

import java.util.Date

data class Expense(
    val id: String = "",
    val amount: Double,
    val description: String,
    val category: String,
    val date: Date = Date(),
    val type: ExpenseType
)

enum class ExpenseType {
    INCOME,
    EXPENSE
} 