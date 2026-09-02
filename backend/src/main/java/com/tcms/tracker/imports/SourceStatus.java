package com.tcms.tracker.imports;

/**
 * One status the source project uses, and how many of its issues are in it.
 *
 * <p>The count is what makes the mapping step legible: <em>"In Review — 41 issues"</em> tells
 * somebody which of the seventeen statuses they actually have to think about.
 *
 * @param name     the source's own spelling, which is also the key the applied mapping is stored
 *                 under — the source system will be gone when the report is read (D-g)
 * @param category the source's coarse grouping ({@code To Do} / {@code In Progress} / {@code Done}
 *                 for Jira), used only to seed a suggestion when the names do not match
 * @param count    live issues in this status
 */
public record SourceStatus(String name, String category, int count) {
}
