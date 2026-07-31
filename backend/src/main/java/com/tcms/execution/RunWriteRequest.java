package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code TestRunInput} on the wire — the contract's seven properties and no more.
 *
 * <p>{@code status}, {@code source}, {@code startedAt} and the key are all absent for the reasons
 * {@link TestRun#create} gives: they are the server's to decide, and a field the client can set is a
 * claim the client can make. {@code commit_sha} and {@code branch} exist in {@code V1} and are not
 * on the contract; they arrive with CI ingestion in M3, and accepting them here would be a field
 * read and dropped.
 */
public record RunWriteRequest(
        String name,
        UUID planId,
        UUID configurationId,
        String releaseVersion,
        String buildNumber,
        List<UUID> caseIds,
        UUID assigneeId) {
}
