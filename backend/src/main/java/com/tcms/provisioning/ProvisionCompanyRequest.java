package com.tcms.provisioning;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What an operator must supply to provision a company.
 *
 * <p>Validated in the canonical constructor, so an invalid request cannot be
 * constructed at all and the service never has to re-check. The formats are the
 * database's own constraints ({@code ck_company_slug_format},
 * {@code ck_project_key_format}) restated here for one reason: a constraint violation
 * surfaces as a driver exception forty lines deep, and an operator running a CLI
 * deserves to be told which argument is wrong before a transaction opens.
 *
 * @param slug         immutable after provisioning — it appears in every URL and shared
 *                     link (plan §6t)
 * @param displayName  the client's name as they write it
 * @param adminEmail   the first Company Admin. Unique instance-wide, so an operator
 *                     provisioning a second company for an existing person reuses their
 *                     account rather than creating a duplicate
 * @param adminName    the per-membership display name (§6u), not a global one — the same
 *                     person may legitimately be named differently in another client
 * @param firstProject the optional first project. Absent is a normal outcome: project
 *                     defaults are seeded by project creation too, so a company with no
 *                     project yet is complete, not half-provisioned
 */
public record ProvisionCompanyRequest(
        String slug,
        String displayName,
        String adminEmail,
        String adminName,
        Optional<FirstProject> firstProject) {

    /** {@code ck_company_slug_format} in V1. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** Matches {@code openapi.yaml}'s {@code Project.key} pattern. */
    private static final Pattern PROJECT_KEY = Pattern.compile("^[A-Z0-9]{2,10}$");

    /**
     * Deliberately permissive: it checks the shape an address must have to be routable
     * at all, and nothing more. A stricter regex rejects valid addresses, and the real
     * verification is that the invitation is received.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    public ProvisionCompanyRequest {
        slug = requireText(slug, "slug");
        displayName = requireText(displayName, "display name");
        adminEmail = requireText(adminEmail, "admin email");
        adminName = requireText(adminName, "admin name");

        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "slug must be lower-case alphanumerics separated by single hyphens (got '" + slug
                            + "'). It appears in every URL and is immutable once provisioned.");
        }
        if (!EMAIL.matcher(adminEmail).matches()) {
            throw new IllegalArgumentException("admin email is not an address: '" + adminEmail + "'");
        }
        if (firstProject == null) {
            throw new IllegalArgumentException("firstProject must be Optional.empty(), never null");
        }
    }

    /** Without a first project. */
    public static ProvisionCompanyRequest of(
            String slug, String displayName, String adminEmail, String adminName) {
        return new ProvisionCompanyRequest(slug, displayName, adminEmail, adminName, Optional.empty());
    }

    /** With one. */
    public static ProvisionCompanyRequest withFirstProject(
            String slug,
            String displayName,
            String adminEmail,
            String adminName,
            String projectKey,
            String projectName) {
        return new ProvisionCompanyRequest(
                slug,
                displayName,
                adminEmail,
                adminName,
                Optional.of(new FirstProject(projectKey, projectName)));
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        return value.strip();
    }

    /**
     * A project is a whole client product or engagement, not a feature area (plan §1).
     * "Acme Online Banking" is a project; "Payments" is a feature or a suite folder.
     *
     * @param key  unique per company, not globally — two clients may both key a project
     *             "OLB"
     */
    public record FirstProject(String key, String name) {

        public FirstProject {
            key = requireText(key, "project key");
            name = requireText(name, "project name");

            if (!PROJECT_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "project key must be 2–10 upper-case letters or digits (got '" + key + "')");
            }
        }
    }
}
