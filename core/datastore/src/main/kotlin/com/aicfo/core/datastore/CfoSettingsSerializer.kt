package com.aicfo.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.aicfo.core.datastore.proto.CfoSettingsProto
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes the settings file (issue 1.9; §21.3, P-01).
 *
 * Why:  DataStore needs to be told how to turn bytes into the typed value. The interesting part is
 *       what happens when it **cannot**: the obvious wiring is a `ReplaceFileCorruptionHandler`
 *       that quietly substitutes defaults, and doing that here would be a privacy incident. The
 *       defaults include "no consent granted" — so a corrupt file would silently revoke every
 *       permission the user gave, and the app would re-prompt as though they had never decided.
 *       Worse, the opposite class of bug (defaults that read as granted) would silently *create*
 *       consent. So corruption surfaces as an error and the caller decides.
 * What: protobuf serialisation, and an explicitly empty default.
 * Result: a store that fails loudly rather than forgetting what the user agreed to.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
internal object CfoSettingsSerializer : Serializer<CfoSettingsProto> {
    /**
     * What a brand-new install holds.
     * Why:    an empty message, which under proto3 means every consent map entry is absent — and
     *         absence is not consent. There is deliberately no code path that defaults a consent
     *         to granted.
     */
    override val defaultValue: CfoSettingsProto = CfoSettingsProto.getDefaultInstance()

    /**
     * Parses the stored bytes.
     * Result: the settings; throws [CorruptionException] if the file is not valid protobuf, which
     *         the store converts into `Err(AppError.Storage)` rather than swallowing.
     * Input:  [input] — the file stream. Output: [CfoSettingsProto].
     */
    override suspend fun readFrom(input: InputStream): CfoSettingsProto =
        try {
            CfoSettingsProto.parseFrom(input)
        } catch (failure: InvalidProtocolBufferException) {
            throw CorruptionException("Settings file is not valid protobuf", failure)
        }

    /**
     * Writes the settings.
     * Result: the bytes on disk; DataStore handles atomicity by writing to a temp file first.
     * Input:  [t] — the value; [output] — the stream. Output: none.
     */
    override suspend fun writeTo(
        t: CfoSettingsProto,
        output: OutputStream,
    ) {
        t.writeTo(output)
    }
}
