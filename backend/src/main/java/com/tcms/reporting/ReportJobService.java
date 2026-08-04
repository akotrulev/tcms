package com.tcms.reporting;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The request half of the pipeline: everything that happens while the client is still on the phone.
 *
 * <p>One method, and it writes one row. {@link ReportJobRunner} is the other half and shares nothing
 * with this one but the table — which is the point of the job record: §6e's {@code 202} exists
 * because report generation outlives the request that asked for it, and an {@code @Async} dispatch
 * would be lost on a restart, leaving a client polling a job id that resolves to nothing with no
 * symptom anywhere but a spinner.
 *
 * <h2>Everything a request can be refused for is refused HERE, not by the job</h2>
 *
 * <p>An unknown report type and an unrenderable format are both things the server knows the instant
 * it is asked. Accepting the job anyway and letting it go {@code FAILED} thirty seconds later is the
 * shape that cannot be told apart from a broken server: the client watches a spinner, gets a
 * terminal state, and has to poll a second endpoint to read a message that was always available. So
 * both are {@code 400}s at request time, and both are decided by asking the registries what this
 * deployment actually has ({@link ReportTypes#refusal}, {@link ReportRenderers#supports}) rather
 * than by a literal list beside the check — a list is correct today and silently wrong the day R4
 * lands the HTML renderer, because nothing would fail.
 *
 * <p><strong>What is deliberately still refused late</strong>, and it is worth naming because it
 * looks like an oversight: a report type's <em>own</em> parameters — a {@code runId} that is absent,
 * or names a run in another project — are checked by {@link ReportComputation#compute}, inside the
 * worker, and surface as a {@code FAILED} job carrying that sentence. Validating them here would
 * mean either a second copy of each report's parameter rules (which is the copy that drifts, §58 E1)
 * or a database read per report type on the request path. The parse itself <em>is</em> checked here,
 * because malformed JSON is not a property of any report.
 *
 * <h2>{@code report:export}, and no new key</h2>
 *
 * <p>{@code V3} has seeded {@code report:view} and {@code report:export} since M0 and this is the
 * latter's first consumer in Java. Generating is exporting — the artifact is a file somebody
 * downloads — so it costs the export key, while the poll and the on-screen view cost
 * {@code report:view} (R3 phase 5). Inventing a {@code report:generate} would be a key {@code V3}
 * seeds to nobody, which is a 403 no administrator can grant their way out of: §67's argument
 * against {@code evidence:read}, §79's against {@code suite:comment}, §80's and §82's since.
 *
 * <p>The gate is {@link ProjectService#writeGranted}, so an unreachable project is a bodiless 404
 * <em>before</em> any permission is consulted and a 403 never tells a caller that a project they
 * cannot see exists (§6t).
 *
 * <h2>A saved definition is read here and never trusted as an authorization (§91)</h2>
 *
 * <p>{@code definitionId} names a row this caller owns in this project — the repository has no
 * method that could find any other kind — so regenerating a saved report needs the same
 * {@code report:export} as an ad-hoc one and a definition cannot carry access to a project the
 * caller was not already gated onto. What it carries is the report's <em>type</em> and the base for
 * its parameters, which is why the gate runs first and the definition is resolved inside it.
 */
@Service
public class ReportJobService {

    /** Roles spec §A5 / {@code V3}. Generating a report is exporting one. */
    private static final String EXPORT_REPORT = "report:export";

    private final ProjectService projects;
    private final ReportJobRepository jobs;
    private final ReportDefinitionRepository definitions;
    private final ReportTypes types;
    private final ReportRenderers renderers;
    private final Clock clock;

    public ReportJobService(
            ProjectService projects,
            ReportJobRepository jobs,
            ReportDefinitionRepository definitions,
            ReportTypes types,
            ReportRenderers renderers,
            Clock clock) {

        this.projects = projects;
        this.jobs = jobs;
        this.definitions = definitions;
        this.types = types;
        this.renderers = renderers;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** {@code report:export} is not held on this project. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, so the form lands each one on its own input. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /**
         * Queued. 202, with the whole job rather than only the id the client polls.
         *
         * <p>{@link ReportJobView}'s header argues it: a client given one shape on the accept and a
         * different one from the poll holds two readings of "where is my report", and the accept's
         * is the one never exercised again.
         */
        record Accepted(ReportJobView job) implements Outcome {
        }
    }

    /**
     * Records a generation request, or refuses it.
     *
     * <p>The row is written {@code PENDING} and nothing else happens: the worker picks it up on its
     * next tick. That is the whole latency budget in v1 and it is deliberately not shortened by
     * running the report on this thread — a synchronous generate is a request that holds a
     * connection for as long as the report takes, on the one endpoint whose cost the product does
     * not choose.
     *
     * @param format the requested format <strong>as text</strong>, not as a {@link ReportFormat}.
     *     The parse is a refusal like the other two and belongs beside them: a value that is not a
     *     format at all and one this deployment cannot render are the same mistake from the caller's
     *     side, and answering them in two places is how they end up worded two ways — one of them by
     *     Jackson, about deserialization, to a person who asked for a PDF.
     * @param definitionId a saved report to regenerate (§91), or null for an ad-hoc request. It
     *     supplies the type and the <em>base</em> for the parameters; see {@link #resolve}.
     * @param parameters the raw JSON body of {@code parameters}. Stored verbatim for an ad-hoc
     *     request and as the <em>merge</em> when a definition was named — what has to be
     *     reproducible is what the report was computed over, not what the caller typed. It is what
     *     {@code report_artifact.filter_snapshot} is later set from, which is what makes a number
     *     read six months later explainable ({@code V1}: "a report you cannot reproduce is a report
     *     nobody trusts").
     */
    @Transactional
    public Outcome request(
            UUID userId,
            UUID projectId,
            UUID definitionId,
            String type,
            String format,
            String parameters) {

        UUID companyId = CompanyContext.require();

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, EXPORT_REPORT,
                project -> accept(
                        companyId, project, definitionId, type, format, parameters, userId));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    private Outcome accept(
            UUID companyId,
            Project project,
            UUID definitionId,
            String type,
            String format,
            String parameters,
            UUID userId) {

        Optional<ReportDefinition> definition = definitionId == null
                ? Optional.empty()
                : definitions.findByIdAndProjectIdAndOwnerId(
                        definitionId, project.getId(), userId);

        Optional<ReportFormat> parsed = parseFormat(format);

        Map<String, List<String>> errors =
                validate(definitionId, definition, type, format, parsed, parameters);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        Resolved resolved = resolve(definition, type, parameters);

        ReportJob job = ReportJob.request(
                companyId,
                project.getId(),
                // Null for an ad-hoc request, which every job was before §91. The column is
                // nullable for exactly this.
                definitionId,
                resolved.type(),
                // Present: `validate` refused every other case above, and the job cannot be written
                // without one.
                parsed.orElseThrow(),
                resolved.parameters(),
                userId,
                clock.instant());

        jobs.save(job);
        return new Outcome.Accepted(ReportJobView.of(job));
    }

    /** What the job is actually written with, once a saved definition has had its say. */
    private record Resolved(String type, String parameters) {
    }

    /**
     * The saved definition's answer, overridden by the request's.
     *
     * <p>The type is the <em>definition's</em> whenever there is one — the request may repeat it and
     * may not contradict it, which {@link #validate} refuses — because the alternative is a request
     * that names one report and a saved definition of another, where picking either silently
     * generates a report nobody asked for.
     *
     * <p>The parameters are merged rather than replaced, so "the same cycle report, but for last
     * week" is one field instead of a re-sent copy of the whole selection.
     * {@link ReportParameters#merge} states why the merge is shallow.
     */
    private Resolved resolve(
            Optional<ReportDefinition> definition, String type, String parameters) {

        return definition
                .map(saved -> new Resolved(
                        saved.getType(), ReportParameters.merge(saved.getParameters(), parameters)))
                .orElseGet(() -> new Resolved(
                        type, parameters == null || parameters.isBlank() ? "{}" : parameters));
    }

    /**
     * The request-time refusals, collected rather than short-circuited.
     *
     * <p>All of them at once, because they are independent and a client that has sent both an
     * unknown type and an unrenderable format should learn both in one round trip — the alternative
     * is a form that reveals its next objection only after the first is fixed.
     *
     * <h2>A named definition changes what {@code type} means, and there are three cases</h2>
     *
     * <p>With a definition, the type is <strong>the definition's</strong>: an absent {@code type} is
     * no longer a mistake (regenerating a saved report is one field), and a {@code type} that
     * <em>disagrees</em> with it is refused rather than resolved — a client holding a stale copy of
     * a definition it has since changed would otherwise get a report of the wrong thing, rendered
     * perfectly. Without one, the type is required exactly as it has been since §87.
     *
     * <p>A {@code definitionId} that resolves to nothing is a <strong>field error, not a 404</strong>:
     * the caller can see the project and asked for something in it, and the row they named is either
     * gone or somebody else's — which they cannot be told apart, for {@code deleteReportDefinition}'s
     * reason.
     */
    private Map<String, List<String>> validate(
            UUID definitionId,
            Optional<ReportDefinition> definition,
            String type,
            String format,
            Optional<ReportFormat> parsed,
            String parameters) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (definitionId != null && definition.isEmpty()) {
            errors.put("definitionId", List.of(
                    "You have no saved report with that id in this project."));
        } else if (definition.isPresent()) {
            String saved = definition.get().getType();
            if (type != null && !type.isBlank() && !type.trim().equals(saved)) {
                errors.put("type", List.of("This saved report is a '" + saved + "'. Ask for that, "
                        + "or leave 'type' out and let the saved report say."));
            } else {
                // A definition outlives the release it was saved in, so its type is checked here
                // as well as at save time: a report retired from a deployment leaves saved rows
                // naming it, and without this the job is accepted and dies in the worker with the
                // registry's internal sentence rather than the catalogue.
                types.refusal(saved).ifPresent(message -> errors.put("type", List.of(message)));
            }
        } else {
            // The same refusal the save endpoint makes, in one place since §91 — see ReportTypes.
            types.refusal(type).ifPresent(message -> errors.put("type", List.of(message)));
        }

        if (format == null || format.isBlank()) {
            errors.put("format", List.of("Say which format to render. This server can produce: "
                    + supportedFormats() + "."));
        } else if (parsed.isEmpty()) {
            // Not a format the schema even knows. Worded as "cannot render" rather than "unknown"
            // on purpose: the two are the same fact to the caller — this file will not arrive — and
            // the only useful half of the sentence is the list of what will.
            errors.put("format", List.of("This server cannot render '" + format
                    + "'. It can produce: " + supportedFormats() + "."));
        } else if (!renderers.supports(parsed.get())) {
            errors.put("format", List.of(refusalFor(parsed.get())));
        }

        if (parameters != null && !parameters.isBlank()) {
            try {
                ReportParameters.parse(parameters);
            } catch (IllegalArgumentException malformed) {
                // The message, never the exception: this is rendered to a person, and the parser's
                // sentences are written for that (see ReportParameters' header).
                errors.put("parameters", List.of(malformed.getMessage()));
            }
        }

        return errors;
    }

    /**
     * Why this format cannot be produced.
     *
     * <p>PDF gets a sentence of its own because it is the one a client is most likely to ask for and
     * the only one the refusal has a <em>reason</em> for: the renderer needs a headless browser that
     * is deliberately not in the image (slice plan §8). "Not supported" would read as a bug in the
     * request; "not installed on this server" is the truth and names the thing an operator could
     * change.
     */
    private String refusalFor(ReportFormat format) {
        if (format == ReportFormat.PDF) {
            return "PDF rendering is not installed on this server. It can produce: "
                    + supportedFormats() + ".";
        }
        return "This server cannot render " + format + ". It can produce: "
                + supportedFormats() + ".";
    }

    /**
     * The requested text as a {@link ReportFormat}, if it names one at all.
     *
     * <p>Case-insensitive, because the contract's enum is upper case and a client sending
     * {@code "md"} has made no mistake worth a refusal — the alternative is a 400 whose fix is
     * invisible in the message.
     */
    private static Optional<ReportFormat> parseFormat(String requested) {
        if (requested == null) {
            return Optional.empty();
        }
        for (ReportFormat format : ReportFormat.values()) {
            if (format.name().equalsIgnoreCase(requested.trim())) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    private String supportedFormats() {
        List<String> names = new ArrayList<>();
        for (ReportFormat supported : renderers.supported()) {
            names.add(supported.name());
        }
        return String.join(", ", names);
    }
}
