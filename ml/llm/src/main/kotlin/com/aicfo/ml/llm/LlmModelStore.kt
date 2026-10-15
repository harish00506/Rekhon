package com.aicfo.ml.llm

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Which model file this build accepts (ADR-0079; §19, AI-ARC-007).
 *
 * Why:  the weights are sideloaded, so the app has to be able to say — exactly, and without
 *       trusting the file name — whether what it found is the model it was built against. Name,
 *       length and SHA-256 are that statement, and keeping them in one value rather than four
 *       constants is what lets a test use a small fixture instead of half a gigabyte.
 * What: the published model's identity, its licence and where it came from.
 * Result: the thing [LlmModelStore] checks a file against.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * Input:  [fileName] — verbatim, as published; [sizeBytes]; [sha256] — lower-case hex;
 *         [modelId] — what provenance calls it (P-02); [licence]; [source] — where to get it.
 * Output: an immutable value.
 */
data class LlmModelManifest(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val modelId: String,
    val licence: String,
    val source: String,
) {
    companion object {
        /**
         * The model the app is built against: Qwen2.5-0.5B-Instruct, int8, as published by Google's
         * LiteRT community for MediaPipe's LLM Inference API.
         *
         * Why this one: the weights are **Apache-2.0**, which a project licensed AGPL-3.0 can ship
         * alongside without asking anyone's permission — a Gemma-class model of the same size comes
         * with its own terms of use and a redistribution policy, and the first question anyone asks
         * about an on-device model is whether they are allowed to have it. It is also small enough
         * (521 MB) to load on a mid-range phone, which is the phone this app is for.
         */
        val PUBLISHED =
            LlmModelManifest(
                fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
                sizeBytes = 546_660_344L,
                sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
                modelId = "on-device:qwen2.5-0.5b-instruct-q8",
                licence = "Apache-2.0",
                source = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct",
            )
    }
}

/**
 * Whether this device has the neural model, and whether it is really the model (ADR-0079; §19,
 * AI-ARC-007, P-01, P-04).
 *
 * Why:  §19's default engine is an on-device compact instruction model, and ADR-0053 shipped the
 *       template verbaliser instead because the weights are half a gigabyte and cannot live in a
 *       repository or an APK. They still cannot. So the model is a **file the user installs** —
 *       which is also what makes the opt-in honest: no file, no neural path, and no network call
 *       anywhere in the app that could fetch one (P-01, P-04).
 * What: the file's presence, its length, and its digest — checked once and remembered.
 * Result: either a path a runtime may load, or the reason the file was refused. A file that is
 *         *nearly* the model is refused: a stopped download is the likeliest failure here, and a
 *         half model fails inside the native library in ways no Kotlin can explain to a user.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 *
 * Input:  [context] — for `filesDir` only; [manifest] — which model to accept, defaulting to the
 *         published one. A test overrides it, because a 521 MB fixture can no more live in this
 *         repository than in the APK.
 * Output: an [LlmModelStore].
 */
class LlmModelStore(
    private val context: Context,
    private val manifest: LlmModelManifest = LlmModelManifest.PUBLISHED,
) {
    /**
     * Where the model file belongs, created if it is not there yet.
     * Why:    the install script and the runtime have to agree on one path, and app-private storage
     *         is the only place a file is both reachable without a permission and unreadable by
     *         every other app on the phone.
     * Result: `<app files>/models`, as a directory that exists.
     * Input:  none. Output: [File].
     */
    fun directory(): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    /**
     * Decides whether the neural path is available on this device, right now.
     * Why:    this is the switch for the whole feature, so it answers from the file system rather
     *         than from a setting that could disagree with it. The digest costs seconds over half a
     *         gigabyte, so a pass is remembered in a sidecar file and only redone when the file's
     *         length or timestamp changes.
     * Result: [ModelStatus.Ready] with the path and the model id; [ModelStatus.Absent] when there
     *         is no file at all; [ModelStatus.Rejected] with the reason when the file is not this
     *         model.
     * Input:  none. Output: [ModelStatus].
     */
    fun status(): ModelStatus {
        val file = File(directory(), manifest.fileName)
        return when {
            !file.isFile -> ModelStatus.Absent
            file.length() != manifest.sizeBytes -> ModelStatus.Rejected(RejectionReason.WRONG_LENGTH)
            remembersThisFile(file) -> ModelStatus.Ready(file.absolutePath, manifest.modelId)
            digestOf(file) != manifest.sha256 -> ModelStatus.Rejected(RejectionReason.WRONG_DIGEST)
            else -> {
                remember(file)
                ModelStatus.Ready(file.absolutePath, manifest.modelId)
            }
        }
    }

    /**
     * Result: true when this exact file already passed the digest check.
     * Why:    re-hashing 521 MB on every cold start would add seconds to the first question of
     *         every session for no new information. Length **and** timestamp both have to match
     *         what was recorded, so a replaced file is hashed again.
     * Input:  [file] — the model file. Output: [Boolean].
     */
    private fun remembersThisFile(file: File): Boolean =
        runCatching { receipt().readText() == receiptFor(file) }.getOrDefault(false)

    /** Records that [file] passed, so the next start can skip the hash. Output: none. */
    private fun remember(file: File) {
        runCatching { receipt().writeText(receiptFor(file)) }
    }

    private fun receipt(): File = File(directory(), RECEIPT_NAME)

    private fun receiptFor(file: File): String = "${manifest.sha256} ${file.length()} ${file.lastModified()}"

    /**
     * Result: the file's SHA-256, lower-case hex.
     * Why:    the manifest's digest is the only thing that tells the published model apart from a
     *         file of the same name and length — including a damaged copy of itself.
     * Input:  [file]. Output: [String]. Streamed in 1 MB blocks, because the file does not fit in
     *         memory on the phones this app targets.
     */
    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val block = ByteArray(BLOCK_BYTES)
            var read = stream.read(block)
            while (read > 0) {
                digest.update(block, 0, read)
                read = stream.read(block)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        /** Where in app-private storage the weights live, relative to `filesDir`. */
        const val DIRECTORY = "models"

        private const val RECEIPT_NAME = "model.verified"
        private const val BLOCK_BYTES = 1 shl 20
    }
}

/**
 * Whether this device can run the neural verbaliser.
 * Why:    three outcomes, not two: no model is the ordinary case and means nothing is wrong, while
 *         a file that fails its manifest is a problem the user can fix and should be told about.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
sealed interface ModelStatus {
    /**
     * The model is installed and verified.
     * Input: [path] — absolute path for the runtime; [modelId] — for provenance (P-02).
     */
    data class Ready(
        val path: String,
        val modelId: String,
    ) : ModelStatus

    /** No model file. The app answers from the template verbaliser, which is not a degradation. */
    data object Absent : ModelStatus

    /** A file is there and it is not the model. Input: [reason] — which check failed. */
    data class Rejected(
        val reason: RejectionReason,
    ) : ModelStatus
}

/**
 * Why a model file was refused.
 * Changelog: 2026-10-10 — Created for ADR-0079.
 */
enum class RejectionReason {
    /** Not the published length — most often a download that stopped early. */
    WRONG_LENGTH,

    /** Right length, wrong contents: a different model, or a damaged copy of this one. */
    WRONG_DIGEST,
}
