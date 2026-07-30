rootProject.name = "tcms-backend"

// This is a self-contained Gradle build rooted at backend/, deliberately NOT at the
// monorepo root: the repo is polyglot (backend/ + frontend/ + docs/) and each stack
// owns its own directory and tooling. `cd backend && ./gradlew build` mirrors
// `cd frontend && npm run build`; neither stack's build files clutter the root.
//
// Spring Modulith modules are PACKAGES (com.tcms.tenancy, .identity, .project, …),
// not Gradle subprojects, so the modular monolith needs no subproject split. If real
// Gradle subprojects are ever warranted, include() them here — rooting the build in
// backend/ does not foreclose that.
//
// Production packaging (HANDOFF §7) stays a task in build.gradle.kts that invokes the
// frontend build and copies ../frontend/dist into the boot jar's static resources,
// while `npm run dev` remains fully independent for day-to-day UI work.

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
