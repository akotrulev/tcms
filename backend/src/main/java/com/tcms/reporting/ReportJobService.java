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
 * deployment actually has ({@link ReportComputations#find}, {@link ReportRenderers#supports}) rather
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
 */
@Service
public class ReportJobService {

    /** Roles spec §A5 / {@code V3}. Generating a report is exporting one. */
    private static final String EXPORT_REPORT = "report:export";

    private final ProjectService projects;
    private final ReportJobRepository jobs;
    private final ReportComputations computations;
    private final ReportRenderers renderers;
    private final Clock clock;

    public ReportJobService(
            ProjectService projects,
            ReportJobRepository jobs,
            ReportComputations computations,
            ReportRenderers renderers,
            Clock clock) {

        this.projects = projects;
        this.jobs = jobs;
        this.computations = computations;
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
     * @param parameters the raw JSON body of {@code parameters}, stored verbatim. It is what
     *     {@code report_artifact.filter_snapshot} is later set from, which is what makes a number
     *     read six months later explainable ({@code V1}: "a report you cannot reproduce is a report
     *     nobody trusts").
     */
    @Transactional
    public Outcome request(
            UUID userId, UUID projectId, String type, String format, String parameters) {

        UUID companyId = CompanyContext.require();

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, EXPORT_REPORT,
                project -> accept(companyId, project, type, format, parameters, userId));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    private Outcome accept(
            UUID companyId,
            Project project,
            String type,
            String format,
            String parameters,
            UUID userId) {

        Optional<ReportFormat> parsed = parseFormat(format);

        Map<String, List<String>> errors = validate(type, format, parsed, parameters);
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        ReportJob job = ReportJob.request(
                companyId,
                project.getId(),
                // R5's saved definitions do not exist yet, so every job in R3 is ad-hoc. The column
                // is nullable for exactly this and the entity takes it rather than defaulting it,
                // so the day a definition can be named there is one call site to change.
                null,
                type,
                // Present: `validate` refused every other case above, and the job cannot be written
                // without one.
                parsed.orElseThrow(),
                parameters == null || parameters.isBlank() ? "{}" : parameters,
                userId,
                clock.instant());

        jobs.save(job);
        return new Outcome.Accepted(ReportJobView.of(job));
    }

    /**
     * The three request-time refusals, collected rather than short-circuited.
     *
     * <p>All of them at once, because they are independent and a client that has sent both an
     * unknown type and an unrenderable format should learn both in one round trip — the alternative
     * is a form that reveals its next objection only after the first is fixed.
     */
    private Map<String, List<String>> validate(
            String type, String format, Optional<ReportFormat> parsed, String parameters) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (type == null || type.isBlank()) {
            errors.put("type", List.of("Say which report to generate. This server can produce: "
                    + String.join(", ", computations.supported()) + "."));
        } else if (computations.find(type).isEmpty()) {
            errors.put("type", List.of("This server has no report called '" + type
                    + "'. It can produce: " + String.join(", ", computations.supported()) + "."));
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
