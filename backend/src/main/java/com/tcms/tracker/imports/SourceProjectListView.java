package com.tcms.tracker.imports;

import java.util.List;

/**
 * The contract's {@code SourceProjectList} — an object with one array, rather than a bare array.
 *
 * <p>A bare array is what the operation naturally returns and is deliberately not what it serves:
 * the connect step also wants to say <em>"4 projects visible to this account"</em>, and a response
 * that is already an object is the one that can grow a count or a truncation flag without becoming
 * a different response.
 */
public record SourceProjectListView(List<SourceProjectView> projects) {
}
