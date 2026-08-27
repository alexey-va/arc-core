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

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, project(":arc-core-integration-testing"))
    add(integrationTest.runtimeOnlyConfigurationName, "com.mysql:mysql-connector-j:9.7.0")
}

tasks.register<Test>("integrationTest") {
    description = "Runs one-time-use ledger transitions against disposable MySQL"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-sql"
        }
    }
}
