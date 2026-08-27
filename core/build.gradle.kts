plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Чистый JVM-модуль без Android SDK. Именно поэтому логику генератора и
// судейства можно гонять юнит-тестами: gradle :core:test — секунды, без
// эмулятора и без устройства.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

sourceSets {
    named("main") { java.srcDirs("src/main/java") }
    named("test") { java.srcDirs("src/test/java") }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging { events("passed", "skipped", "failed") }
}
