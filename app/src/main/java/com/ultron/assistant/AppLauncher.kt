
package com.ultron.assistant

import android.content.Context
import android.content.Intent

object AppLauncher {

    fun launch(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun availableApps(context: Context): List<String> {
        return context.packageManager
            .getInstalledApplications(0)
            .filter {
                context.packageManager.getLaunchIntentForPackage(
                    it.packageName
                ) != null
            }
            .map { it.packageName }
            .sorted()
    }
}
