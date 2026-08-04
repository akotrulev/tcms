package com.tcms.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.project.UserRefView;
import java.util.UUID;

/**
 * {@code ReportDefinition} in {@code docs/specs/openapi.yaml} — a saved report as a client reads it.
 *
 * <p>{@code parameters} is a {@link JsonNode} rather than the stored text: the contract declares an
 * object, and a string there would be a JSON document a client has to parse a second time — the
 * shape §90 refused on the way in, refused here on the way out for the same reason.
 *
 * <p>{@code scheduleCron}, {@code sharedWithTeamId} and {@code createdAt} are columns this view does
 * not carry. The first two are unwritten (see {@link ReportDefinition}); {@code createdAt} decides
 * the list's order and is otherwise not something the contract promised, so adding it here would be
 * a field a screen starts rendering before anybody decided it should.
 *
 * @param owner always present and always the caller, because the list is owner-scoped. It is in the
 *     contract as a required field anyway, so that a shared definition — the day sharing exists —
 *     does not need a new shape to say whose it is.
 */
record ReportDefinitionView(UUID id, String type, String name, JsonNode parameters,
        UserRefView owner) {

    static ReportDefinitionView of(ReportDefinition definition, UserRefView owner) {
        return new ReportDefinitionView(
                definition.getId(),
                definition.getType(),
                definition.getName(),
                ReportParameters.parse(definition.getParameters()).node(),
                owner);
    }
}
