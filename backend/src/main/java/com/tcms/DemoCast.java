package com.tcms;

import java.util.Map;
import java.util.UUID;

/**
 * Who is available to author the content of one company, resolved once so the content seeders do
 * not each look people up by address.
 *
 * <p>Authorship is not decoration here. Several screens exist to show <em>who</em> — the review
 * panel, the case history, the board's assignee column, "my work" — and a fixture where one
 * account wrote everything renders all four of them as the same name repeated. So each seeder
 * picks an author whose role could plausibly have done that thing, and asks this for it.
 *
 * @param adminId the Company Admin, and the fallback: a company whose plan lists no people still
 *                has to get its content from somebody.
 */
record DemoCast(UUID companyId, UUID adminId, Map<String, UUID> peopleByEmail) {

    /** The Test Lead, who approves things and closes runs. */
    UUID lead() {
        return firstMatching("lead@");
    }

    /** The busier of the two engineers — most authored content is theirs. */
    UUID engineer() {
        return firstMatching("engineer@");
    }

    /** The second engineer, so a review has somebody other than the author to be reviewed by. */
    UUID tester() {
        UUID found = firstMatching("tester@");
        return found.equals(adminId) ? engineer() : found;
    }

    /** The read-only stakeholder. Authors nothing; exists so the Viewer render can be looked at. */
    UUID viewer() {
        return firstMatching("viewer@");
    }

    private UUID firstMatching(String emailPrefix) {
        return peopleByEmail.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(emailPrefix))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(adminId);
    }
}
