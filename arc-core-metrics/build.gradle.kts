plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Metrics — cached Prometheus JVM, OS, disk, and application metrics"

java {
    withSourcesJar()
}

dependencies {
    api(project(":arc-core"))
    api("io.micrometer:micrometer-core:1.14.5")
    api("io.micrometer:micrometer-registry-prometheus:1.14.5")
    implementation(project(":arc-core-redis"))
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-metrics"
        }
    }
}
