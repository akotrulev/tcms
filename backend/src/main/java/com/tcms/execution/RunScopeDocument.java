package com.tcms.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;

/**
 * The one writer of the {@code {planId, caseIds, suiteIds, tags}} document ({@code V61}, §247).
 *
 * <h2>Why this is a class and not a private method</h2>
 *
 * <p>It was {@code RunCreateService#scopeRequestOf}, private, and it had exactly one caller until
 * {@code V68} gave the same document a second home on {@code run_schedule}. Copying the eight lines
 * would have made "the schedule's scope and the run's scope are the same bytes" a claim two methods
 * agree on today rather than a property of the code — and the whole argument for reusing
 * {@code V61}'s shape instead of inventing a second encoding is that <strong>one piece of code
 * writes it</strong>. {@link RunScopeRequestView#parse} is the reading half and was already shared.
 *
 * <h2>Normalised the way the resolution normalised it</h2>
 *
 * <p>De-duplicated ids, trimmed tags — so what is recorded is what the server acted on rather than
 * the bytes that arrived. A caller that named the same suite twice asked for one suite.
 *
 * <p>The document is <strong>the request, not the case set</strong>. A suite named here may since
 * have been deleted and a tag may now match nothing; that is the point, on a run because it makes
 * the difference from the checklist visible, and on a schedule because the arms are re-resolved on
 * every fire.
 */
final class RunScopeDocument {

    private RunScopeDocument() {
    }

    /**
     * Builds the document from a request.
     *
     * @param json this module's own mapper, passed rather than held so there is no second
     *             {@code ObjectMapper} anywhere in {@code execution}
     */
    static String of(ObjectMapper json, RunWriteRequest request) {
        ObjectNode node = json.createObjectNode();
        if (request.planId() != null) {
            node.put("planId", request.planId().toString());
        }
        putIds(node, "caseIds", PlanEdit.distinct(request.caseIds()));
        putIds(node, "suiteIds", PlanEdit.distinct(request.suiteIds()));
        ArrayNode tags = node.putArray("tags");
        RunEdit.tagsOf(request).forEach(tags::add);
        return node.toString();
    }

    private static void putIds(ObjectNode node, String field, List<UUID> ids) {
        ArrayNode array = node.putArray(field);
        ids.forEach(id -> array.add(id.toString()));
    }
}
