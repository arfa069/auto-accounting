package com.autoaccounting.data.local

import android.content.Context
import androidx.room.Room

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
                    AutoAccountingDatabase.MIGRATION_2_3
                )
                .build()
                .also { instance = it }
        }
}
