
package com.ultron.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri

object PhoneController {

    fun prepareCall(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false

        return try {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(phoneNumber)}")
            )

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
