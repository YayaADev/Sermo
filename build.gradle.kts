plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    id("io.ktor.plugin") version "2.3.7"
    application
}

group = "com.sermo"
version = "1.0.0"

application {
    mainClass.set("com.sermo.SermoKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // SermoModels - API models (conditional: local project vs Docker jar)
    if (file("../SermoModels").exists()) {
        implementation(project(":SermoModels"))
    } else {
        implementation(files("libs/models.jar"))
    }

    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-cors-jvm")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Dependency Injection
    implementation("io.insert-koin:koin-ktor:3.5.3")
    implementation("io.insert-koin:koin-logger-slf4j:3.5.3")

    // OpenAPI/Swagger support
    implementation("io.ktor:ktor-server-openapi:2.3.7")
    implementation("io.ktor:ktor-server-swagger-jvm:2.3.7")

    // Google Cloud APIs
    implementation("com.google.cloud:google-cloud-speech:4.61.0")
    implementation("com.google.cloud:google-cloud-texttospeech:2.47.0")
    implementation(platform("com.google.cloud:libraries-bom:26.62.0"))

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.13")
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Language Detection
    implementation("com.github.pemistahl:lingua:1.2.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.21")
    testImplementation("io.insert-koin:koin-test:3.5.3")
    implementation(kotlin("stdlib-jdk8"))
}

kotlin {
    jvmToolchain(21)
}


tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.sermo.SermoKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
