plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    id("io.ktor.plugin") version "2.3.7"
    id("org.openapi.generator") version "7.2.0"
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
    // Ktor server - only what we need for JSON API
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Dependency Injection
    implementation("io.insert-koin:koin-ktor:3.5.3")
    implementation("io.insert-koin:koin-logger-slf4j:3.5.3")

    // OpenAPI/Swagger support
    implementation("io.ktor:ktor-server-openapi:2.3.7")
    implementation("io.ktor:ktor-server-swagger-jvm:2.3.7")

    // Google speech to text
    implementation("com.google.cloud:google-cloud-speech:4.61.0")
    implementation(platform("com.google.cloud:libraries-bom:26.62.0"))

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.13")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.21")
    testImplementation("io.insert-koin:koin-test:3.5.3")
}

kotlin {
    jvmToolchain(21)
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set("$projectDir/src/main/resources/openapi/sermo-api.json")
    outputDir.set("${layout.buildDirectory.get()}/generated/openapi")
    packageName.set("com.sermo.api.generated")
    modelPackage.set("com.sermo.models.generated")
    apiPackage.set("com.sermo.api.generated")

    configOptions.set(mapOf(
        "serializationLibrary" to "kotlinx_serialization",
        "library" to "jvm-ktor",
        "useCoroutines" to "true"
    ))

    // Only generate models, not client code
    globalProperties.set(mapOf(
        "models" to "",
        "apis" to "false",
        "supportingFiles" to "false"
    ))
}

// Add generated sources to compilation
sourceSets {
    main {
        kotlin {
            srcDir("${layout.buildDirectory.get()}/generated/openapi/src/main/kotlin")
        }
    }
}

// Ensure OpenAPI generation happens before compilation
tasks.compileKotlin {
    dependsOn("openApiGenerate")
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