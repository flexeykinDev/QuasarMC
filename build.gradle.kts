plugins {
    java
    application
}

group = "dev.quasar"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val nettyVersion = "4.1.115.Final"

dependencies {
    implementation("io.netty:netty-buffer:$nettyVersion")
    implementation("io.netty:netty-codec:$nettyVersion")
    implementation("io.netty:netty-handler:$nettyVersion")
    implementation("io.netty:netty-transport:$nettyVersion")
    implementation("io.netty:netty-transport-classes-epoll:$nettyVersion")
    implementation("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")

    implementation("it.unimi.dsi:fastutil-core:8.5.15")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStackTraces = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass = "dev.quasar.Main"
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "-Xms1G",
        "-Xmx4G",
    )
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-this-escape"))
}

/**
 * Self-contained runnable jar. Deliberately hand-rolled instead of pulling in the
 * shadow plugin, so a clean checkout needs nothing but Gradle + a JDK 21 toolchain.
 */
val fatJar by tasks.registering(Jar::class) {
    archiveClassifier = "all"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.quasar.Main",
            "Implementation-Title" to "Quasar",
            "Implementation-Version" to project.version,
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

tasks.build { dependsOn(fatJar) }

/** Runs the synthetic load client: `gradlew bots --args="--count 8 --spread 4096"` */
val bots by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Connect N synthetic clients to a running Quasar server"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.quasar.bench.BotSwarm"
}
