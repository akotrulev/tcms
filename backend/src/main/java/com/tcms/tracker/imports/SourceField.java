package com.tcms.tracker.imports;

/**
 * One field the source project actually holds values for, and how many.
 *
 * <p>The population is <em>fields with at least one value on at least one issue in this project</em>,
 * not the fields defined on the site. The preview's question is "how much would be lost", and a
 * field somebody defined and nobody filled in loses nothing — listing it would put twenty rows on a
 * screen whose whole job is to be believed.
 *
 * @param name      the source's own display name, which is what the report shows a year later
 * @param type      the source's own word for the shape — {@code number}, {@code option},
 *                  {@code array}. Passed through rather than translated: the report is about the
 *                  system being left behind, so its vocabulary is the honest one.
 * @param custom    whether the source calls this a custom field. It is what decides between the
 *                  mockup's {@code Custom: "Squad"} row and a bare field name — calling a built-in
 *                  field custom would be this product guessing about somebody else's product.
 * @param valueCount how many issues hold a value for it
 */
public record SourceField(String name, String type, boolean custom, int valueCount) {
}
