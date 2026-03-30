package com.example.apptohtml.crawler

import android.content.Context
import android.content.Intent
import com.example.apptohtml.MainActivity

object AppToHtmlNavigator {
    fun returnToApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        context.startActivity(intent)
    }
}
