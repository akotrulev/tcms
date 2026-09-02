package com.tcms.tracker.imports;

/**
 * What one write pass produced — the four KPI tiles on {@code import-report.html}, plus the dropped
 * total beneath them.
 *
 * <p>A record rather than five parameters on {@link ImportRun#complete}, because five consecutive
 * {@code int}s at a call site is a defect nothing catches: transposing {@code comments} and
 * {@code labels} compiles, passes every type check, and shows a customer the wrong number on the
 * one screen this module exists to produce.
 *
 * @param droppedValues the sum of every dropped field's {@code valueCount} — how many <em>values</em>
 *                      were lost, not how many field types, which is the number
 *                      {@code import-report.html}'s tile shows and the question D-f says a
 *                      paragraph cannot answer
 */
record ImportCounts(int issues, int releases, int comments, int labels, int droppedValues) {
}
