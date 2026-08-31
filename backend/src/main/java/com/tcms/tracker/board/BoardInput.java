package com.tcms.tracker.board;

import java.util.List;

/**
 * {@code POST /projects/{projectId}/boards} and {@code PATCH /boards/{boardId}}.
 *
 * <p>{@link #columns} is a <strong>replacement</strong> and not a patch: a column carrying an id is
 * kept and updated, one without is created, one whose id is absent from the array is deleted, and
 * position is the array index. Reordering columns is therefore reordering this array, which is the
 * same whole-document argument {@code PUT /projects/{projectId}/workflow} makes — a sequence of
 * per-column writes has an intermediate state in which two columns claim one position.
 */
public record BoardInput(String name, SwimlaneBy swimlaneBy, List<BoardColumnInput> columns) {
}
