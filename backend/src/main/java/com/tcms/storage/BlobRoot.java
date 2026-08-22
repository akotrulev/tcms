package com.tcms.storage;

/**
 * Which of the three blob roots a key lives under.
 *
 * <p><strong>An enum rather than a qualifier string, for this package's own reason.</strong>
 * {@link AttachmentStore} and {@link ReportStore} are two types precisely so that handing evidence
 * to the report store is a compile error rather than a screenshot silently written under the
 * reports root. This type carries the same distinction into the one place a caller genuinely has
 * to name a root as a <em>value</em> — {@link BlobInventory}, which answers "what is under this
 * prefix" and therefore cannot be three separate beans without a caller holding all three anyway.
 *
 * <p>It names roots, never bytes: nothing here opens, writes or deletes anything, so a wrong member
 * yields a wrong <em>listing</em> and never a misplaced file. That is the whole reason it is
 * acceptable where a {@code BlobStore} qualifier is not.
 */
public enum BlobRoot {

    /** Attachments — evidence, spec extracts, page images, {@code FILE} artifacts. */
    EVIDENCE("evidence"),

    /** Reports this product generated and could regenerate from {@code execution_fact}. */
    REPORTS("reports"),

    /** The verbatim file CI sent, which nothing can reproduce (§160). */
    RAW_REPORTS("raw-reports");

    private final String label;

    BlobRoot(String label) {
        this.label = label;
    }

    /**
     * The root's stable name — the same word its configuration property and its archive prefix use.
     *
     * <p>Not {@link #name()}: that would spell the third root {@code RAW_REPORTS}, and this string
     * is written into export archives that outlive the enum.
     */
    public String label() {
        return label;
    }
}
