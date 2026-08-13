plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core SQL — optional MySQL/Hikari runtime, async JDBC and migrations"

java {
    withSourcesJar()
}

dependencies {
    implementation(project(":arc-core"))
    api("com.zaxxer:HikariCP:7.0.2")
    runtimeOnly("com.mysql:mysql-connector-j:9.7.0")

    testImplementation("io.mockk:mockk:1.14.7")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-sql"
        }
    }
}
