package com.example.drowsinessdetection

import android.content.Context
import android.content.SharedPreferences
import android.telephony.SmsManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object Utils {
    private const val PREFS_NAME = "DrowsinessDetectionPrefs"
    private const val CONTACTS_KEY = "emergency_contacts"
    
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun saveContacts(context: Context, contacts: List<Contact>) {
        val prefs = getSharedPreferences(context)
        val gson = Gson()
        val json = gson.toJson(contacts)
        prefs.edit().putString(CONTACTS_KEY, json).apply()
    }
    
    fun getContacts(context: Context): List<Contact> {
        val prefs = getSharedPreferences(context)
        val gson = Gson()
        val json = prefs.getString(CONTACTS_KEY, null) ?: return emptyList()
        
        val type = object : TypeToken<List<Contact>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("Utils", "Error parsing contacts", e)
            emptyList()
        }
    }
    
    fun sendSMS(phoneNumber: String, message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            
            // Split message if it's too long
            val messageParts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, messageParts, null, null)
            
            Log.d("Utils", "SMS sent to $phoneNumber")
        } catch (e: Exception) {
            Log.e("Utils", "Failed to send SMS", e)
            throw e
        }
    }
    
    // Helper function to format timestamps in a user-friendly way
    fun formatTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    
    // Helper to get location info - simplified in this implementation
    fun getLocationInfo(): String {
        // In a full implementation, this would get GPS coordinates and possibly reverse geocode
        return "Location information unavailable"
    }
}