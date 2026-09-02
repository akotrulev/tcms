package com.tcms.tracker.imports;

/**
 * One project the credential can see, for the connect step's dropdown and its
 * <em>"4 projects visible to this account"</em> line.
 *
 * @param key        {@code PROJ} — the prefix imported issues keep (D5). Not a TCMS project key.
 * @param name       what the source calls it
 * @param issueCount the source's own count, used before anything is read whole: the preview refuses
 *                   above {@code tcms.tracker.imports.max-issues} naming this number, rather than
 *                   letting a run die at minute four
 */
public record SourceProject(String key, String name, int issueCount) {
}
