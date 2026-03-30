package com.example.apptohtml

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

object AppDiscovery {
    fun queryLauncherApps(packageManager: PackageManager, excludePackageName: String): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { info ->
                InstalledApp(
                    appName = info.loadLabel(packageManager).toString(),
                    packageName = info.activityInfo.packageName,
                    launcherActivity = info.activityInfo.name,
                )
            }
            .filter { it.packageName != excludePackageName }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    fun accessibilityServiceComponentName(packageName: String): String {
        return ComponentName(packageName, AppToHtmlAccessibilityService::class.java.name).flattenToString()
    }

    fun isAccessibilityServiceEnabled(context: Context, packageName: String): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        return enabledServices?.contains(accessibilityServiceComponentName(packageName)) == true
    }
}
