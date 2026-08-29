package com.tcms;

import java.util.List;

/**
 * What {@code seed-demo-data} creates, as data rather than as code.
 *
 * <p>Separated from {@link DemoDataSeeder} so the seeder stays the mechanism and this stays the
 * fixture: a reviewer asking "what will I see on the runs screen" reads this file, and somebody
 * adding a screen adds rows here rather than statements there.
 *
 * <p>The two companies are not decoration. One is rich enough to fill every screen and the other
 * exists so that <em>isolation is visible</em> — signing in as a Vertex person and finding none of
 * Northwind's projects is the only way a reader confirms the tenancy layer from the UI rather than
 * from a test name.
 */
final class DemoSeedPlan {

    /**
     * The one password every seeded person gets. Twelve characters is
     * {@code PasswordPolicy.MIN_LENGTH}; this is fourteen, so the policy cannot reject it and a
     * later tightening of the minimum shows up as a failed seed rather than as a person who
     * exists and cannot sign in.
     */
    static final String PASSWORD = "tcms-demo-2026";

    /** The instance operator, granted SYSTEM scope so the instance-level screens have an actor. */
    static final String INSTANCE_ADMIN_EMAIL = "ops@tcms.test";

    private DemoSeedPlan() {
    }

    /**
     * A person to create in a company, and the role they hold.
     *
     * @param roleName    the built-in role from {@code V3}/{@code V40}, by name. Names are used
     *                    HERE and nowhere else: this is a fixture choosing which grant to make,
     *                    not runtime code deciding what somebody may do — which is always by
     *                    permission (roles spec §A3).
     * @param projectKeys the projects the grant applies to, or empty for a company-wide grant.
     */
    record Person(String email, String displayName, String roleName, List<String> projectKeys) {

        static Person companyWide(String email, String displayName, String roleName) {
            return new Person(email, displayName, roleName, List.of());
        }
    }

    /** A project and how much content it gets — see {@link Depth}. */
    record ProjectSpec(String key, String name, String description, Depth depth, boolean archived) {
    }

    /**
     * How much content a project receives.
     *
     * <p>Three levels rather than one, because several screens only tell the truth at a
     * particular size: an empty state is a render worth seeing, a project with one run cannot
     * show a trend, and a project with everything hides which parts are optional.
     */
    enum Depth {

        /** Every screen populated — suites, cases, plans, runs, results, docs, reports, board. */
        FULL,

        /** The repository and one run. Enough to be navigable, thin enough to read at a glance. */
        PARTIAL,

        /** Nothing but the seeded defaults, so the empty states are reachable. */
        EMPTY
    }

    /** One client company and everything under it. */
    record CompanySpec(
            String slug,
            String displayName,
            String adminEmail,
            String adminName,
            List<Person> people,
            List<ProjectSpec> projects) {
    }

    /**
     * The rich company. Four projects at three depths, so the project list itself has something
     * to say — one archived, one empty, one thin, one full.
     */
    static final CompanySpec NORTHWIND = new CompanySpec(
            "northwind",
            "Northwind Retail",
            "admin@northwind.test",
            "Dana Whitfield",
            List.of(
                    Person.companyWide("lead@northwind.test", "Priya Raman", "Test Lead"),
                    new Person("engineer@northwind.test", "Marco Ferreira", "Test Engineer",
                            List.of("WEB", "MOB")),
                    new Person("tester@northwind.test", "Aiko Tanaka", "Test Engineer",
                            List.of("WEB")),
                    new Person("viewer@northwind.test", "Tom Brennan", "Viewer", List.of("WEB"))),
            List.of(
                    new ProjectSpec("WEB", "Storefront Web",
                            "The customer-facing shop — catalogue, cart, checkout and account.",
                            Depth.FULL, false),
                    new ProjectSpec("MOB", "Mobile Apps",
                            "iOS and Android shopping apps, sharing the storefront's API.",
                            Depth.PARTIAL, false),
                    new ProjectSpec("PAY", "Payments Platform",
                            "Card capture, settlement and refunds. Newly opened — no tests yet.",
                            Depth.EMPTY, false),
                    new ProjectSpec("LEG", "Legacy Portal",
                            "Retired in 2025. Kept for its history.",
                            Depth.EMPTY, true)));

    /**
     * The second company. Small on purpose: its whole job is to be somewhere the Northwind people
     * cannot see, and a second full company would double the seed time to prove the same thing.
     */
    static final CompanySpec VERTEX = new CompanySpec(
            "vertex",
            "Vertex Health",
            "admin@vertex.test",
            "Ines Duarte",
            List.of(
                    Person.companyWide("lead@vertex.test", "Samir Haddad", "Test Lead")),
            List.of(
                    new ProjectSpec("EHR", "Patient Records",
                            "Clinical record keeping — the regulated half of the product.",
                            Depth.PARTIAL, false)));

    static final List<CompanySpec> COMPANIES = List.of(NORTHWIND, VERTEX);
}
