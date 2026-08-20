package com.tcms.project;

import java.util.Optional;
import java.util.UUID;

/**
 * <strong>What artifact is this review about</strong> — asked from this side of the module
 * boundary, and never <em>may somebody see it</em>.
 *
 * <h2>Why this port has to exist at all</h2>
 *
 * <p>A {@link Review} names its subject as a {@code (type, id)} pair and nothing more, which is
 * what {@link ReviewView}'s header calls correct for the API: <em>"the client asked about a subject
 * it already has open"</em>, so resolving it there would mean reading two other modules' tables to
 * render a title the caller is already displaying.
 *
 * <p><strong>A notification is the case that reverses that reasoning.</strong> Its reader is in a
 * chat room with no artifact open, and a notice that says "you were asked to review a CASE" plus a
 * UUID cannot lead anybody to the thing they were asked to read — which is the whole content of the
 * message. So the pair has to be resolvable, and neither end can do it alone: {@code project}
 * declares {@code {api, tenancy}} and {@code integration} declares {@code {api, tenancy, project}},
 * so the case, the suite and the plan are out of reach from both. {@link PeopleDirectory}'s shape,
 * for the fourth question — interface here, implementations in the modules that own the tables,
 * called from the module that needs the answer.
 *
 * <h2>One bean per subject type, indexed rather than switched</h2>
 *
 * <p>{@link #subjectType()} lets the caller build a map, which is
 * {@code NotificationChannelPort#kind()}'s arrangement and has its property: a fourth subject type
 * added to {@link ReviewSubjectType} with no directory behind it fails where it is missing, once,
 * naming the type — instead of falling through a {@code switch} that compiled because its default
 * arm returned empty.
 *
 * <h2>Resolved at delivery, never carried in the payload</h2>
 *
 * <p>{@code NotificationProjects}' rule, and it binds harder here: a title is edited, so a title
 * frozen into an event is the one the artifact had when somebody was asked, and a retry an hour
 * later would announce a name that is no longer on the screen the reader opens.
 */
public interface ReviewSubjectDirectory {

    /** Which kind of subject this one answers about. Exactly one bean per member. */
    ReviewSubjectType subjectType();

    /**
     * Resolves one subject in one project.
     *
     * <p><strong>Empty is a state real rows are in, not a defence.</strong> A review is history and
     * outlives its subject: a case can be deleted while an approved review of it stands, and every
     * implementation resolves through its module's {@code findLiveInProject}, so a deleted artifact
     * yields nothing rather than a row nobody may act on. The caller says so in words instead of
     * naming an id.
     */
    Optional<Subject> describe(UUID projectId, UUID subjectId);

    /**
     * How an artifact is written down for a reader who is not looking at it.
     *
     * @param key   the display key — {@code CASE-12}, {@code SUITE-3}, {@code PLAN-7}. Every one of
     *              the three subject tables has one and it is {@code updatable = false}, so unlike
     *              the title it is the same handle tomorrow
     * @param title the artifact's own words. A case's {@code title}, a suite's or a plan's
     *              {@code name} — different columns, one question
     */
    record Subject(String key, String title) {
    }
}
