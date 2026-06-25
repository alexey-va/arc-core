plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core AI — OpenRouter LLM, moderation, tool RPC over Redis"

java {
    withSourcesJar()
}

dependencies {
    api(project(":arc-core"))
    implementation(project(":arc-core-redis"))

    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.openai:openai-java:3.5.2")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-ai"
        }
    }
}
