plugins {
    // AGP 9.5.0 is the newest stable and the first line whose compileSdk ceiling covers API 37, which
    // Haze 2.1.0's AAR metadata requires (minCompileSdk=37). AGP 8.13's documented ceiling is API 36.1,
    // so raising compileSdk alone would not have worked.
    //
    // AGP 9 enables built-in Kotlin, so `org.jetbrains.kotlin.android` is deliberately NOT applied:
    // applying it makes AGP fail with "The 'org.jetbrains.kotlin.android' plugin is no longer required
    // for Kotlin support since AGP 9.0". We migrate properly instead of setting android.builtInKotlin=false.
    //
    // Kotlin stays pinned because `org.jetbrains.kotlin.plugin.compose` is versioned together with it,
    // and Haze 2.1.0 ships Kotlin 2.4 metadata (mv=[2,4,0]) that pre-2.4 compilers reject.
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
