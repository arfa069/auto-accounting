package com.autoaccounting.feature.diagnostics

import android.content.Context
import android.util.Log
import com.autoaccounting.BuildConfig
import com.autoaccounting.data.crypto.PassphraseAesGcm
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidDiagnosticLogRepository internal constructor(
    context: Context,
    private val store: DiagnosticEncryptedStore = DiagnosticEncryptedStore(
        directory = File(context.noBackupFilesDir, DIAGNOSTICS_DIRECTORY),
        cipher = AndroidKeystoreDiagnosticCipher()
    ),
    private val isDebugBuild: Boolean = BuildConfig.DEBUG,
    private val buildDefaultEnabled: Boolean = isDebugBuild,
    private val clock: () -> Long = System::currentTimeMillis
) : DiagnosticLogRepository {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val clearGeneration = AtomicLong(0)
    private val coalesced = ConcurrentHashMap<CoalesceKey, CoalesceState>()
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, buildDefaultEnabled))
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private val _stats = MutableStateFlow(DiagnosticLogStats())

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()
    override val stats: StateFlow<DiagnosticLogStats> = _stats.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    override fun setEnabled(enabled: Boolean, userConfirmed: Boolean): Boolean {
        if (enabled && !isDebugBuild && !userConfirmed) return false
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        return true
    }

    override fun record(event: DiagnosticEvent) {
        if (!_enabled.value) return
        val expectedGeneration = clearGeneration.get()
        scope.launch {
            runCatching { recordNow(event, expectedGeneration) }
                .onFailure { logFixedFailure("diagnostic_write_failed") }
        }
    }

    internal suspend fun recordNow(event: DiagnosticEvent) {
        recordNow(event, clearGeneration.get())
    }

    private suspend fun recordNow(event: DiagnosticEvent, expectedGeneration: Long) {
        if (!_enabled.value) return
        val sanitized = sanitizeDiagnosticEvent(event)
        mutex.withLock {
            if (!_enabled.value || expectedGeneration != clearGeneration.get()) return@withLock
            val key = CoalesceKey(
                sanitized.metadata.component,
                sanitized.metadata.source,
                sanitized.metadata.reason ?: sanitized.metadata.event
            )
            val previous = coalesced[key]
            val now = clock()
            if (previous != null) {
                if (now - previous.firstTimestamp < COALESCE_WINDOW_MILLIS) {
                    previous.suppressedCount += 1
                    if (previous.flushJob == null) {
                        previous.flushJob = scheduleSuppressedFlush(key, previous)
                    }
                    return@withLock
                }
                previous.flushJob?.cancel()
                coalesced.remove(key)
                appendSuppressedLocked(previous)
            }
            appendLocked(sanitized)
            coalesced[key] = CoalesceState(
                firstTimestamp = now,
                template = sanitized
            )
        }
    }

    override suspend fun refresh(limit: Int) {
        mutex.withLock {
            flushSuppressedLocked()
            refreshLocked(limit)
        }
    }

    override suspend fun clear(keepEnabledPreference: Boolean) {
        clearGeneration.incrementAndGet()
        mutex.withLock {
            coalesced.values.forEach { it.flushJob?.cancel() }
            coalesced.clear()
            store.clear()
            _events.value = emptyList()
            _stats.value = DiagnosticLogStats()
            if (!keepEnabledPreference) {
                preferences.edit().remove(KEY_ENABLED).apply()
                _enabled.value = buildDefaultEnabled
            }
        }
    }

    override suspend fun exportEncrypted(passphrase: CharArray): String {
        require(passphrase.size >= MIN_EXPORT_PASSPHRASE_LENGTH) {
            "Diagnostic export passphrase must contain at least 8 characters"
        }
        return mutex.withLock {
            flushSuppressedLocked()
            val jsonLines = store.readAll().joinToString(separator = "\n", postfix = "\n") {
                DiagnosticEventCodec.encode(it)
            }
            DIAGNOSTICS_EXPORT_PREFIX + Base64.getEncoder().encodeToString(
                PassphraseAesGcm.encrypt(jsonLines.toByteArray(Charsets.UTF_8), passphrase)
            )
        }
    }

    private fun scheduleSuppressedFlush(key: CoalesceKey, state: CoalesceState): Job = scope.launch {
        delay(COALESCE_WINDOW_MILLIS)
        mutex.withLock {
            if (coalesced[key] !== state) return@withLock
            coalesced.remove(key)
            appendSuppressedLocked(state)
        }
    }

    private fun flushSuppressedLocked() {
        val states = coalesced.values.toList()
        coalesced.clear()
        states.forEach {
            it.flushJob?.cancel()
            appendSuppressedLocked(it)
        }
    }

    private fun appendSuppressedLocked(state: CoalesceState) {
        if (state.suppressedCount <= 0) return
        appendLocked(
            state.template.copy(
                metadata = state.template.metadata.copy(
                    timestampEpochMillis = clock(),
                    suppressedCount = state.suppressedCount
                ),
                sensitivePayload = DiagnosticSensitivePayload(),
                truncatedFields = emptySet()
            )
        )
    }

    private fun appendLocked(event: DiagnosticEvent) {
        store.append(event)
        logMetadata(event.metadata)
        refreshLocked(1_000)
    }

    private fun refreshLocked(limit: Int) {
        val all = store.readAll()
        _events.value = all.takeLast(limit).asReversed()
        _stats.value = DiagnosticLogStats(
            eventCount = all.size,
            encryptedBytes = store.encryptedBytes(),
            segmentCount = store.segmentCount()
        )
    }

    private fun logMetadata(metadata: DiagnosticEventMetadata) {
        val safeReason = metadata.reason
            ?.replace(Regex("[\\r\\n]"), "_")
            ?.take(80)
        Log.i(
            LOG_TAG,
            "component=${metadata.component.name} event=${metadata.event.take(60)} " +
                "traceId=${metadata.traceId} sessionId=${metadata.sessionId.orEmpty()} " +
                "source=${metadata.source.name} outcome=${metadata.outcome.orEmpty()} " +
                "reason=${safeReason.orEmpty()} count=${metadata.count ?: 0} " +
                "suppressed=${metadata.suppressedCount} durationMs=${metadata.durationMillis ?: 0}"
        )
    }

    private data class CoalesceKey(
        val component: DiagnosticComponent,
        val source: DiagnosticSource,
        val reason: String
    )

    private data class CoalesceState(
        val firstTimestamp: Long,
        val template: DiagnosticEvent,
        var suppressedCount: Int = 0,
        var flushJob: Job? = null
    )

    private companion object {
        const val DIAGNOSTICS_DIRECTORY = "diagnostics"
        const val PREFERENCES_NAME = "diagnostic_log_preferences"
        const val KEY_ENABLED = "enabled"
        const val COALESCE_WINDOW_MILLIS = 5_000L
        const val LOG_TAG = "AutoAccountingDiag"
    }
}

object DiagnosticLogs {
    @Volatile
    private var repository: AndroidDiagnosticLogRepository? = null

    fun get(context: Context): AndroidDiagnosticLogRepository = repository ?: synchronized(this) {
        repository ?: AndroidDiagnosticLogRepository(context.applicationContext).also { repository = it }
    }
}

internal fun decryptDiagnosticExport(exportText: String, passphrase: CharArray): String {
    require(exportText.startsWith(DIAGNOSTICS_EXPORT_PREFIX)) { "Unsupported diagnostic export" }
    val encrypted = Base64.getDecoder().decode(exportText.removePrefix(DIAGNOSTICS_EXPORT_PREFIX))
    return PassphraseAesGcm.decrypt(encrypted, passphrase).toString(Charsets.UTF_8)
}

private fun logFixedFailure(code: String) {
    Log.e("AutoAccountingDiag", code)
}

const val DIAGNOSTICS_EXPORT_PREFIX = "AUTO_ACCOUNTING_DIAGNOSTICS_V1:"
const val DIAGNOSTICS_EXPORT_EXTENSION = "aadiag"
const val MIN_EXPORT_PASSPHRASE_LENGTH = 8
