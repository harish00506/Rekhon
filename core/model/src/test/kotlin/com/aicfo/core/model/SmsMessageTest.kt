package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves the SMS boundary type behaves (issue 3.9; §18, P-01).
 *
 * Why:  `:core:model` is held to 100% line coverage (CLAUDE.md §4), and the one piece of behaviour
 *       [SmsMessage] has is its refusal of a negative timestamp. That guard matters more here than
 *       it looks: the parser derives the transaction date from [SmsMessage.receivedAtUtcMillis]
 *       rather than from a clock (TIM-001), so a nonsensical instant would become a nonsensical
 *       booking date on a row about the user's money.
 * Result: the guard is pinned. Changelog: 2026-08-07 — Created for issue 3.9.
 */
class SmsMessageTest {
    @Test
    fun `a message keeps every field it was given`() {
        val message =
            SmsMessage(
                id = 42L,
                sender = "VM-HDFCBK",
                body = "Rs.1,250.00 debited from A/c XX4521",
                receivedAtUtcMillis = 1_786_082_400_000L,
            )

        assertEquals(42L, message.id)
        assertEquals("VM-HDFCBK", message.sender)
        assertEquals("Rs.1,250.00 debited from A/c XX4521", message.body)
        assertEquals(1_786_082_400_000L, message.receivedAtUtcMillis)
    }

    @Test
    fun `the epoch is a legal instant`() {
        assertEquals(0L, SmsMessage(1L, "VM-HDFCBK", "body", 0L).receivedAtUtcMillis)
    }

    @Test
    fun `a negative instant is refused rather than booked`() {
        assertThrows(IllegalArgumentException::class.java) {
            SmsMessage(1L, "VM-HDFCBK", "body", -1L)
        }
    }
}
