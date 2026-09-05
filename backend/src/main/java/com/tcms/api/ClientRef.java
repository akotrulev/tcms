package com.tcms.api;

import java.util.UUID;

/**
 * A client named from somewhere that is not the client screen — a project card, the project
 * overview's chip, a picker option.
 *
 * <p><strong>Two fields on purpose.</strong> Every one of those places renders a name and links to
 * an id; anything more here would make every project read carry a client record.
 *
 * <p><strong>It lives in {@code api} because two modules serve it and neither may import the
 * other.</strong> {@code crm} owns the {@code client} table; {@code project} stores the id and must
 * serve the name on {@code ProjectView}, and {@code project} is a foundation module that may not
 * depend on {@code crm}. Declaring the shape twice would be two records over one wire type, which
 * {@code ArchitectureRulesTest}'s repeated-shape sweep exists to catch — so it is declared once,
 * here, beside {@link UserRef}, which is in this package for exactly the same reason.
 *
 * <p>Nothing on this record is a permission target. A Client carries no scope (scope D1): it is not
 * a third gate, and seeing one of these means only that you could see the project or the client it
 * came from.
 */
public record ClientRef(UUID id, String name) {
}
