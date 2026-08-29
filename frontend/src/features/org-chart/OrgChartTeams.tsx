import type { Team } from '../../api/types';
import { Badge } from '../../components/kit';
import { formatCount } from '../../lib/format';
import { flattenTeamTree } from '../admin/teamTree';

/**
 * The team layer — the same tree `/admin/teams` draws, from the same function.
 *
 * `flattenTeamTree` rather than a copy: a copy is how the two screens come to disagree about which
 * teams exist, and the disagreement would be invisible on exactly the data that is already broken.
 * Orphans and cycle members are surfaced at the ROOT rather than dropped, which is that function's
 * rule and matters here for a second reason — this screen is where somebody goes to understand the
 * org chart, so a team it silently omits is one nobody will think to look for.
 *
 * Read-only. Every edit affordance is on `/admin/teams` behind `company:teams`; this screen gates
 * nothing and therefore offers nothing to gate — which is also why every label here is a **badge**
 * and not a chip (design language §5): a lead, a member count and a project role are all state the
 * system owns, and a chip would promise a filter that does not exist.
 */
export function OrgChartTeams({ teams }: { teams: Team[] }) {
  const tree = flattenTeamTree(teams);

  return (
    <div className="flex flex-col gap-2" data-testid="OrgChart.Teams.List">
      {tree.map(({ team, depth }) => (
        <div
          key={team.id}
          data-testid="OrgChart.Teams.Item"
          className={depth > 0 ? 'border-l border-line pl-3' : undefined}
          style={depth > 1 ? { marginLeft: (depth - 1) * 12 } : undefined}
        >
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-[13px] font-semibold text-ink">{team.name}</span>
            <span className="font-mono text-[11px] text-ink-4">
              {formatCount(team.members.length)} {team.members.length === 1 ? 'member' : 'members'}
            </span>
            {team.leads.map((lead) => (
              <Badge key={lead.id} tone="steel">
                lead: {lead.displayName}
              </Badge>
            ))}
            {/*
              `Team.projects` is already clipped to what this reader may see — the server does it,
              in the same assembler `/admin/teams` uses. An empty list here means "none you can
              see", not "none", which is why nothing counts what is missing.
            */}
            {team.projects.map((project) => (
              <Badge key={project.projectId} tone="plain">
                {project.projectName} · {project.roleName}
              </Badge>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
