package com.tcms.reporting.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The three delivery reports as they go on the wire — 020 T1's schemas, one record each.
 *
 * <h2>{@link Sufficiency} is an answer, not an error</h2>
 *
 * <p>It rides on all three, and it arrives at 200 with a sentence in it. A well-formed question
 * about a project with three issues deserves a true statement about what can be said, not a 422 and
 * not an empty series a client renders as a flat line at zero (020 D-e). {@code reason} is null
 * exactly when {@code sufficient} is true, and it is prose rather than a code because every case has
 * a different fix — a client mapping codes back to sentences is a second place the sentence can be
 * wrong.
 *
 * <h2>Two of these carry a field naming what this product cannot know</h2>
 *
 * <p>{@link MeasuredFrom} is {@code CREATED} and has one member, because nothing here records the
 * moment work started (020 D-c). {@link CumulativeFlowPoint#beforeCurrentStatus()} is the issues
 * that existed on a past date whose status <em>on that date</em> is recorded nowhere (020 D-d).
 * Both are on the wire rather than in a comment, because the client that draws them has to say so
 * too.
 *
 * <p>Package-private records with public accessors: Jackson serialises them by component name, and
 * nothing outside this package constructs one.
 */
final class AnalyticsViews {

    private AnalyticsViews() {
    }

    record Sufficiency(boolean sufficient, String reason, int issuesConsidered) {

        static Sufficiency ok(int considered) {
            return new Sufficiency(true, null, considered);
        }

        static Sufficiency no(String reason, int considered) {
            return new Sufficiency(false, reason, considered);
        }
    }

    record BurnUpPoint(LocalDate date, int done, int totalScope) {
    }

    record BurnUpReport(
            UUID releaseId,
            String releaseVersion,
            Instant releaseCreatedAt,
            int countedTotal,
            int doneTotal,
            int scopeAddedAfterCreation,
            List<BurnUpPoint> points,
            Sufficiency sufficiency) {

        BurnUpReport {
            points = List.copyOf(points);
        }
    }

    /**
     * One member, deliberately.
     *
     * <p>{@code STARTED} is what this becomes the day a status-history table exists; an enum
     * carrying a member nothing can produce would be a promise in the schema rather than a fact
     * about the data, and a client written against this field today keeps working when the second
     * member arrives.
     */
    enum MeasuredFrom {
        CREATED
    }

    record CycleTimeBand(String typeName, int count, double medianDays, double p90Days) {
    }

    record CycleTimeReport(
            MeasuredFrom measuredFrom,
            int windowDays,
            List<CycleTimeBand> bands,
            Sufficiency sufficiency) {

        CycleTimeReport {
            bands = List.copyOf(bands);
        }
    }

    record CumulativeFlowPoint(
            LocalDate date, int notStarted, int inFlight, int done, int beforeCurrentStatus) {
    }

    record CumulativeFlowReport(
            int windowWeeks, List<CumulativeFlowPoint> points, Sufficiency sufficiency) {

        CumulativeFlowReport {
            points = List.copyOf(points);
        }
    }
}
