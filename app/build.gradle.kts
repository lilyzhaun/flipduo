import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.flipduo.module"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.flipduo.module"
        minSdk = 35
        targetSdk = 36
        versionCode = 128
        versionName = "0.6.117"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].java.srcDir("../native-hook/src")
    sourceSets["main"].resources.srcDir("../native-hook/resources")
    buildTypes {
        release { isMinifyEnabled = false }
    }
    lint { lintConfig = rootProject.file("lint.xml") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES") }
}
kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_17
dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.3")
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
}
