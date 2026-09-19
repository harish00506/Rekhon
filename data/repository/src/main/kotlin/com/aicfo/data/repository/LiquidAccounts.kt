package com.aicfo.data.repository

import com.aicfo.core.model.AccountType

/**
 * The account types whose balance counts as liquid (issues 7.2, 9.2; ADR-0034, ADR-0043).
 *
 * Why:  one definition, read by both the emergency fund (§10.1, runway) and the forecast (§9,
 *       consolidated liquid balance). Two copies would be two answers to "how much could I spend
 *       today?" the first time either changed. Only the unambiguous types count, for the reason
 *       ADR-0034 records: there is no per-account liquidity tier to say an FD is breakable.
 * Result: bank and cash.
 * Changelog: 2026-09-19 — Extracted from `RoomEmergencyFundRepository` for issue 9.2.
 */
internal val LIQUID_ACCOUNT_TYPES: Set<AccountType> = setOf(AccountType.BANK, AccountType.CASH)
