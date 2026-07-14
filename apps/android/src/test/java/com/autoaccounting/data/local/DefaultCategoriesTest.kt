package com.autoaccounting.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultCategoriesTest {
    @Test
    fun defaultsContainReviewedExpenseAndIncomeInventory() {
        val defaults = DefaultCategories.systemDefaults(createdAtEpochMillis = 1)

        assertEquals(49, defaults.size)
        assertEquals(49, defaults.map { it.name }.toSet().size)
        assertEquals(37, defaults.count { it.kind == TransactionKind.EXPENSE })
        assertEquals(10, defaults.count { it.kind == TransactionKind.INCOME })
        assertEquals("游戏", DefaultCategories.nameForId("games"))
        assertEquals("收款", DefaultCategories.nameForId("payment_received"))
    }

    @Test
    fun duplicateDisplayNamesResolveByTransactionKind() {
        assertEquals(
            "red_packet_expense",
            DefaultCategories.idForName("红包", TransactionKind.EXPENSE)
        )
        assertEquals(
            "red_packet_income",
            DefaultCategories.idForName("红包", TransactionKind.INCOME)
        )
        assertEquals("housing", DefaultCategories.idForName("居住"))
        assertEquals("healthcare", DefaultCategories.idForName("医疗健康"))
    }
}
