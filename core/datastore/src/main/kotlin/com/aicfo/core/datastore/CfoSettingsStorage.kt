package com.aicfo.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import com.aicfo.core.datastore.proto.CfoSettingsProto
import kotlinx.coroutines.CoroutineScope
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Builds the settings [DataStore] over Okio-backed storage (issue 1.9).
 *
 * Why:  DataStore's default file storage writes to a temp file and renames it into place. That is
 *       the right design — a crash mid-write cannot corrupt the settings — but the `File.renameTo`
 *       it uses **cannot replace an existing file on Windows**, so every write after the first
 *       fails there. On Android that never happens, but it made this module's tests unrunnable on
 *       a Windows dev machine, and an untestable consent ledger is not acceptable for the one
 *       component P-01 rests on. Okio's `atomicMove` handles the replace case on every host, so
 *       the same code path is exercised in tests and in production.
 * What: an [OkioSerializer] wrapping the protobuf serialiser, and the factory that assembles them.
 * Result: one storage engine, testable everywhere.
 * Changelog: 2026-07-25 — Created for issue 1.9 after the default storage proved unwritable on
 *            Windows, which had been silently failing every second write.
 */
internal object CfoSettingsStorage {
    /**
     * Creates the store.
     * Why:    **no corruption handler on purpose.** The obvious wiring would replace a corrupt file
     *         with defaults — which for a consent ledger means silently discarding every permission
     *         the user granted or withheld, with no way for them to know. Corruption surfaces as
     *         `Err(Storage)` instead and the app decides what to say.
     * Result: a [DataStore] over the given path.
     * Input:  [path] — the settings file; [scope] — the store's coroutine scope, injected so it is
     *         cancelled with its owner (ARC-006).
     * Output: `DataStore<CfoSettingsProto>`.
     */
    fun create(
        path: Path,
        scope: CoroutineScope,
    ): DataStore<CfoSettingsProto> =
        DataStoreFactory.create(
            storage =
                OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = OkioProtoSerializer,
                    producePath = { path },
                ),
            scope = scope,
        )

    /** Convenience for callers holding a plain path string. Input: [path]. Output: the store. */
    fun create(
        path: String,
        scope: CoroutineScope,
    ): DataStore<CfoSettingsProto> = create(path.toPath(), scope)
}

/**
 * Bridges the protobuf serialiser to Okio's source/sink API.
 * Why: [CfoSettingsSerializer] already defines how the message is read and written, including
 *         the deliberate decision to surface corruption rather than reset. This adapts it rather
 *         than duplicating either behaviour.
 * Result: the serialiser [OkioStorage] needs.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
internal object OkioProtoSerializer : OkioSerializer<CfoSettingsProto> {
    override val defaultValue: CfoSettingsProto = CfoSettingsSerializer.defaultValue

    /** Input: [source] — the file. Output: the parsed settings; throws on corruption. */
    override suspend fun readFrom(source: BufferedSource): CfoSettingsProto =
        CfoSettingsSerializer.readFrom(source.inputStream())

    /** Input: [t] — the value; [sink] — the destination. Output: none. */
    override suspend fun writeTo(
        t: CfoSettingsProto,
        sink: BufferedSink,
    ) {
        CfoSettingsSerializer.writeTo(t, sink.outputStream())
    }
}
