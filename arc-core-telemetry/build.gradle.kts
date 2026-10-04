plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Player Telemetry — durable player event outbox and query store"

dependencies {
    api(project(":arc-core"))
    api(project(":arc-core-sql"))
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("io.mockk:mockk:1.14.7")
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

kotlin.target.compilations.getByName("integrationTest").associateWith(kotlin.target.compilations.getByName("main"))

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    add(integrationTest.implementationConfigurationName, project(":arc-core-integration-testing"))
    add(integrationTest.runtimeOnlyConfigurationName, "com.mysql:mysql-connector-j:9.7.0")
}

tasks.register<Test>("integrationTest") {
    description = "Runs player telemetry SQL persistence and query tests against disposable MySQL"
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
            artifactId = "arc-core-telemetry"
        }
    }
}
