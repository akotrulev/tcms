package com.tcms.identity;

import java.util.List;

/**
 * The contract's {@code OrgChart} — the company's people and teams, and each person's effective
 * role on each project <em>this caller</em> can open.
 *
 * <p>One document rather than three calls, because the three axes have to come from one snapshot:
 * a grid whose rows, columns and cells were read at three different moments renders a company that
 * never existed.
 *
 * @param teams   the whole org chart's structure, rendered by the same assembler
 *                {@code GET /teams} uses, so the two screens cannot draw different trees
 * @param people  every non-{@code REMOVED} member of the company — the people axis is a company
 *                fact and is not clipped
 * @param projects <strong>the matrix columns, and the disclosure boundary.</strong> Exactly the
 *                projects this caller holds {@code project:read} on. A project they cannot open
 *                contributes no column, no cell and no count — an unreachable project and a
 *                non-existent one are indistinguishable (§6t), and a count is a name-free existence
 *                claim about the company's portfolio
 */
public record OrgChartView(
        List<TeamView> teams,
        List<OrgChartPersonView> people,
        List<OrgChartProjectView> projects,
        List<OrgChartGapView> gaps) {
}
