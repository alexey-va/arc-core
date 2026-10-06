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
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Optional at runtime: only the packet-display owner loads this integration.
    // Consumers using it declare the installed PacketEvents plugin as a dependency.
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1")
    compileOnly("io.netty:netty-transport:4.2.7.Final")

    testImplementation(project(":arc-core-paper-testing"))
    testImplementation(project(":arc-core-paper-api"))
    testImplementation(project(":arc-core-logging"))
    testImplementation(project(":arc-core-metrics"))
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1")
    testRuntimeOnly("io.netty:netty-transport:4.2.7.Final")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper"
        }
    }
}
