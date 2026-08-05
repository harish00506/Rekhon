# Database migrations — the procedure

**Binding rule: DB-003 — destructive migrations are forbidden.** This app has no server copy. A
dropped column is a user's financial history gone for good, with nothing to restore from. Room's
`fallbackToDestructiveMigration` is therefore never configured, and a missing migration fails at
open rather than quietly recreating the database.

Harness added by issue 1.7. Schema owned by `CfoDatabase` (issue 1.6).

---

## Adding a database version

1. **Change the entities** in `src/main/kotlin/.../entity/Entities.kt`.
2. **Bump** `CfoDatabase.VERSION`.
3. **Write the `Migration`** and register it on the builder in `CfoDatabaseFactory`. Prefer
   additive SQL — `ALTER TABLE … ADD COLUMN` with a default or nullable type. If you must replace a
   column, copy the data across in the same migration; never drop and recreate empty.
4. **Build the module** so KSP exports the new schema:
   `./gradlew :core:database:assembleDebug`
5. **Commit `core/database/schemas/com.aicfo.core.database.CfoDatabase/<version>.json`.** This file
   is a test fixture, not a build artifact. Never hand-edit it.
6. **Add a round-trip case** to `MigrationRoundTripTest` — seed rows at the old version, migrate,
   assert the values survive. Copy the template in that file.
7. Run both halves of the harness (below).

Skipping step 5 fails `MigrationSafetyTest` immediately: the fixture set must be contiguous from
version 1 and must contain the declared version.

---

## The two halves of the harness, and why there are two

| | `MigrationSafetyTest` (JVM) | `MigrationRoundTripTest` (device) |
|---|---|---|
| **Runs** | Everywhere, on every build | Only with a device or emulator attached |
| **Proves** | The schema never loses anything structurally | The migration SQL actually carries the rows |
| **Catches** | A dropped/renamed/retyped column, a tightened null constraint, a missing fixture | A migration whose SQL is wrong or incomplete |
| **Command** | `./gradlew :core:database:testDebugUnitTest` | `./gradlew :core:database:connectedDebugAndroidTest` |

Room's own `MigrationTestHelper` needs a device, and this project currently has none — which would
have left DB-003 enforced by nobody. But the exported schema JSON is just data, and
`androidx.room:room-migration` parses it in plain Kotlin, so the structural half runs today. That
split is the whole design: the common mistake is caught on every build; the rarer one is caught
whenever a device is available.

**As of 2026-07-25 the device half has never run.** `adb devices` is empty and the SDK has no AVD
installed.

---

## What counts as destructive

`findDestructiveChanges` in `SchemaFixtures.kt` flags four things between consecutive versions:

- a **table removed**
- a **column removed**
- a column whose **SQL affinity changed** (e.g. `INTEGER` → `TEXT`)
- a **nullable column made `NOT NULL`** — existing rows may hold null, so the migration fails on
  real data even though it passes on an empty database

Additive changes — new tables, new nullable columns — are allowed and produce no findings.

The check is structural, so it cannot see that a migration *copies* data into a replacement column.
A genuine rename-with-copy will be flagged. That bias is deliberate: the fix is to prove the data
survives in `MigrationRoundTripTest`, not to loosen the guard.

---

## Schema invariants, checked on every build

`MigrationSafetyTest` also asserts the invariants that apply to every table in this app, at the
column level where the data actually lives:

- `*_minor` columns are **INTEGER** — money is `Long` paise (MNY-001). A `REAL` amount column is
  the bug the whole `Money` type exists to prevent, and it would be invisible in Kotlin if the
  entity used a `Long` while the column drifted.
- `*_utc_millis` columns are **INTEGER** — instants are epoch millis (TIM-001).
- `*_iso_date` columns are **TEXT** — a user-picked day is an ISO string, never a midnight
  timestamp (TIM-002).
- every table has `deleted_at_utc_millis` (soft delete) and, except `profile`, `profile_id`
  (per-profile scoping).

A new entity that skips one of these fails the build.
