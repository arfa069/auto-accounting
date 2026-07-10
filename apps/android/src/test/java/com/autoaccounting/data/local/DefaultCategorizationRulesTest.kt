package com.autoaccounting.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCategorizationRulesTest {
    @Test
    fun defaultsAreVisibleEditableRuleRecordsWithStableCoverage() {
        val rules = DefaultCategorizationRules.rules

        assertEquals(7, rules.size)
        assertEquals(rules.size, rules.map { it.id }.distinct().size)
        assertTrue(rules.all { it.id.startsWith("default-") })
        assertTrue(rules.all { it.enabled && it.category.isNotBlank() })
        assertEquals(
            setOf("餐饮", "交通", "购物", "居住", "医疗健康", "工资", "退款"),
            rules.map { it.category }.toSet()
        )
    }
}
