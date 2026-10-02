# =============================================================================
# R8 rules for the release build (issue 11.6; §21.3, §21.6, SEC-007).
#
# Why:  two jobs, and only one of them is size. The first is **stripping the logs**: §21.6 bans PII
#       and amounts from logs, and the custom lint rule `CfoPiiInLogs` enforces that at compile time
#       for the arguments it can read. It cannot read a string built at runtime, and it says nothing
#       about a `Log.d` that is merely uninteresting today and sits next to a balance tomorrow. The
#       release build removes the calls outright, so there is no release log surface to get wrong.
#       The second is shrinking, which also raises the cost of reading the shipped app.
# What: the log strip, plus the keep rules R8 cannot infer for reflective frameworks.
# Result: a release APK with no `Log` calls from this app's own code, verified rather than assumed
#         by `./gradlew verifyReleaseLogStripping` (and in CI).
# Changelog: 2026-10-02 — Created for issue 11.6.
#
# **Every keep rule here is justified in a comment.** A `-keep` nobody can explain is dead weight
# that silently stops R8 from shrinking, and the reflex of pasting rules "to be safe" is how a
# minified build ends up the same size as an unminified one.
# =============================================================================

# --- The log strip (§21.6, the point of this file) ---------------------------------------------
#
# `assumenosideeffects` lets R8 delete the whole call, arguments included — so a string built for a
# log message is never constructed in a release build either. That matters more than the call: a
# `"balance=" + money` concatenation would otherwise still run and still sit in memory.
#
# `e` and `wtf` are **deliberately kept**. An error path that cannot say anything is a release that
# cannot be diagnosed at all, and the lint rule already blocks PII in their arguments. Verbose,
# debug, info and warn carry the chatter and are the ones worth removing.
# The `return` clauses are not decoration. Without them R8 can only delete a call whose result is
# unused, and a surprising number are used — `if (Log.isLoggable(TAG, DEBUG))` guards a block, and
# `Log.w` returns an int that callers occasionally propagate. Measured on this app: without the
# returns, 42 `isLoggable` calls and one `w` survived minification; with them, all four levels reach
# zero. Giving `isLoggable` a constant `false` is what lets R8 fold the guarded block away too.
-assumenosideeffects class android.util.Log {
    public static int v(...) return 0;
    public static int d(...) return 0;
    public static int i(...) return 0;
    public static int w(...) return 0;
    public static boolean isLoggable(...) return false;
}

# `println` goes the same way: the PII lint rule covers it, and a release has no console.
-assumenosideeffects class java.io.PrintStream {
    public void println(%);
    public void println(java.lang.Object);
    public void println(java.lang.String);
}

# --- Crash-report readability ------------------------------------------------------------------
#
# Without these a stack trace from a release build is unreadable line numbers over renamed classes.
# `SourceFile` is rewritten to a constant by the default `proguard-android-optimize.txt`, so this
# keeps the trace mappable through `mapping.txt` without naming source paths in the APK.
-keepattributes SourceFile,LineNumberTable

# --- Kotlin reflection surface -----------------------------------------------------------------
#
# kotlinx.serialization generates `Companion.serializer()` and `$$serializer` members that are
# looked up reflectively for every `@Serializable` type. The library ships consumer rules covering
# its own classes; these cover **ours** — the nav routes (issue 1.10) and `CfoArchive` with its
# thirty entity lists, whose JSON field names are a contract with files already on users' phones
# (ADR-0023). An archive written by a minified build must still decode in an older one.
-keepclassmembers class com.aicfo.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.aicfo.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Enum valueOf/values are reflective for every enum we persist by name — `AuditEvent`,
# `ConsentFeature`, `TransactionType` and the rest. Room and the proto mapping both store `name`,
# so a renamed enum constant orphans existing rows.
-keepclassmembers enum com.aicfo.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Proto DataStore (found by running the release build, not by reading it) ---------------------
#
# **This one was a total lockout.** With no rule here, a clean install of the minified release opened
# on the lock screen with no PIN set — so there was no way into the app at all — while the same commit
# as a debug build opened on onboarding step 1. No crash and no exception: protobuf-javalite resolves
# its generated message classes reflectively, R8 renamed them, the settings read failed, and the app
# lock's deliberate fail-secure default (`AppLockUiState`: an unknown lock state is treated as
# locked, never as open) did exactly what it promises. Correct behaviour over broken data.
#
# The generated classes and their fields must therefore survive minification intact.
-keep class com.aicfo.core.datastore.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}

# --- Deliberately NOT kept ---------------------------------------------------------------------
#
# Room, Hilt/Dagger, Retrofit, OkHttp, Tink, SQLCipher, Glance and Compose all ship their own
# consumer ProGuard rules, which AGP merges automatically. Adding our own copies would be
# duplication that drifts out of date as those libraries change theirs. If one of them breaks under
# R8, the fix belongs here **with the evidence that proved it** — not pre-emptively. The protobuf
# block above is exactly that: it is here because a device run showed a clean release install
# locking the user out, and it names what was observed rather than guessing at what might help.
