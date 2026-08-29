package com.tcms.provisioning;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What must not leave this instance inside an export archive.
 *
 * <p><strong>Enumerated, never pattern-matched.</strong> Four {@code content_hash} columns are
 * content addresses of the client's own version snapshots, and a blanket "strip anything matching
 * %hash%" rule would destroy the integrity check on every snapshot in the archive — the opposite of
 * what an export is for. So the list is written out and {@code ExportRedactionSweepTest} is the
 * pattern: it sweeps {@code information_schema.columns} and fails the build when a column whose
 * name suggests a credential is neither redacted here, nor in a table {@link ExportTables} excludes,
 * nor on {@link #DELIBERATELY_KEPT} with a reason.
 *
 * <p>The archive goes to a client, a data-protection officer, a ticket attachment. Ciphertext is
 * redacted on the same terms as a digest: "encrypted with a key you do not have" is a property of
 * today's holder, not of every future one.
 */
final class ExportRedactions {

    /** {@code table -> columns dropped from every exported row of it}. */
    static final Map<String, Set<String>> DROPPED_COLUMNS = Map.of(
            // A bcrypt digest of a password people reuse. V1:81.
            "app_user", Set.of("password_hash"),
            // ~~The digest an API token is checked against. V1:337.~~ → THE COLUMN IS GONE.
            // `V36__api_token_lookup.sql:75` DROPPED `api_token.token_hash` and moved the digest
            // to `api_token_lookup.token_hash`, which `ExportTables` excludes WHOLESALE as
            // CREDENTIAL_ONLY — so the secret is not exported and never was. What this entry was,
            // by the time it was written, is a redaction of a column that does not exist: it made
            // the list look complete while naming nothing, which is the exact failure
            // `ExportRedactionSweepTest.nothingIsKeptOrDroppedForAColumnThatIsGone` exists to
            // catch, and it caught it on this list's first execution. `api_token`'s remaining
            // columns are all KEPT — the client must be able to see which integrations existed and
            // when they were revoked, and `token_prefix` is a deliberate partial disclosure with
            // its own KEPT_COLUMNS entry.
            // An unconsumed invitation digest is a live admission to the company. V6:70.
            "membership_invitation", Set.of("token_hash"),
            // AES-GCM ciphertext of a webhook URL, and a webhook URL IS the authentication for the
            // room it posts to. V54:130. `secret_hint` is KEPT: its own column comment calls it
            // "the only part of the credential that ever leaves this table", and it is what lets a
            // client recognise WHICH webhook was configured.
            "notification_channel", Set.of("secret"),
            // The OIDC confidential-client secret (V53:37) and the LDAP bind password (V57:31).
            "identity_provider", Set.of("client_secret_encrypted", "bind_password_encrypted"),
            // A person's own tracker PAT and its OAuth successor, both AES-GCM ciphertext (V73:175,
            // V73:178). `notification_channel`'s shape exactly, and one degree worse: this
            // credential is not the instance's, it is an individual's, and it authenticates them in
            // somebody else's Jira. `secret_hint` is KEPT — see DELIBERATELY_KEPT, and V73's own
            // column comment: "Recognition, never a credential."
            "tracker_identity", Set.of("secret", "refresh_token"));

    /**
     * Columns the sweep would flag that are exported deliberately. <strong>Every entry needs a
     * reason, and adding one is a decision</strong> — the same rule
     * {@code SchemaConventionTest.INSTANCE_LEVEL_TABLES} carries.
     *
     * <p>Entries whose column name contains no {@link #SUSPECT_NAME_FRAGMENTS} fragment are still
     * listed where the plan named them: they document a judgement somebody will otherwise re-make
     * ("is a storage key a credential?"), and {@code nothingIsKeptOrDroppedForAColumnThatIsGone}
     * keeps every one of them pinned to a column that exists.
     */
    static final Map<String, String> DELIBERATELY_KEPT = Map.ofEntries(
            Map.entry("test_case_version.content_hash", "content address of the client's own case"),
            Map.entry("suite_version.content_hash", "content address of the client's own suite"),
            Map.entry("test_plan_version.content_hash", "content address of the client's own plan"),
            Map.entry("doc_page_version.content_hash", "content address of the client's own page"),
            Map.entry("tracker_identity.secret_hint",
                    "four end-anchored characters, and V73's column comment states the rule the "
                            + "name only hints at: \"Recognition, never a credential.\" It is "
                            + "notification_channel.secret_hint's decision one table over — a "
                            + "client reading their own export must be able to tell WHICH token a "
                            + "person had connected, and four characters is the least that answers "
                            + "it"),
            Map.entry("api_token.token_prefix",
                    "the displayed first characters — V36 added it precisely so a token can be "
                            + "named without being reproduced"),
            Map.entry("notification_channel.secret_hint",
                    "masked tail, designed to leave the table (V54's column comment)"),
            // The three token columns below are FOREIGN KEYS to api_token.id, not credentials.
            // They are the "which credential did this" half of §132's attribution, and dropping
            // them would make an exported audit trail unable to answer the question it exists for.
            Map.entry("audit_event.actor_token_id", "an api_token id — attribution, not a secret"),
            Map.entry("test_execution.executed_by_token", "an api_token id (V1:843)"),
            Map.entry("ai_proposal.created_token_id", "an api_token id (V43:129)"),
            Map.entry("company.logo_storage_key", "a blob key, not a credential"),
            Map.entry("attachment.storage_key", "a blob key — it is how the archive's bytes are found"),
            Map.entry("report_artifact.storage_key", "a blob key"),
            Map.entry("ingestion_job.raw_storage_key", "a blob key"),
            Map.entry("ingestion_job.idempotency_key", "the CI caller's own de-duplication key"),
            Map.entry("permission.key", "a permission name; the table is excluded anyway"),
            Map.entry("project.key", "the client's own project key — it is in every URL they own"));

    /**
     * The column-name shapes {@code ExportRedactionSweepTest} treats as suspect.
     *
     * <p>{@code key} is deliberately absent. Every storage key, idempotency key, permission key and
     * project key in the schema would match it, and a suspect list that flags sixty benign columns
     * is a list whose failures get waved through — which is the failure mode of the sweep itself.
     */
    static final List<String> SUSPECT_NAME_FRAGMENTS = List.of(
            "hash", "secret", "password", "token", "credential", "encrypted",
            "nonce", "verifier", "salt", "cipher", "private_key");

    private ExportRedactions() {
    }

    static Set<String> droppedFrom(String table) {
        return DROPPED_COLUMNS.getOrDefault(table, Set.of());
    }

    /** Whether a column name looks like it could carry credential material. */
    static boolean isSuspect(String columnName) {
        String lower = columnName.toLowerCase(Locale.ROOT);
        return SUSPECT_NAME_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
