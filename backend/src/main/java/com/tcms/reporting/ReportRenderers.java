package com.tcms.reporting;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Which formats this deployment can actually produce — <strong>derived from the renderers on the
 * classpath, never from a list somebody maintains</strong>.
 *
 * <h2>Why this is a class and not a {@code switch}</h2>
 *
 * <p>{@link ReportFormat} has six values and R3 ships two renderers. Something has to refuse the
 * other four, and the refusal is user-facing: a {@code format} the server cannot render is a
 * <strong>400 at request time</strong> naming the reason (R3 phase 5), never a job accepted into a
 * queue that fails an hour later with a client watching a spinner. The tempting spelling is a
 * literal set of the supported formats beside the validation. It would be correct today and wrong on
 * the day R4 adds the HTML renderer, because nothing would fail: the class would land, the bean
 * would be registered, and {@code format: HTML} would keep being refused by a list nobody
 * remembered. This project's standing rule is that a catalogue-wide fact needs a computed check
 * rather than a careful reading; {@link #supports} is that check.
 *
 * <h2>One renderer per format, enforced at startup</h2>
 *
 * <p>Two beans claiming the same format is a silent coin-toss over which bytes a client downloads,
 * and it would present as an intermittently-wrong artifact rather than as a wiring mistake. The
 * constructor refuses it, so the application does not start.
 */
@Component
class ReportRenderers {

    private final Map<ReportFormat, ReportRenderer> byFormat;

    ReportRenderers(List<ReportRenderer> renderers) {
        Map<ReportFormat, ReportRenderer> registry = new EnumMap<>(ReportFormat.class);
        for (ReportRenderer renderer : renderers) {
            ReportRenderer existing = registry.put(renderer.format(), renderer);
            if (existing != null) {
                throw new IllegalStateException("Two renderers claim " + renderer.format() + ": "
                        + existing.getClass().getName() + " and " + renderer.getClass().getName());
            }
        }
        this.byFormat = Map.copyOf(registry);
    }

    /** Whether a job asking for this format could be served. The 400's whole basis. */
    boolean supports(ReportFormat format) {
        return byFormat.containsKey(format);
    }

    Optional<ReportRenderer> find(ReportFormat format) {
        return Optional.ofNullable(byFormat.get(format));
    }

    /**
     * The renderer for a format already known to be supported.
     *
     * @throws IllegalStateException when it is not — reached only if a job was accepted for a format
     *     {@link #supports} refuses, which is a bug in the validation and not something a caller can
     *     cause. Loud, because the alternative is a job that fails with no artifact and no reason.
     */
    ReportRenderer require(ReportFormat format) {
        return find(format).orElseThrow(() -> new IllegalStateException(
                "No renderer for " + format + ", yet a job was accepted for it"));
    }

    /** Every format this deployment can produce, for the message that says what it can. */
    List<ReportFormat> supported() {
        return byFormat.keySet().stream().sorted().toList();
    }
}
