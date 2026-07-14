package com.autoaccounting.data.local

object DefaultCategories {
    private val definitions = listOf(
        definition("food", "餐饮", TransactionKind.EXPENSE, 10),
        definition("shopping", "购物", TransactionKind.EXPENSE, 20),
        definition("daily", "日用", TransactionKind.EXPENSE, 30),
        definition("transport", "交通", TransactionKind.EXPENSE, 40),
        definition("vegetables", "蔬菜", TransactionKind.EXPENSE, 50),
        definition("fruit", "水果", TransactionKind.EXPENSE, 60),
        definition("snacks", "零食", TransactionKind.EXPENSE, 70),
        definition("sports", "运动", TransactionKind.EXPENSE, 80),
        definition("entertainment", "娱乐", TransactionKind.EXPENSE, 90),
        definition("communication", "通讯", TransactionKind.EXPENSE, 100),
        definition("clothing", "服饰", TransactionKind.EXPENSE, 110),
        definition("beauty", "美容", TransactionKind.EXPENSE, 120),
        definition("housing", "住房", TransactionKind.EXPENSE, 130),
        definition("family", "家庭", TransactionKind.EXPENSE, 140),
        definition("social", "社交", TransactionKind.EXPENSE, 150),
        definition("travel", "旅行", TransactionKind.EXPENSE, 160),
        definition("tobacco_alcohol", "烟酒", TransactionKind.EXPENSE, 170),
        definition("digital", "数码", TransactionKind.EXPENSE, 180),
        definition("car", "汽车", TransactionKind.EXPENSE, 190),
        definition("healthcare", "医疗", TransactionKind.EXPENSE, 200),
        definition("books", "书籍", TransactionKind.EXPENSE, 210),
        definition("study", "学习", TransactionKind.EXPENSE, 220),
        definition("pets", "宠物", TransactionKind.EXPENSE, 230),
        definition("cash_gift_expense", "礼金", TransactionKind.EXPENSE, 240, "礼金（支出）"),
        definition("gifts", "礼品", TransactionKind.EXPENSE, 250),
        definition("office", "办公", TransactionKind.EXPENSE, 260),
        definition("repair", "维修", TransactionKind.EXPENSE, 270),
        definition("donation", "捐赠", TransactionKind.EXPENSE, 280),
        definition("lottery", "彩票", TransactionKind.EXPENSE, 290),
        definition("red_packet_expense", "红包", TransactionKind.EXPENSE, 300, "红包（支出）"),
        definition("courier", "快递", TransactionKind.EXPENSE, 310),
        definition("other_expense", "其它", TransactionKind.EXPENSE, 320, "其它（支出）"),
        definition("repayment", "还款", TransactionKind.EXPENSE, 330),
        definition("lending", "借出", TransactionKind.EXPENSE, 340),
        definition("drinks", "饮品", TransactionKind.EXPENSE, 350),
        definition("fandom", "追星", TransactionKind.EXPENSE, 360),
        definition("games", "游戏", TransactionKind.EXPENSE, 370),
        definition("salary", "工资", TransactionKind.INCOME, 510),
        definition("red_packet_income", "红包", TransactionKind.INCOME, 520, "红包（收入）"),
        definition("rent_income", "租金", TransactionKind.INCOME, 530),
        definition("cash_gift_income", "礼金", TransactionKind.INCOME, 540, "礼金（收入）"),
        definition("dividends", "分红", TransactionKind.INCOME, 550),
        definition("investment_income", "理财", TransactionKind.INCOME, 560),
        definition("year_end_bonus", "年终奖", TransactionKind.INCOME, 570),
        definition("other_income", "其它", TransactionKind.INCOME, 580, "其它（收入）"),
        definition("borrowing", "借入", TransactionKind.INCOME, 590),
        definition("payment_received", "收款", TransactionKind.INCOME, 600),
        definition("refund", "退款", TransactionKind.REFUND, 700),
        definition("uncategorized", "未分类", null, 9999)
    )

    fun systemDefaults(createdAtEpochMillis: Long): List<CategoryEntity> = definitions.map {
        CategoryEntity(
            id = it.id,
            name = it.storageName,
            kind = it.kind,
            sortOrder = it.sortOrder,
            isSystem = true,
            createdAtEpochMillis = createdAtEpochMillis
        )
    }

    fun nameForId(id: String): String? = definitions.firstOrNull { it.id == id }?.displayName

    fun idForName(name: String, kind: TransactionKind? = null): String? {
        val normalizedName = when (name.trim()) {
            "居住" -> "住房"
            "医疗健康" -> "医疗"
            "其他" -> "其它"
            else -> name.trim()
        }
        val matches = definitions.filter {
            it.displayName == normalizedName || it.storageName == name.trim()
        }
        if (matches.size <= 1) return matches.firstOrNull()?.id
        return if (kind == TransactionKind.INCOME) {
            matches.firstOrNull { it.kind == TransactionKind.INCOME }?.id
        } else {
            matches.firstOrNull { it.kind != TransactionKind.INCOME }?.id
        }
    }

    private fun definition(
        id: String,
        name: String,
        kind: TransactionKind?,
        sortOrder: Int,
        storageName: String = name
    ) = DefaultCategoryDefinition(id, name, storageName, kind, sortOrder)
}

private data class DefaultCategoryDefinition(
    val id: String,
    val displayName: String,
    val storageName: String,
    val kind: TransactionKind?,
    val sortOrder: Int
)
