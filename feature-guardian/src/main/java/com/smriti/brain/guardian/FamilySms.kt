package com.smriti.brain.guardian

import android.content.Context
import android.content.Intent
import android.net.Uri

class FamilySms(private val context: Context) {
    fun compose(number: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
