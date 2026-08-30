plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core — config, lifecycle, identifiers, persistence, locale, diagnostics and nameplates"

java {
    withSourcesJar()
}

dependencies {
    implementation("org.snakeyaml:snakeyaml-engine:3.0.1")
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core"
        }
    }
}
