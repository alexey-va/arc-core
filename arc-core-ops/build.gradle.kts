plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Ops — platform-agnostic ops HTTP server"

dependencies {
    api(project(":arc-core"))
    implementation("com.google.code.gson:gson:2.11.0")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-ops"
        }
    }
}
