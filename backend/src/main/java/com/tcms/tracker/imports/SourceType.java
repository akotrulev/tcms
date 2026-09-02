package com.tcms.tracker.imports;

/**
 * One issue type the source project uses, and how many issues hold it.
 *
 * @param name    the source's own spelling — matched against {@code issue_type.name} without
 *                regard to case
 * @param subtask whether the source calls this a sub-task. It is the only structural fact a source
 *                reliably exposes about a type, and it is what maps onto {@link
 *                com.tcms.tracker.IssueLevel#TASK} when the name matches nothing.
 * @param count   issues of this type
 */
public record SourceType(String name, boolean subtask, int count) {
}
