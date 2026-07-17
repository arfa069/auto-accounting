package com.autoaccounting

import android.app.Application
import com.autoaccounting.feature.diagnostics.DiagnosticComponent
import com.autoaccounting.feature.diagnostics.DiagnosticEvent
import com.autoaccounting.feature.diagnostics.DiagnosticEventMetadata
import com.autoaccounting.feature.diagnostics.DiagnosticLevel
import com.autoaccounting.feature.diagnostics.DiagnosticLogs
import com.autoaccounting.feature.diagnostics.DiagnosticSensitiveField
import com.autoaccounting.feature.diagnostics.DiagnosticSensitivePayload
import com.autoaccounting.feature.diagnostics.DiagnosticSource
import com.autoaccounting.feature.diagnostics.toDiagnosticExceptionDetails

class AutoAccountingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installDiagnosticCrashHandler()
    }

    private fun installDiagnosticCrashHandler() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DiagnosticLogs.get(this@AutoAccountingApplication).record(
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
