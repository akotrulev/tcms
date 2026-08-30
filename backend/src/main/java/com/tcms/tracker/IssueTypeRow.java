package com.tcms.tracker;

import java.util.UUID;

/**
 * A type row, flattened, so {@link IssueEdit} can be pure.
 *
 * <p>{@link IssueEdit} decides the depth rule and it does so with no {@code EntityManager}, no
 * Spring and no clock — which is what makes its cases cheap enough to have written first. The
 * service resolves the two rows and hands them in as this.
 */
record IssueTypeRow(UUID id, String name, IssueLevel level, boolean countsTowardCompletion) {
}
