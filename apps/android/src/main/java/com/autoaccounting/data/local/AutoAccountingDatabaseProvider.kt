package com.autoaccounting.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object AutoAccountingDatabaseProvider {
    @Volatile
    private var instance: AutoAccountingDatabase? = null

    fun get(context: Context): AutoAccountingDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AutoAccountingDatabase::class.java,
                "auto-accounting.db"
            )
                .addMigrations(
                    AutoAccountingDatabase.MIGRATION_1_2,
                    AutoAccountingDatabase.MIGRATION_2_3,
                    AutoAccountingDatabase.MIGRATION_3_4,
                    AutoAccountingDatabase.MIGRATION_4_5,
                    AutoAccountingDatabase.MIGRATION_5_6,
                    AutoAccountingDatabase.MIGRATION_6_7,
                    AutoAccountingDatabase.MIGRATION_7_8,
                    AutoAccountingDatabase.MIGRATION_8_9,
                    AutoAccountingDatabase.MIGRATION_9_10,
                    AutoAccountingDatabase.MIGRATION_10_11
                )
                .addCallback(DEFAULT_CATEGORIZATION_RULES_CALLBACK)
                .build()
                .also { instance = it }
        }
}

internal val DEFAULT_CATEGORIZATION_RULES_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        DefaultCategorizationRules.insertMissing(db)
        db.execSQL(
            """
            INSERT OR IGNORE INTO ledger_books (id, name, created_at_epoch_millis)
            VALUES (?, ?, 0)
            """.trimIndent(),
            arrayOf(DEFAULT_LEDGER_BOOK_ID, DEFAULT_LEDGER_BOOK_NAME)
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO local_settings (
                id, ai_consent_granted, enhanced_context_granted,
                active_ledger_id
            ) VALUES (?, 0, 0, ?)
            """.trimIndent(),
            arrayOf(LOCAL_SETTINGS_ID, DEFAULT_LEDGER_BOOK_ID)
        )
    }
}
