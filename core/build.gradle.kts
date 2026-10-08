plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin.compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
    }
}

tasks.register<JavaExec>("sampleDrawings") {
    group = "verification"
    description = "Generate calculated A3 and A4 vector SVG construction sheets."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("nz.co.timbertakeoff.core.drawing.SampleDrawingExportKt")
    args(layout.buildDirectory.dir("sample-drawings").get().asFile.absolutePath)
}
