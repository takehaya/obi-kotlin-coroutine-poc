plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.bytebuddy:byte-buddy:1.15.11")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Fat jar with ByteBuddy bundled. Track / Native get appended to the bootstrap
// class loader at premain, so the jar must stay a plain, non-modular jar.
tasks.jar {
    archiveBaseName.set("coroagent")
    manifest {
        attributes(
            "Premain-Class" to "obicoro.CoroAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
    }
}

// Minimal jar appended to the bootstrap class loader search. Appending the fat
// jar (with ByteBuddy inside) would load ByteBuddy twice (bootstrap + app
// loader) and fail with a LinkageError, so only Track / Native go in here.
val bootJar by tasks.registering(Jar::class) {
    archiveFileName.set("coroagent-boot.jar")
    from(sourceSets.main.get().output) {
        include("obicoro/Track*.class", "obicoro/Native.class")
    }
}

tasks.assemble {
    dependsOn(bootJar)
}
