plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Logging — Loki appender, JSON layout, structured MDC"

java {
    withSourcesJar()
}

dependencies {
    api(project(":arc-core"))

    implementation("org.apache.logging.log4j:log4j-api:2.23.0")
    implementation("org.apache.logging.log4j:log4j-core:2.23.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("pl.tkowalcz.tjahzi:log4j2-appender-nodep:0.9.41")
    implementation("org.slf4j:slf4j-api:2.0.16")

    testImplementation("io.mockk:mockk:1.14.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-logging"
        }
    }
}
