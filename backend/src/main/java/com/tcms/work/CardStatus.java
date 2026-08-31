package com.tcms.work;

/**
 * A card's status, as a name a person reads and a kind a percentage can be computed from.
 *
 * <p><strong>Replaces the fixed {@code WorkItemStatus} enum</strong>, which had five members and
 * could not spell an issue's status: an issue's status is a per-project {@code issue_status} row
 * (D2), so a project that renamed "Blocked" to "Environment down" has a status the enum has no
 * member for. A mapping table would have given every card two spellings of where it is, and this
 * repo has already shipped two contradictory numbers on one screen once.
 *
 * <p>The four derived generators supply their own names with the matching kind — an outstanding
 * execution is {@code ("In Progress", IN_PROGRESS)}, a case the health computation flagged is
 * {@code ("To do", NOT_STARTED)}. Nothing about how a derived card is computed changed; only how its
 * position is spelled. <em>The plan's own examples for this paragraph had those two the other way
 * round; what D-c actually asks for, and what is built, is that each generator keeps the status it
 * already computed and gains the kind that goes with it.</em>
 *
 * <p>{@code kind} is a String and not an enum for {@code BoardFacts}' reason: it is
 * {@code tracker}'s {@code IssueStatusKind} vocabulary, this module may not import that module, and
 * a second copy of another module's value set is one a value added there would break as an
 * {@code IllegalArgumentException} on a read rather than as a compile error. A mirror copies what it
 * was given.
 *
 * <p>M2 builds real board columns over {@code kind} plus the project's own statuses, which is what
 * D2 asked for: the canonical status is on the issue, and a board <em>maps</em> statuses onto
 * columns rather than owning them — so two boards over the same work cannot disagree about where a
 * card is.
 */
public record CardStatus(String name, String kind) {

    /** {@code issue_status.kind}, as {@code V3} seeds and {@code EpicStatus} derives it. */
    static final String NOT_STARTED = "NOT_STARTED";

    static final String IN_PROGRESS = "IN_PROGRESS";

    /**
     * §6l's five default column names, which the derived generators keep.
     *
     * <p>They are the words {@code WorkItemStatus} stood for rather than that enum's member
     * spellings: the board rendered {@code To do} and {@code In Progress} on screen, and a card that
     * suddenly read {@code TODO} would be the same value acquiring a second reading — which is what
     * this record exists to prevent. {@code V3}'s own {@code TODO → 'Backlog'} mapping is a
     * different question: it decides where a <em>migrated task</em> lands in a project's real
     * workflow, and a derived card has no workflow to land in.
     */
    static CardStatus toDo() {
        return new CardStatus("To do", NOT_STARTED);
    }

    static CardStatus inProgress() {
        return new CardStatus("In Progress", IN_PROGRESS);
    }
}
