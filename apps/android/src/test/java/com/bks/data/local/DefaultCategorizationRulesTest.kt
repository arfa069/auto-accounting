package com.bks.data.local

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
            setOf("餐饮", "交通", "购物", "住房", "医疗", "工资", "退款"),
            rules.map { it.category }.toSet()
        )
    }
}
