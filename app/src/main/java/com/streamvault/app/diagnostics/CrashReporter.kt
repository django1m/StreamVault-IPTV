package com.streamvault.app.diagnostics

import android.content.Context
import com.streamvault.app.BuildConfig
import com.streamvault.data.repository.DiagnosticsRepository
import com.streamvault.domain.model.DiagnosticCategory
import com.streamvault.domain.model.DiagnosticDraft
import com.streamvault.domain.model.DiagnosticSeverity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures uncaught exceptions and reports them per device.
 *
 * The crash is persisted to a plain file SYNCHRONOUSLY inside the dying
 * process (Room is suspend-based and can't be used from a crash handler), then
 * the default handler is invoked so the OS still tears the process down
 * normally. On the next launch [drainPendingCrashes] moves the files into the
 * diagnostics upload queue.
 */
@Singleton
class CrashReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: DiagnosticsRepository,
) {
    private val dir: File
        get() = File(context.filesDir, "diagnostics/crashes").apply { mkdirs() }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun persist(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val ts = System.currentTimeMillis()
        val header = buildString {
            append("thread=").append(thread.name).append('\n')
            append("version=").append(BuildConfig.VERSION_NAME)
            append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        }
        File(dir, "crash_$ts.log").writeText(header + sw.toString())
    }

    /** Move persisted crash files into the upload queue. Safe to call repeatedly. */
    suspend fun drainPendingCrashes() {
        val files = dir.listFiles()?.filter { it.name.startsWith("crash_") } ?: return
        for (file in files) {
            runCatching {
                val ts = file.name.removePrefix("crash_").removeSuffix(".log").toLongOrNull()
                    ?: System.currentTimeMillis()
                val text = file.readText()
                val firstLine = text.lineSequence()
                    .firstOrNull { it.contains("Exception") || it.contains("Error") }
                    ?.trim()
                    ?: "cause inconnue"
                val message = "L'application a planté : ${firstLine.take(220)}"
                repository.enqueue(
                    DiagnosticDraft(
                        severity = DiagnosticSeverity.CRASH,
                        category = DiagnosticCategory.CRASH,
                        message = message,
                        context = mapOf("détail" to text.take(800)),
                        occurredAtMs = ts,
                    )
                )
            }
            file.delete()
        }
    }
}
