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
                    AutoAccountingDatabase.MIGRATION_3_4
                )
                .addCallback(DEFAULT_CATEGORIZATION_RULES_CALLBACK)
                .build()
                .also { instance = it }
        }
}

internal val DEFAULT_CATEGORIZATION_RULES_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        DefaultCategorizationRules.insertMissing(db)
    }
}
