plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.2.2")
    implementation("io.ktor:ktor-server-netty:3.2.2")
    implementation("io.ktor:ktor-server-cio:3.2.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("BackendKt")
}
