package com.tcms.tracker.imports;

import java.util.UUID;

/**
 * One source status, its issue count, and the target this project would suggest — the contract's
 * {@code PreviewStatusRow}.
 *
 * <p>A <em>suggestion</em>, and the distinction is the whole of the mapping step. The run refuses a
 * status with no mapping in the confirmed request ({@code 400}), and it refuses it whether or not
 * this row carried a suggestion: an unmapped status silently becoming the default would put its
 * issues in the wrong column, which is exactly what {@code import-mapping.html}'s disabled Confirm
 * exists to prevent, and the server does not trust the client to have prevented it.
 *
 * @param matched           true when the names are equal ignoring case — the mockup's
 *                          <em>name matched</em> badge, and the only claim this product will make
 *                          about a status somebody else named
 * @param suggestedStatusId null when nothing matched, and the mapping step then shows it unmapped
 */
public record PreviewStatusRow(
        String sourceStatusName, int count, boolean matched, UUID suggestedStatusId) {
}
