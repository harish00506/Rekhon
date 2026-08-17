package com.aicfo.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The system's way in to the widget (issue 5.5).
 *
 * Why:  Android talks to a widget through a `BroadcastReceiver`, not through the widget class, so
 *       this is the type named in the manifest. It is deliberately **plain** — no
 *       `@AndroidEntryPoint`, no injected fields. Everything a receiver would normally fetch for
 *       its widget is already in the Glance state by the time the system asks for a frame, put
 *       there by `:app`'s `WidgetRefreshWorker`; injecting here would only reintroduce the database
 *       dependency that ADR-0024 exists to keep off this path (SEC-002).
 * What: hands the system a [CfoWidget].
 * Result: the widget appears in the picker and receives its update broadcasts.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
class CfoWidgetReceiver : GlanceAppWidgetReceiver() {
    /** Result: the widget this receiver renders. Input: none. Output: [CfoWidget]. */
    override val glanceAppWidget: GlanceAppWidget get() = CfoWidget()
}
