package com.tcms.project;

import java.util.UUID;

/**
 * One repository on the wire — the contract's {@code ProjectRepository}.
 *
 * @param primary computed from {@link #position}, never stored: the lowest-position row is the
 *                primary one, and a second boolean column would be a second source of truth for the
 *                same fact
 */
public record ProjectRepoView(
        UUID id,
        String name,
        String url,
        String defaultBranch,
        String subdirectory,
        String buildCommand,
        int position,
        boolean primary) {

    static ProjectRepoView of(ProjectRepo row, int lowestPosition) {
        return new ProjectRepoView(
                row.getId(),
                row.getName(),
                row.getUrl(),
                row.getDefaultBranch(),
                row.getSubdirectory(),
                row.getBuildCommand(),
                row.getPosition(),
                row.getPosition() == lowestPosition);
    }
}
