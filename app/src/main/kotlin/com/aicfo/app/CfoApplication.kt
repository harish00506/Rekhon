package com.aicfo.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Hilt dependency-injection root.
 *
 * Why:  §21.2 / ARC-003 — the app is a single-Activity Compose host whose object graph
 *       is built by Hilt; @HiltAndroidApp bootstraps that graph once at process start.
 * What: the custom [Application] Hilt generates the component from.
 * Result: constructor injection is available across the app (no service locators).
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
@HiltAndroidApp
class CfoApplication : Application()
