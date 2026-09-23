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
    implementation("io.ktor:ktor-client-core:3.2.2")
    implementation("io.ktor:ktor-client-cio:3.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    // Real epoll .so, so NETTY_TRANSPORT=epoll can actually pick the native transport.
    runtimeOnly("io.netty:netty-transport-native-epoll:4.2.2.Final:linux-x86_64")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("FrontendKt")
}
