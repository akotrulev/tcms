package com.tcms.tracker.board;

/** {@code POST /projects/{projectId}/labels} and {@code PATCH /labels/{labelId}}. */
public record LabelInput(String name, String color) {
}
