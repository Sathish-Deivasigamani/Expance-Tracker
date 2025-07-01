package com.example.expencetracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            Log.d("SmsReceiver", "New SMS received")
            
            val bundle: Bundle? = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as? Array<*>
                if (pdus != null) {
                    for (pdu in pdus) {
                        val smsMessages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                        for (message in smsMessages) {
                            val body = message.messageBody
                            val address = message.originatingAddress
                            val date = message.timestampMillis
                            
                            Log.d("SmsReceiver", "Processing SMS: $body")
                            
                            // Check if this is a transaction SMS
                            val transactionKeywords = listOf(
                                "debited", "credited", "withdrawn", "deposited", "transferred", 
                                "transaction", "txn", "payment", "purchase", "spent", "paid", 
                                "received", "sent", "transfer"
                            )
                            
                            if (transactionKeywords.any { keyword -> 
                                body.contains(keyword, ignoreCase = true) 
                            }) {
                                Log.d("SmsReceiver", "Transaction SMS detected")
                                
                                val mainActivity = context as? MainActivity
                                if (mainActivity != null) {
                                    val transaction = mainActivity.parseTransactionSms(body, date, address)
                                    if (transaction != null) {
                                        Log.d("SmsReceiver", "Transaction parsed: ₹${transaction.amount} ${transaction.status}")
                                        
                                        // Store the transaction
                                        mainActivity.storeTransactions(listOf(transaction))
                                        
                                        // Process with ChatGPT
                                        mainActivity.processTransactionWithChatGpt(transaction)
                                        
                                        // Trigger UI update
                                        mainActivity.updateHomeScreenData()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
} 