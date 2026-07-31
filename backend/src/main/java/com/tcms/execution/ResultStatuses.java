package com.tcms.execution;

import com.tcms.project.CaseStatus;
import com.tcms.project.CaseStatusRepository;
import com.tcms.project.CaseStatusView;
import com.tcms.project.StatusKind;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A project's result vocabulary, loaded once per request.
 *
 * <h2>Why this module reads {@code case_status} directly and that is not the seam the package
 * comment governs</h2>
 *
 * <p>{@code case_status} is {@code project}'s table (ERD §3), and {@code project} is one of the
 * three modules everything may depend on. So this is an ordinary allowed edge — unlike
 * {@link CaseFacts}, which reaches into {@code repository} and is the one place that may.
 *
 * <p>{@code CaseStatusRepository} rather than {@code ProjectConfigurationService}: the service
 * re-runs the project gate, and every caller here has already passed it moments earlier. Paying for
 * a second permission evaluation to fetch six rows is the kind of cost that only shows up as a slow
 * run page.
 *
 * <h2>The kind is what counting reads, never the name</h2>
 *
 * <p>Statuses are project-configurable; {@link StatusKind} is not. A client who renames "Blocked" to
 * "Environment down", or adds a second passing status, must not disappear from a run's counts — so
 * {@link #countable} answers a kind and every tally in this module is written against it.
 */
@Component
class ResultStatuses {

    private final CaseStatusRepository statuses;

    ResultStatuses(CaseStatusRepository statuses) {
        this.statuses = statuses;
    }

    /**
     * A project's statuses by id, in the project's own order.
     *
     * <p>A {@link LinkedHashMap} because the order is the project's decision (position, then name)
     * and a status list rendered in hash order is a picker whose entries move between renders.
     */
    Map<UUID, CaseStatusView> byId(UUID projectId) {
        Map<UUID, CaseStatusView> byId = new LinkedHashMap<>();
        for (CaseStatus status : statuses.findByProjectIdOrderByPositionAscNameAsc(projectId)) {
            byId.put(status.getId(), new CaseStatusView(
                    status.getId(),
                    status.getName(),
                    status.getKind(),
                    status.getColor(),
                    status.isCountsAsPass(),
                    status.isFinal()));
        }
        return byId;
    }

    /**
     * Which {@link ResultCountsView} bucket a status falls in.
     *
     * <p>A null status — nobody has recorded anything — and the two non-final kinds both count as
     * not-run. See {@link ResultCountsView#notRun()} for why {@code RETEST} belongs there rather
     * than in a bucket of its own.
     */
    static StatusKind countable(CaseStatusView status) {
        if (status == null) {
            return StatusKind.UNTESTED;
        }
        return status.kind();
    }
}
