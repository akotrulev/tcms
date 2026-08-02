package com.tcms.documentation;

/**
 * What an artifact actually <em>is</em> — plan §6n's four kinds, one register.
 *
 * <p>The set is deliberately wider than what {@link ArtifactWriteService} accepts. Two of these are
 * <strong>references</strong> and can be registered today; two name bytes or a generated document
 * that nothing in this system can yet produce. The enum carries all four because the register has to
 * <em>render</em> a row of any kind — a database seeded by a later slice, or by an operator, must not
 * make the list endpoint throw — while the write path refuses the two it cannot honour.
 *
 * <p>That asymmetry is the point rather than an oversight, and it is why {@code ArtifactInput.kind}
 * is a narrower enum in the contract instead of this one with a runtime check: a client generating
 * types from the contract cannot even construct the request that would be refused.
 */
public enum ArtifactKind {

    /**
     * Uploaded bytes. <strong>Not registrable yet</strong> — needs the storage port (open question
     * #28, resolved as a filesystem adapter with S3 later), which means the bytes proxy through the
     * API and a request-size limit must exist before the endpoint does.
     */
    FILE,

    /**
     * A pointer to a page in this project's documentation space.
     *
     * <p>The option plan §6n steers toward, and the reason this module is the register's home: a
     * test strategy <em>authored</em> as a doc page cannot rot the way an uploaded copy of one does,
     * because there is only ever one of it.
     */
    DOC_PAGE_REF,

    /**
     * A link out — Confluence, SharePoint, a Git path.
     *
     * <p>Kept because pretending everything will be migrated in is how people stop using a register
     * altogether (plan §6n). The cost is that the register renders somebody's string as a link, so
     * the scheme is validated on the way in.
     */
    EXTERNAL_URL,

    /**
     * A report (plan §6e) pinned into the register, freezing that output as a cycle artifact.
     *
     * <p><strong>Not registrable yet</strong> — the {@code reporting} module does not exist, so
     * there is no report to pin.
     */
    GENERATED_REPORT
}
