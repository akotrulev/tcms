package com.tcms.tracker.imports;

import java.util.UUID;

/**
 * One line of the confirmed mapping — <em>"Awaiting Triage becomes Backlog"</em>.
 *
 * <p>Keyed on the source's <strong>name</strong> rather than on an id, because the source system is
 * the thing being left behind: its status ids are meaningless here the moment the site is
 * decommissioned, and the name is what the preview showed and what the report has to say a year
 * later (D-g).
 *
 * @param targetStatusId one of <em>this project's</em> statuses. A status belonging to another
 *                       project is a 400 on this field and never a silent default — an unmapped
 *                       status would put its issues in the wrong column of a board, which is
 *                       exactly what {@code import-mapping.html}'s disabled Confirm exists to
 *                       prevent and what the server refuses again rather than trusting the client.
 */
public record StatusMappingEntry(String sourceStatusName, UUID targetStatusId) {
}
