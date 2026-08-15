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
    implementation(libs.spring.ldap.core)
    implementation(libs.modulith.starter.core)

    implementation(libs.spring.security.crypto)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.modulith.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.unboundid.ldapsdk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.greenmail.junit5)
    testImplementation(libs.spring.security.test)
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
    // Gradle gives a test worker 512m by default, and this suite had been running against that
    // ceiling without anybody knowing — §161 measured the hung worker at 522685K used of 524288K.
    // THE FAILURE MODE IS WHY THIS IS PINNED RATHER THAN LEFT TO THE DEFAULT: an exhausted G1 heap
    // here does not throw OutOfMemoryError and does not fail a test. Every test passes, the worker
    // finishes them, and then hangs for ever in MessageHub.stop with the collector spinning — a
    // build that never returns and reports nothing, which is the least diagnosable outcome
    // available. It cost two 20-minute runs to see it, and the second only because a heap dump was
    // taken of a process that looked idle.
    //
    // What lives in that heap is not the product: it is three ArchUnit class graphs (two held
    // statically by ArchitectureRulesTest for the JVM's whole life, one by ContractEnumTest),
    // several cached Spring contexts, and Testcontainers' client. The graphs are the large,
    // long-lived part and they grow with the codebase, so the ceiling gets closer every slice.
    maxHeapSize = "2g"

    // The artifacts this suite reads that are NOT on its classpath, declared so Gradle re-runs
    // the tests when they change.
    //
    // WITHOUT THIS THOSE RULES ARE GREEN BY CACHE, which is worse than not having them.
    // Several tests here assert over source *text* rather than over bytecode, because the thing
    // being checked is a string: ConfigConventionTest reads backend/Dockerfile and both compose
    // files (§185), IdempotencyKeyCapTest has read ../docs/specs/openapi.yaml since §136. Gradle
    // tracks a Test task's inputs as its classpath and its resources, so editing any of those
    // files leaves `:test` UP-TO-DATE and the rule about the edit never executes. Measured, and
    // it is how this comment came to exist: §185's own rules were verified by deleting the fix
    // they check, and the build reported success without running them.
    //
    // That is the failure this repo keeps meeting from the other end — a check that exists and
    // is not driven is a claim, not an observer — arriving here through the build tool rather
    // than through a deferred queue.
    //
    // Directories rather than files for `deploy` and `docs/specs`: a fifth artifact added there
    // is covered without anyone remembering this list, and this list is exactly the kind of
    // hand-kept tally §152–§156 replaced four times. `optional`, because the Docker build stage
    // copies only the build definition and `src` into its context, so these paths do not exist
    // while it configures the build to run `bootJar -x test`.
    // A single FILE for the SPA and not its directory, which is the opposite of the choice above
    // and for the reason that choice states: a fifth artifact under `deploy` or `docs/specs` is
    // one this suite would want to see, while `frontend/src` is another stack's whole source tree
    // and declaring it would re-run every backend test on every unrelated component edit. §188's
    // SeedDataTest.areasMatchTheSpa reads exactly one file there — permission.area's only
    // justification for existing is that it agrees with that file's `permissionAreas` — so the
    // declaration is exactly one file, and a second such read must add its own line here rather
    // than widening this one to the directory.
    inputs.files(
        file("Dockerfile"),
        file("../compose.yaml"),
        file("../deploy"),
        file("../docs/specs"),
        file("../frontend/src/lib/permissions.ts"),
    ).withPropertyName("outOfSourceArtifactsReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional(true)

    systemProperty("api.version", System.getenv("DOCKER_API_VERSION") ?: "1.41")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
