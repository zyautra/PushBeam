plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("io.github.zyautra.pushbeam.server.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ulid.creator)
    implementation(libs.firebase.admin)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}
