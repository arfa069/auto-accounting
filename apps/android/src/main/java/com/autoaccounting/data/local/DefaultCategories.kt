package com.autoaccounting.data.local

object DefaultCategories {
    fun systemDefaults(createdAtEpochMillis: Long): List<CategoryEntity> = listOf(
        CategoryEntity(
            id = "food",
            name = "餐饮",
            kind = TransactionKind.EXPENSE,
            sortOrder = 10,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        ),
        CategoryEntity(
            id = "transport",
            name = "交通",
            kind = TransactionKind.EXPENSE,
            sortOrder = 20,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        ),
        CategoryEntity(
            id = "shopping",
            name = "购物",
            kind = TransactionKind.EXPENSE,
            sortOrder = 30,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        ),
        CategoryEntity(
            id = "housing",
            name = "居住",
            kind = TransactionKind.EXPENSE,
            sortOrder = 40,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        ),
        CategoryEntity(
            id = "healthcare",
            name = "医疗健康",
            kind = TransactionKind.EXPENSE,
            sortOrder = 50,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        ),
        CategoryEntity(
            id = "salary",
            name = "工资",
            kind = TransactionKind.INCOME,
            sortOrder = 110,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        ),
        CategoryEntity(
            id = "refund",
            name = "退款",
            kind = TransactionKind.REFUND,
            sortOrder = 120,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        ),
        CategoryEntity(
            id = "uncategorized",
            name = "未分类",
            kind = null,
            sortOrder = 999,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        )
    )
}
