plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // gradle test -PupdateGolden  -> ghi lại ../testvectors/golden.json
    systemProperty("updateGolden", (project.findProperty("updateGolden") ?: "").toString())
    testLogging { events("failed"); showStandardStreams = false }
}
