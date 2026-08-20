package com.bks.feature.settings

import com.bks.data.crypto.PassphraseAesGcm
import java.util.Base64

internal fun encryptPersistedLocalData(
    snapshot: PersistedLocalDataSnapshot,
    passphrase: String
): String {
    require(isValidNewBackupPassphrase(passphrase)) {
        "Backup passphrase must be longer than 8 characters"
    }
    val plainText = snapshot.toBytes()
    val passphraseChars = passphrase.toCharArray()
    return try {
        BACKUP_PREFIX_V5 + Base64.getEncoder().encodeToString(
            PassphraseAesGcm.encrypt(plainText, passphraseChars)
        )
    } finally {
        passphraseChars.fill('\u0000')
    }
}

internal fun decryptPersistedLocalData(
    backupText: String,
    passphrase: String
): PersistedLocalDataSnapshot {
    require(passphrase.isNotBlank()) { "Backup passphrase is required" }
    val prefix = when {
        backupText.startsWith(BACKUP_PREFIX_V5) -> BACKUP_PREFIX_V5
        backupText.startsWith(BACKUP_PREFIX_V4) -> BACKUP_PREFIX_V4
        backupText.startsWith(BACKUP_PREFIX_V3) -> BACKUP_PREFIX_V3
        backupText.startsWith(BACKUP_PREFIX_V2) -> BACKUP_PREFIX_V2
        else -> error("Unsupported backup format")
    }
    val bytes = Base64.getDecoder().decode(backupText.removePrefix(prefix))
    val passphraseChars = passphrase.toCharArray()
    return try {
        snapshotFromBytes(PassphraseAesGcm.decrypt(bytes, passphraseChars))
    } finally {
        passphraseChars.fill('\u0000')
    }
}

internal fun isEncryptedLocalDataBackup(backupText: String): Boolean =
    backupText.startsWith(BACKUP_PREFIX_V2) ||
        backupText.startsWith(BACKUP_PREFIX_V3) ||
        backupText.startsWith(BACKUP_PREFIX_V4) ||
        backupText.startsWith(BACKUP_PREFIX_V5)

internal const val MIN_BACKUP_PASSPHRASE_LENGTH = 9

internal fun isValidNewBackupPassphrase(passphrase: String): Boolean =
    passphrase.isNotBlank() && passphrase.length >= MIN_BACKUP_PASSPHRASE_LENGTH

internal const val BACKUP_PREFIX_V2 = "AUTO_ACCOUNTING_BACKUP_V2:"
internal const val BACKUP_PREFIX_V3 = "AUTO_ACCOUNTING_BACKUP_V3:"
internal const val BACKUP_PREFIX_V4 = "AUTO_ACCOUNTING_BACKUP_V4:"
internal const val BACKUP_PREFIX_V5 = "AUTO_ACCOUNTING_BACKUP_V5:"
