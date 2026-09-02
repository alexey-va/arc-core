plugins {
    kotlin("jvm")
    `maven-publish`
}

description = "ARC Core Paper Menu — configurable inventories and native dialogs"

val paperApiVersion: String by project

dependencies {
    api(project(":arc-core-menu"))
    compileOnlyApi("io.papermc.paper:paper-api:$paperApiVersion")
    implementation("com.github.stefvanschie.inventoryframework:IF:0.12.0")
    implementation("commons-lang:commons-lang:2.6")

    testImplementation(project(":arc-core-paper-testing"))
}

tasks.withType<Test>().configureEach {
    // Java 25 otherwise makes Byte Buddy launch an external attach helper,
    // which can stall before MockBukkit owns the Bukkit singleton.
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-paper-menu"
        }
    }
}
