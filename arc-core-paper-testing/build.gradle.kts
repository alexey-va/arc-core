plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper Testing — canonical MockBukkit runtime and fixtures"

val paperApiVersion: String by project
val mockBukkitVersion: String by project
val byteBuddyVersion: String by project

java {
    withSourcesJar()
}

dependencies {
    api(project(":arc-core-paper"))
    api("io.papermc.paper:paper-api:$paperApiVersion")
    api("org.mockbukkit.mockbukkit:mockbukkit-v1.21:$mockBukkitVersion")
    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper-testing"
        }
    }
}
