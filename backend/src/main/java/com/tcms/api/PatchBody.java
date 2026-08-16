package com.tcms.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A {@code PATCH} body, bound <strong>and</strong> asked which fields it actually carried (B1).
 *
 * <h2>The defect this exists to end</h2>
 *
 * <p>Jackson gives an absent field and an explicit {@code null} the same Java value, so until this
 * class every {@code PATCH} in this API had to pick one meaning for both — and picked
 * <em>clear it</em>. That is the safe reading for exactly one client, the SPA, whose editor submits
 * every field it renders on every save. For everybody else it is a trap with no symptom:
 * {@code {"title": "x"}} renamed a case and emptied its objective, its expected outcome, its
 * estimate and its automation key, and answered 200. §143 found it from the far end — the CLI's
 * {@code case edit --title} had never once worked — and the fix there was
 * {@code @tcms/client}'s {@code core/rewrite.ts}, a read-modify-write on the client, which is a
 * workaround for a server that cannot be asked what it was sent.
 *
 * <h2>How it works, and why it is not a Jackson feature</h2>
 *
 * <p>The body binds as a {@link JsonNode} and is converted to the endpoint's own request record with
 * the application's {@link ObjectMapper} — so binding, coercion and unknown-property handling are
 * unchanged — while the node is kept to answer {@link #has(String)}. The alternative is
 * {@code JsonNullable} (a dependency, and a wrapper on every component of every request record) or
 * {@code Optional<T>} components (which Jackson supports and records do not, since a record's
 * canonical constructor cannot distinguish an absent optional from an empty one without a module
 * that has to be configured per type). One class, no dependency, and it is opt-in per endpoint.
 *
 * <h2>Top-level fields only, deliberately</h2>
 *
 * <p>{@link #has(String)} answers about the object's own keys and does not descend. Nothing in this
 * API has a nested-object patch — the nested shapes are {@code steps}, {@code preconditions} and
 * their kin, which are <em>lists</em>, and a list has an empty spelling of its own ({@code []}) so
 * it never needed presence to be readable. Descending would also mean deciding what a present-but-
 * empty nested object means, which is a question no operation here asks.
 *
 * <p>The component is called {@code body} rather than {@code node}, and that is a rule rather than
 * a preference: {@code reporting.ReportParameters} is {@code (JsonNode node)}, and
 * {@code ArchitectureRulesTest} reads two records with the same component set as one shape copied.
 * They are different concepts — a report's parameter object, and a request body — so each is named
 * for the concept it is, which is the resolution that rule's own message asks for.
 *
 * @param body the body as sent, never null — an empty body is {@code {}} and answers false to
 *     everything, which is a legal {@code PATCH} meaning "change nothing".
 */
public record PatchBody(JsonNode body) {

    /** Whether the caller sent this field at all — <em>including</em> sending it as {@code null}. */
    public boolean has(String field) {
        return body != null && body.has(field);
    }

    /** Every field the caller sent, in wire order. For a service that wants the whole set. */
    public Set<String> fields() {
        Set<String> names = new LinkedHashSet<>();
        if (body != null) {
            body.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    /**
     * The body as the endpoint's own request record.
     *
     * @throws MalformedBody when the JSON cannot be read as that shape — an unknown property, a
     *     value of the wrong type, a string that is not one of an enum's members. Before B1 the
     *     message converter raised this one layer earlier and Spring answered 400; the exception is
     *     how that answer survives the body being bound loosely, and every controller that binds a
     *     {@link PatchBody} has to translate it (there is no global handler for it on purpose — a
     *     400 whose body is not this API's {@code Problem} is a shape no client parses).
     */
    public <T> T as(ObjectMapper mapper, Class<T> shape) {
        try {
            return mapper.treeToValue(body, shape);
        } catch (JsonProcessingException unreadable) {
            throw new MalformedBody(unreadable);
        }
    }

    /** What {@link #as} throws. Unchecked, so a controller translates it where it answers. */
    public static class MalformedBody extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MalformedBody(Throwable cause) {
            super("The request body could not be read.", cause);
        }
    }
}
