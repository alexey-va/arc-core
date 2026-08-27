plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Integration Testing — disposable Redis and MySQL Testcontainers"

java {
    withSourcesJar()
}

dependencies {
    api("org.testcontainers:testcontainers:2.0.5")
    testImplementation("com.mysql:mysql-connector-j:9.7.0")
    testImplementation(project(":arc-core-redis"))
    testImplementation("com.google.code.gson:gson:2.11.0")
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("integrationTest") {
    description = "Runs the shared Redis and MySQL fixtures against disposable containers"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn("integrationTest")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-integration-testing"
        }
    }
}
