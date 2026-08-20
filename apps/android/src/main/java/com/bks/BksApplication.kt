package com.bks

import android.app.Application
import com.bks.feature.diagnostics.DiagnosticComponent
import com.bks.feature.diagnostics.DiagnosticEvent
import com.bks.feature.diagnostics.DiagnosticEventMetadata
import com.bks.feature.diagnostics.DiagnosticLevel
import com.bks.feature.diagnostics.DiagnosticLogs
import com.bks.feature.diagnostics.DiagnosticSensitiveField
import com.bks.feature.diagnostics.DiagnosticSensitivePayload
import com.bks.feature.diagnostics.DiagnosticSource
import com.bks.feature.diagnostics.toDiagnosticExceptionDetails

class BksApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installDiagnosticCrashHandler()
    }

    private fun installDiagnosticCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DiagnosticLogs.get(this@BksApplication).record(
                    DiagnosticEvent(
                        metadata = DiagnosticEventMetadata(
                            level = DiagnosticLevel.Error,
                            component = DiagnosticComponent.Application,
                            event = "uncaught_exception",
                            source = DiagnosticSource.System,
                            outcome = "failed",
                            reason = "uncaught_exception"
                        ),
                        sensitivePayload = DiagnosticSensitivePayload(
                            mapOf(
                                DiagnosticSensitiveField.ExceptionDetails to
                                    "thread=${thread.name}\n${throwable.toDiagnosticExceptionDetails()}"
                            )
                        )
                    )
                )
            }
            original?.uncaughtException(thread, throwable)
        }
    }
}
