plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper — scheduling, transfer, teleport, player-state escrow and nameplates"

val paperApiVersion: String by project

dependencies {
    api(project(":arc-core"))
    // The host plugin supplies the shared API class identity at runtime. Keeping
    // it compile-only here prevents consumer shadow JARs from embedding a second
    // ArcSidebarService class that Bukkit's ServicesManager could not match.
    compileOnlyApi(project(":arc-core-paper-api"))
    compileOnlyApi(project(":arc-core-logging"))
    compileOnlyApi(project(":arc-core-metrics"))
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(project(":arc-core-paper-testing"))
    testImplementation(project(":arc-core-paper-api"))
    testImplementation(project(":arc-core-logging"))
    testImplementation(project(":arc-core-metrics"))
    testImplementation("io.mockk:mockk:1.14.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper"
        }
    }
}
