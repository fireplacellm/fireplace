// Root build file. Plugin versions are pinned; see gradle/libs.versions.toml at
// implementation time. This scaffold declares the module graph and the Android
// targets agreed in the ADRs.
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("com.android.library") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
