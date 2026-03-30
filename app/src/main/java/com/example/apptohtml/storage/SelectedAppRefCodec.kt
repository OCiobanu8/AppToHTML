package com.example.apptohtml.storage

import com.example.apptohtml.model.SelectedAppRef

object SelectedAppRefCodec {
    private const val SEP = "|"

    fun encode(ref: SelectedAppRef): String {
        return listOf(
            escape(ref.packageName),
            escape(ref.appName),
            escape(ref.launcherActivity),
            ref.selectedAt.toString(),
        ).joinToString(SEP)
    }

    fun decode(value: String): SelectedAppRef? {
        val parts = splitEscaped(value)
        if (parts.size != 4) return null
        val timestamp = parts[3].toLongOrNull() ?: return null
        return SelectedAppRef(
            packageName = unescape(parts[0]),
            appName = unescape(parts[1]),
            launcherActivity = unescape(parts[2]),
            selectedAt = timestamp,
        )
    }

    private fun escape(input: String): String = input
        .replace("\\", "\\\\")
        .replace(SEP, "\\$SEP")

    private fun unescape(input: String): String {
        val output = StringBuilder()
        var escaped = false
        input.forEach { char ->
            if (escaped) {
                output.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else {
                output.append(char)
            }
        }
        return output.toString()
    }

    private fun splitEscaped(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        input.forEach { char ->
            if (escaped) {
                current.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '|') {
                result.add(current.toString())
                current.clear()
            } else {
                current.append(char)
            }
        }
        result.add(current.toString())
        return result
    }
}
