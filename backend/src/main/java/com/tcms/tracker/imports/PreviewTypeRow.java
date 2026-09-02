package com.tcms.tracker.imports;

import com.tcms.tracker.IssueLevel;
import java.util.UUID;

/**
 * One source issue type and the project type it would become — the contract's {@code PreviewTypeRow}.
 *
 * <p>Type mapping is not confirmed by anybody, unlike the status mapping: a source type maps by
 * name and then by level, and both fall back on something that exists. A wrong type is a chip with
 * the wrong letter on it; a wrong status puts an issue in the wrong column of a board, which is why
 * that one is a step in the wizard and this one is a table to read.
 *
 * @param targetLevel            what the depth rule will treat it as (scope §4 — levels, never names)
 * @param countsTowardCompletion whether issues of the target type move a percentage. Shown because
 *                               a source's {@code Spike} landing on a type that counts is a
 *                               completion figure quietly changing meaning.
 */
public record PreviewTypeRow(
        String sourceTypeName,
        UUID targetTypeId,
        String targetTypeName,
        IssueLevel targetLevel,
        boolean countsTowardCompletion,
        int count) {
}
