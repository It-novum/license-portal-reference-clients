plugins { java }

dependencies {
    implementation("tools.jackson.core:jackson-databind:3.1.7")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Java 17 bytecode and API, so the reference can be copied into most applications.
tasks.withType<JavaCompile>().configureEach { options.release = 17 }

tasks.named<Test>("test") {
    useJUnitPlatform()
    // The tests read the conformance vectors from disk, so a changed vector runs them again rather than leaving a broken client up to date.
    inputs.dir(rootProject.layout.projectDirectory.dir("vectors")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("vectors")
}
