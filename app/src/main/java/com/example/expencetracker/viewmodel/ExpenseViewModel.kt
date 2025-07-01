package com.example.expencetracker.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.expencetracker.data.Expense
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ExpenseViewModel : ViewModel() {
    private val _expenses = MutableStateFlow<List<Expense>>(emptyList())
    val expenses: StateFlow<List<Expense>> = _expenses.asStateFlow()

    fun addExpense(expense: Expense) {
        viewModelScope.launch {
            val newExpense = expense.copy(id = UUID.randomUUID().toString())
            _expenses.value = _expenses.value + newExpense
        }
    }

    fun deleteExpense(expenseId: String) {
        viewModelScope.launch {
            _expenses.value = _expenses.value.filter { it.id != expenseId }
        }
    }

    fun getTotalBalance(): Double {
        return _expenses.value.fold(0.0) { acc, expense ->
            when (expense.type) {
                com.example.expencetracker.data.ExpenseType.INCOME -> acc + expense.amount
                com.example.expencetracker.data.ExpenseType.EXPENSE -> acc - expense.amount
            }
        }
    }
} 