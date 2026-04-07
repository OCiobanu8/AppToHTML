package com.example.apptohtml.crawler

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.apptohtml.model.SelectedAppRef

data class AppLaunchResult(
    val alreadyRunning: Boolean,
    val errorMessage: String? = null,
)

object AppLaunchHelper {
    fun launchSelectedApp(
        context: Context,
        selectedApp: SelectedAppRef,
        lastObservedPackage: String?,
    ): AppLaunchResult {
        val launchIntent = buildLaunchIntent(context, selectedApp)
            ?: return AppLaunchResult(
                alreadyRunning = false,
                errorMessage = "Could not resolve a launcher activity for ${selectedApp.appName}.",
            )

        val alreadyRunning = appearsToBeRunning(
            context = context,
            packageName = selectedApp.packageName,
            lastObservedPackage = lastObservedPackage,
        )

        return runCatching {
            context.startActivity(launchIntent)
            AppLaunchResult(alreadyRunning = alreadyRunning)
        }.getOrElse { error ->
            AppLaunchResult(
                alreadyRunning = alreadyRunning,
                errorMessage = "Failed to launch ${selectedApp.appName}: ${error.message ?: "unknown error"}.",
            )
        }
    }

    fun appearsToBeRunning(
        context: Context,
        packageName: String,
        lastObservedPackage: String?,
    ): Boolean {
        if (lastObservedPackage == packageName) {
            return true
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false

        return activityManager.runningAppProcesses.orEmpty().any { process ->
            process.processName == packageName ||
                process.processName.startsWith("$packageName:") ||
                process.pkgList?.contains(packageName) == true
        }
    }

    private fun buildLaunchIntent(context: Context, selectedApp: SelectedAppRef): Intent? {
        val explicitIntent = Intent.makeRestartActivityTask(
            ComponentName(
                selectedApp.packageName,
                normalizeActivityName(selectedApp.packageName, selectedApp.launcherActivity),
            )
        ).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        if (explicitIntent.resolveActivity(context.packageManager) != null) {
            return explicitIntent
        }

        return context.packageManager.getLaunchIntentForPackage(selectedApp.packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
    }

    private fun normalizeActivityName(packageName: String, activityName: String): String {
        return if (activityName.startsWith(".")) {
            packageName + activityName
        } else {
            activityName
        }
    }
}
