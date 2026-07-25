package com.aicfo.app.di

import com.aicfo.core.common.getOrNull
import com.aicfo.core.datastore.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Supplies the profile time zone to the [com.aicfo.core.common.Clock] (TIM-001).
 *
 * Why:  this closes the seam issue 1.3 deliberately left open. `Clock.zone()` is **synchronous** —
 *       every engine calls it inside ordinary maths, and making it `suspend` would ripple through
 *       the entire domain layer. But the setting it must read arrives as a `Flow` from DataStore
 *       (issue 1.9). The two are bridged here: a collector keeps the latest zone in a `@Volatile`
 *       field, and the clock reads that field. `runBlocking` would be the lazy bridge and is banned
 *       outside tests (§5) — blocking the main thread on a disk read at startup is exactly the kind
 *       of thing that makes an app feel broken.
 * What: starts one app-scoped collector and exposes the current zone as `() -> ZoneId`.
 * Result: calendar logic resolves in the user's zone within a frame or two of launch, and in the
 *       device zone before that.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *
 * **Every failure falls back to the device zone rather than throwing.** An unset setting, an
 * unreadable store, or a zone id this JVM does not recognise (a corrupt value, or one written by a
 * newer build) must not brick every date in the app. The wrong zone shows slightly wrong day
 * boundaries; a thrown exception from `Clock.zone()` would crash every engine at once.
 *
 * Input:  [settingsStore] — a deferred lookup of the setting source (see the `@Inject`
 *         constructor for why it is deferred); [scope] — the application scope the collector lives
 *         on (injected, never `GlobalScope` — ARC-006); [systemZone] — the fallback, injectable so
 *         tests do not depend on the machine's zone.
 * Output: a `() -> ZoneId` suitable for `SystemClock`.
 */
@Singleton
class ProfileZoneProvider
    internal constructor(
        private val settingsStore: () -> SettingsStore,
        private val scope: CoroutineScope,
        private val systemZone: () -> ZoneId,
    ) : () -> ZoneId {
        /**
         * The binding Hilt uses.
         * Why:    two details that are not stylistic. `Provider<SettingsStore>` **breaks a real
         *         dependency cycle** — the settings store is built alongside the consent store,
         *         which needs the `Clock`, which needs this class; deferring the lookup to
         *         `start()` cuts it, and by then the graph is complete. And the system-zone lambda
         *         is supplied here rather than as a defaulted constructor parameter, because
         *         Dagger does not see Kotlin default arguments and would demand a binding for
         *         `() -> ZoneId`.
         * Input:  [settingsStoreProvider], [scope]. Output: the provider.
         */
        @Inject
        constructor(
            settingsStoreProvider: Provider<SettingsStore>,
            scope: CoroutineScope,
        ) : this({ settingsStoreProvider.get() }, scope, { ZoneId.systemDefault() })

        @Volatile
        private var current: ZoneId = systemZone()

        /**
         * Begins tracking the setting.
         * Why:    not done in the constructor — an object that starts a coroutine when it is
         *         created cannot be built in a test without also starting it, and Hilt may
         *         construct it earlier than the app is ready. `CfoApplication` calls this once.
         * What:   collects the settings flow for the life of [scope], updating [current].
         * Result: subsequent calls to this provider return the user's zone.
         * Input:  none. Output: none (launches a collector).
         */
        fun start() {
            scope.launch {
                settingsStore().observe().collectLatest { result ->
                    current = result.getOrNull()?.profileTimeZoneId.toZoneOrDefault()
                }
            }
        }

        /**
         * The zone calendar logic should resolve in.
         * Result: the user's zone once known, the device zone until then.
         * Input:  none. Output: [ZoneId].
         */
        override fun invoke(): ZoneId = current

        /**
         * Parses a stored zone id defensively.
         * Why:    `ZoneId.of` throws on anything it does not recognise, and this value comes off
         *         disk — so it can be empty, stale, or written by a build that knew a zone this one
         *         does not.
         * Result: the parsed zone, or the device zone.
         * Input:  the receiver — the stored id, possibly `null`. Output: [ZoneId].
         */
        private fun String?.toZoneOrDefault(): ZoneId = runCatching { ZoneId.of(this) }.getOrElse { systemZone() }
    }
