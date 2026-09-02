package com.tcms.tracker.imports;

/**
 * The two choices the mapping step offers, both of them about what happens to something the source
 * has and this product does not.
 *
 * @param labelPerSprint one label per distinct sprint name, one assignment per issue.
 *                       <strong>Default false</strong> (D-h): a project with sixty sprints becomes
 *                       sixty labels nobody chose, and the label picker is a project-wide
 *                       vocabulary. {@code Sprint} stays in the dropped list either way, with its
 *                       reason sentence adjusted to say what was kept in its place — removing the
 *                       row would make the list a function of what was chosen rather than of what
 *                       was lost.
 * @param preserveBacklogOrder set {@code issue.rank} from the source's own order instead of letting
 *                       {@code V5}'s {@code issue_rank_default} trigger assign
 *                       {@code max(rank) + 1024} in insert order (D-i). <strong>Default true for
 *                       Jira</strong>, whose board ranking is meaningful. False means the report
 *                       says the backlog order is insertion order, chosen by nobody, rather than
 *                       letting it read as imported.
 */
public record ImportOptions(boolean labelPerSprint, boolean preserveBacklogOrder) {

    /** What the wizard sends when it sends nothing — D-h off, D-i on. */
    static ImportOptions defaults() {
        return new ImportOptions(false, true);
    }
}
