plugins { kotlin("jvm") version "2.2.10" }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)

repositories { mavenCentral() }
dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}

// Compile the actual Android transport sources, not a second protocol implementation.
kotlin.sourceSets.main {
    kotlin.srcDir("../../android/app/src/main/java")
    kotlin.include("**/NodiProtocol.kt", "**/NodiReceiver.kt", "**/NodiPairing.kt", "**/NodiDevices.kt", "**/NodiEndpoint.kt")
}

val core = layout.buildDirectory.file("nodi-core")
val buildGo = tasks.register<Exec>("buildGo") {
    workingDir("../..")
    doFirst { core.get().asFile.parentFile.mkdirs() }
    commandLine("go", "build", "-buildvcs=false", "-o", core.get().asFile.absolutePath, "./cmd/nodi")
}
tasks.test {
    dependsOn(buildGo)
    systemProperty("nodi.core", core.get().asFile.absolutePath)
    testLogging { events("passed", "skipped", "failed") }
}
