package com.example.apptohtml.crawler

import android.content.Context
import java.io.File

data class CapturedScreenFiles(
    val htmlFile: File,
    val xmlFile: File,
)

object CaptureFileStore {
    fun save(context: Context, snapshot: ScreenSnapshot): CapturedScreenFiles {
        val baseDir = preferredBaseDir(context, snapshot.packageName)
        preparePackageDirectory(baseDir)
        val baseName = ScreenNaming.toFileBase(snapshot.screenName)
        val htmlFile = File(baseDir, "$baseName.html")
        val xmlFile = File(baseDir, "$baseName.xml")

        htmlFile.writeText(HtmlRenderer.render(snapshot), Charsets.UTF_8)
        xmlFile.writeText(snapshot.xmlDump, Charsets.UTF_8)

        return CapturedScreenFiles(
            htmlFile = htmlFile,
            xmlFile = xmlFile,
        )
    }

    internal fun preparePackageDirectory(baseDir: File) {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
            return
        }

        baseDir.listFiles().orEmpty().forEach { file ->
            if (!file.delete()) {
                throw IllegalStateException("Could not delete previous capture file: ${file.absolutePath}")
            }
        }
    }

    private fun preferredBaseDir(context: Context, packageName: String): File {
        val externalRoot = context.getExternalFilesDir(null)
        val root = externalRoot ?: context.filesDir
        return File(root, "html/$packageName")
    }
}
