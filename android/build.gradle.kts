import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.gms.google.services)
}

// 릴리스 서명 정보: CI는 환경변수, 로컬은 keystore.properties(git 미추적)에서 읽음
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "io.github.zyautra.pushbeam"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.zyautra.pushbeam"
        minSdk = 34
        targetSdk = 36
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: providers.gradleProperty("pushbeam.version").get()
        // 서버 주소: 환경 변수 PUSHBEAM_URL 또는 gradle property pushbeam.url
        val serverUrl = System.getenv("PUSHBEAM_URL")
            ?: providers.gradleProperty("pushbeam.url").orNull
            ?: "https://pushbeam.example.com"
        buildConfigField("String", "PUSHBEAM_URL", "\"$serverUrl\"")

    }

    signingConfigs {
        create("release") {
            signingValue("KEYSTORE_FILE", "storeFile")?.let { storeFile = file(it) }
            storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signingValue("KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            if (signingConfigs["release"].storeFile != null) {
                signingConfig = signingConfigs["release"]
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(project(":shared"))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.firebase.messaging)
    implementation(libs.coroutines.play.services)
    implementation(libs.datastore.preferences)
    implementation(libs.firebase.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}