package com.tcms.tracker.imports;

/**
 * One source field that is <strong>not</strong> imported, and how many values it holds — the
 * contract's {@code DroppedField}.
 *
 * <p>The same shape is served by the preview and by the report, from {@link DroppedFields}' single
 * derivation, so the two cannot disagree (D-f).
 *
 * @param name       {@code Story Points}, {@code Sprint}, {@code Custom: "Squad"}
 * @param fieldType  {@code number}, {@code option}, {@code array} — the source's own word
 * @param valueCount how many issues hold a value for it
 * @param reason     why, in the words the preview used, and stored per row rather than looked up at
 *                   read time: the sentence is part of what was said at the time
 */
public record DroppedFieldView(String name, String fieldType, int valueCount, String reason) {
}
