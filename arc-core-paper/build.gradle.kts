plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper — scheduling, transfer, teleport, player-state escrow and nameplates"

val paperApiVersion: String by project

repositories {
    maven("https://repo.codemc.io/repository/maven-public/")
}

dependencies {
    api(project(":arc-core"))
    // The host plugin supplies the shared API class identity at runtime. Keeping
    // it compile-only here prevents consumer shadow JARs from embedding a second
    // ArcSidebarService class that Bukkit's ServicesManager could not match.
    compileOnlyApi(project(":arc-core-paper-api"))
    compileOnlyApi(project(":arc-core-logging"))
    compileOnlyApi(project(":arc-core-metrics"))
    compileOnlyApi(project(":arc-core-sql"))
    compileOnlyApi(project(":arc-core-redis"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Optional at runtime: only the packet-display owner loads this integration.
    // Consumers using it declare the installed PacketEvents plugin as a dependency.
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1")
    compileOnly("io.netty:netty-transport:4.2.7.Final")
    // Paper's API does not supply the NBT reader used for offline player data.
    implementation("net.kyori:adventure-nbt:4.26.1")

    testImplementation(project(":arc-core-paper-testing"))
    testImplementation(project(":arc-core-paper-api"))
    testImplementation(project(":arc-core-logging"))
    testImplementation(project(":arc-core-metrics"))
    testImplementation(project(":arc-core-sql"))
    testImplementation(project(":arc-core-redis"))
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1")
    testRuntimeOnly("io.netty:netty-transport:4.2.7.Final")
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    add(integrationTest.implementationConfigurationName, project(":arc-core-integration-testing"))
}
tasks.register<Test>("integrationTest") {
    description = "Verifies shared Paper resource leases against disposable Redis and MySQL"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper"
        }
    }
}
