package com.aquascope.report

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportPdfExporter {

    data class ExportResult(
        val cacheFile: File,
        val downloadsUri: Uri?,
        val displayName: String
    )

    fun fileName(report: SessionReport): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ENGLISH).format(Date(report.generatedAtMs))
        val loc = report.locationLabel
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "scan" }
        return "AquaScope_Report_${loc}_$stamp.pdf"
    }

    fun export(context: Context, report: SessionReport): ExportResult {
        val name = fileName(report)
        val dir = File(context.cacheDir, "reports").also { it.mkdirs() }
        val cacheFile = File(dir, name)
        PdfSessionReportWriter.write(report, cacheFile)

        val downloadsUri = saveToDownloads(context, cacheFile, name)
        return ExportResult(cacheFile, downloadsUri, name)
    }

    private fun saveToDownloads(context: Context, file: File, displayName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AquaScope")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val uri = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            uri
        } catch (_: Exception) {
            null
        }
    }

    fun shareIntent(context: Context, file: File, report: SessionReport): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "AquaScope inspection report — ${report.locationLabel}"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                IndianComplianceGuidelines.executiveSummary(report)
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
        }
    }

    fun viewIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
