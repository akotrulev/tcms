package com.tcms.tracker.release;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tracker.IssueStatusKind;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /projects/{projectId}/roadmap} — releases on a timeline with their epics inside, story
 * R3 and {@code work-timeline.html}.
 *
 * <h2>Expect it to be empty, and say so on the screen</h2>
 *
 * <p>D14 migrates nothing into {@code release}, so on the day this module merges both arrays are
 * empty on every instance. That is a decision and not a defect, and it is the one thing about this
 * endpoint a client must handle deliberately: an empty timeline that explains nothing reads as a
 * broken screen, and a reader has no way to tell the difference.
 *
 * <h2>The unscheduled lane holds two shapes, and one list is the right number</h2>
 *
 * <p>{@code work-timeline.html}'s note is the rule — <em>"an epic with no dates does not disappear
 * from the roadmap; it drops into the unscheduled lane, where it can be seen and given some"</em> —
 * and a second shape qualifies for the same reason: an epic with <strong>no target release</strong>
 * has no row to be drawn under. Both go in one list, because both are answering "why can I not see
 * this epic on the timeline" and two lists would make a client decide which question it was asking.
 */
@Service
public class RoadmapService {

    private static final String READ = "release:read";

    /**
     * Every epic in the project, with its dates and its release.
     *
     * <p>The whole set rather than one query per lane: a project has tens of epics, the partition
     * into lanes is three field comparisons, and the alternative is one statement per release plus
     * one for the strays — which is the N+1 the roadmap would notice first.
     *
     * <p>Ordered by start date with nulls last, then by key sequence. Dateless epics therefore sit
     * at the end of their lane rather than at the front, which is where the eye expects "not
     * scheduled" to be; {@code key_seq} and never the key string, because {@code ACME-100} sorts
     * before {@code ACME-2} as text.
     */
    private static final String EPICS = """
            SELECT i.id, i.issue_key, i.title, i.start_date, i.target_date, i.release_id, s.kind
              FROM issue        i
              JOIN issue_type   t ON t.id = i.type_id
              JOIN issue_status s ON s.id = i.status_id
             WHERE i.project_id = :projectId
               AND i.deleted_at IS NULL
               AND t.level = 'EPIC'
             ORDER BY i.start_date NULLS LAST, i.key_seq
            """;

    private final ProjectService projects;
    private final ReleaseRepository releases;
    private final ReadinessService readiness;
    private final EntityManager entities;

    RoadmapService(
            ProjectService projects,
            ReleaseRepository releases,
            ReadinessService readiness,
            EntityManager entities) {

        this.projects = projects;
        this.releases = releases;
        this.readiness = readiness;
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public GateResult<RoadmapView> roadmap(UUID userId, UUID projectId) {
        return projects.readGranted(userId, projectId, READ, project -> {
            List<Release> all = releases.findByProjectIdOrderBySortKeyAsc(project.getId());
            List<EpicRow> epics = epicsOf(project.getId());

            Map<UUID, Integer> percent =
                    readiness.completionOf(epics.stream().map(EpicRow::id).toList());
            Map<UUID, ReleaseVerdict> verdicts = readiness.verdictsOf(project.getId(), all);

            Map<UUID, List<RoadmapEpicView>> byRelease = new LinkedHashMap<>();
            List<RoadmapEpicView> unscheduled = new ArrayList<>();
            for (EpicRow epic : epics) {
                RoadmapEpicView view = new RoadmapEpicView(
                        epic.key(), epic.title(), epic.startDate(), epic.targetDate(),
                        epic.releaseId(), epic.statusKind(), percent.get(epic.id()));

                boolean dateless = epic.startDate() == null && epic.targetDate() == null;
                if (epic.releaseId() == null || dateless) {
                    // Both shapes, one list. An epic in a release but with no dates is here AND is
                    // not repeated under its release: it cannot be drawn as a bar there, and
                    // listing it twice would make the same epic look like two.
                    unscheduled.add(view);
                } else {
                    byRelease.computeIfAbsent(epic.releaseId(), key -> new ArrayList<>()).add(view);
                }
            }

            List<RoadmapReleaseView> lanes = all.stream()
                    .map(release -> new RoadmapReleaseView(
                            release.getId(), release.getVersion(), release.getTargetDate(),
                            release.getStatus(), verdicts.get(release.getId()),
                            byRelease.getOrDefault(release.getId(), List.of())))
                    .toList();

            return new RoadmapView(lanes, List.copyOf(unscheduled));
        });
    }

    private record EpicRow(
            UUID id, String key, String title, LocalDate startDate, LocalDate targetDate,
            UUID releaseId, IssueStatusKind statusKind) {
    }

    private List<EpicRow> epicsOf(UUID projectId) {
        List<EpicRow> epics = new ArrayList<>();
        for (Object result : entities.createNativeQuery(EPICS)
                .setParameter("projectId", projectId)
                .getResultList()) {

            Object[] row = (Object[]) result;
            epics.add(new EpicRow(
                    UUID.fromString(row[0].toString()),
                    (String) row[1],
                    (String) row[2],
                    ReadinessService.toLocalDate(row[3]),
                    ReadinessService.toLocalDate(row[4]),
                    row[5] == null ? null : UUID.fromString(row[5].toString()),
                    IssueStatusKind.valueOf((String) row[6])));
        }
        return List.copyOf(epics);
    }
}
