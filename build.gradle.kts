plugins {
    kotlin("jvm") version "1.9.21"
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
    // Ktor server dependencies - minimal set
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testing dependencies
    testImplementation("io.ktor:ktor-server-tests-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.21")
}

kotlin {
    jvmToolchain(21)
}