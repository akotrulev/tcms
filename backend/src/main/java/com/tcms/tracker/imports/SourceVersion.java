package com.tcms.tracker.imports;

/**
 * A fix version in the source, which becomes a release here (D6).
 *
 * @param name     the version's own name, which becomes the release's
 * @param released whether the source considers it shipped
 */
public record SourceVersion(String name, boolean released) {
}
