package com.aicfo.core.model

/**
 * One message as it sits in the phone's inbox (issue 3.9; §18, §23, P-01).
 *
 * Why:  the boundary between `:data:sms` — which needs a device, a `ContentResolver` and a
 *       Play-restricted permission — and `:domain:engines:sms`, which decides what a message *means*
 *       and must be provable without any of those. Putting the type here rather than in `:data:sms`
 *       is what lets a pure-Kotlin engine consume it at all: ARC-002 forbids a `:domain:*` module
 *       from importing Android, and `:data:sms` is an Android library. Exactly the reasoning
 *       [RecognizedText] records for the OCR path.
 * What: the three fields the parser reads, plus the id that makes a message identifiable.
 * Result: the input to the SMS parser, and the one thing a frozen eval fixture has to contain.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * **This type is not persisted.** The `sms_draft` table stores what the parser *concluded* and the
 * [id] it concluded it from — never [body]. P-01 says the message content stays on the device, and
 * the strongest reading of that is that it stays in the inbox that already owns it: the app reads a
 * message, decides, and keeps only the decision. A row holding the text would be a second copy of
 * the user's messages, living inside a financial database, for no purpose the feature needs.
 *
 * Input:  [id] — the inbox row's stable identifier (`Telephony.Sms._ID`), used as the scan cursor
 *         and as the key that stops one alert being proposed twice; [sender] — the originating
 *         address, an alphabetic DLT header (`VM-HDFCBK`) for a real bank alert and a ten-digit
 *         number for a person, which is the cheapest false-positive gate there is; [body] — the
 *         message text as received; [receivedAtUtcMillis] — when the phone received it, UTC epoch
 *         millis (TIM-001). The parser uses this as the transaction date rather than reading a
 *         clock, which is also what makes every eval case reproducible (P-08).
 * Output: an immutable value.
 */
data class SmsMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val receivedAtUtcMillis: Long,
) {
    init {
        require(receivedAtUtcMillis >= 0L) {
            "receivedAtUtcMillis is UTC epoch millis and cannot be negative, was $receivedAtUtcMillis"
        }
    }
}
