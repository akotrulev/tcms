plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
}

group = "com.tcms"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform(libs.modulith.bom))
    testImplementation(platform(libs.testcontainers.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.modulith.starter.core)

    implementation(libs.spring.security.crypto)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.modulith.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.assertj.core)
    testImplementation(libs.greenmail.junit5)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -Werror per the be-java pattern-writer gate: warnings are build failures.
    // -serial: entities/exceptions deliberately carry no serialVersionUID.
    // -this-escape: JPA/Spring proxy-friendly classes trip it without a real defect.
    // -try: CompanyContext.open() is a scope guard — the resource is intentionally
    //       never referenced in the body. Java 21 has no unnamed variable (`_`, JEP 456,
    //       final in 22), so every correct use of the idiom would otherwise warn.
    //       Revisit when the toolchain moves to 22+.
    options.compilerArgs.addAll(
        listOf("-Xlint:all,-serial,-this-escape,-processing,-try", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Docker Engine 29 dropped API versions below 1.40, while the docker-java client
    // bundled with Testcontainers 1.21 still negotiates an older one — the daemon
    // answers HTTP 400 with an empty Info payload and Testcontainers reports the
    // misleading "Could not find a valid Docker environment", as though no daemon were
    // running at all. Pinning a floor that every Docker >= 20.10 accepts fixes it.
    // Overridable, so a CI image with a different daemon can set its own.
    // docker-java reads the "api.version" property (not DOCKER_API_VERSION), so it has
    // to be a system property on the forked test JVM.
    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.41")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
