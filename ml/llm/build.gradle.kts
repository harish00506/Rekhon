// :ml:llm — on-device LLM (AICore/MediaPipe) behind LlmEngine; only verbalises engine
// numbers (P-03) and passes the numeric guardrail (AI-ARC-004) (issue 10.5).
plugins {
    alias(libs.plugins.cfo.android.library)
}

android {
    namespace = "com.aicfo.ml.llm"
}

dependencies {
    implementation(project(":core:model"))
}
