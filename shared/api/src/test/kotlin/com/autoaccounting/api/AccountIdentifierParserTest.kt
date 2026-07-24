package com.autoaccounting.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIdentifierParserTest {

    @Test
    fun parseValidUsernames() {
        val res1 = AccountIdentifierParser.parse("User_01")
        assertEquals(AccountIdentifierTypeContract.USERNAME, res1.type)
        assertEquals("user_01", res1.normalizedValue)
        assertEquals("User_01", res1.displayValue)

        val res2 = AccountIdentifierParser.parse("  admin_test  ")
        assertEquals(AccountIdentifierTypeContract.USERNAME, res2.type)
        assertEquals("admin_test", res2.normalizedValue)
        assertEquals("admin_test", res2.displayValue)
    }

    @Test
    fun parseValidEmails() {
        val res = AccountIdentifierParser.parse("  Test.User@Example.COM  ")
        assertEquals(AccountIdentifierTypeContract.EMAIL, res.type)
        assertEquals("test.user@example.com", res.normalizedValue)
        assertEquals("test.user@example.com", res.displayValue)
    }

    @Test
    fun parseValidPhones() {
        val res = AccountIdentifierParser.parse(" 13800138000 ")
        assertEquals(AccountIdentifierTypeContract.PHONE, res.type)
        assertEquals("13800138000", res.normalizedValue)
        assertEquals("13800138000", res.displayValue)

        assertEquals(
            AccountIdentifierTypeContract.PHONE,
            AccountIdentifierParser.parse("03800138000").type
        )
    }

    @Test
    fun invalidEmailDoesNotFallbackToUsername() {
        val invalidEmails = listOf(
            "user@domain@com",
            "@domain.com",
            "user@",
            "user..name@domain.com",
            "user@domain..com",
            "user@-domain.com"
        )
        for (invalid in invalidEmails) {
            val err = assertThrows("Expected $invalid to fail email validation", IllegalArgumentException::class.java) {
                AccountIdentifierParser.parse(invalid)
            }
            assertTrue(err.message.orEmpty().contains("email"))
        }
    }

    @Test
    fun invalidPhoneDoesNotFallbackToUsername() {
        val invalidPhones = listOf(
            "123456",
            "123456789012"
        )
        for (invalid in invalidPhones) {
            val err = assertThrows("Expected $invalid to fail phone validation", IllegalArgumentException::class.java) {
                AccountIdentifierParser.parse(invalid)
            }
            assertTrue(err.message.orEmpty().contains("phone"))
        }
    }

    @Test
    fun invalidUsernameThrowsException() {
        val invalidUsernames = listOf(
            "abc",          // too short (<4)
            "1user",        // starts with digit
            "user-name",    // hyphen not allowed
            "user name",    // space not allowed
            "a".repeat(21)  // too long (>20)
        )
        for (invalid in invalidUsernames) {
            val err = assertThrows("Expected $invalid to fail username validation", IllegalArgumentException::class.java) {
                AccountIdentifierParser.parse(invalid)
            }
            assertTrue(err.message.orEmpty().contains("username"))
        }
    }

    @Test
    fun emptyOrBlankInputThrowsException() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountIdentifierParser.parse("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountIdentifierParser.parse("   ")
        }
    }

    @Test
    fun emailLengthBoundaryCheck() {
        val longDomain = "a".repeat(250) + "@example.com" // > 254 chars (250 + 1 + 11 = 262)
        val err = assertThrows(IllegalArgumentException::class.java) {
            AccountIdentifierParser.parse(longDomain)
        }
        assertTrue(err.message.orEmpty().contains("email"))
    }
}
