plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Redis — pub/sub, hash ops, in-memory test double"

java {
    withSourcesJar()
}

dependencies {
    implementation(project(":arc-core"))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("redis.clients:jedis:5.2.0")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-redis"
        }
    }
}
