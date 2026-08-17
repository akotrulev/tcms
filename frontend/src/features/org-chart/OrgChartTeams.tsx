import { Box, Chip, Stack, Typography } from '@mui/material';
import type { Team } from '../../api/types';
import { Identifier } from '../../components/Identifier';
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
 * nothing and therefore offers nothing to gate.
 */
export function OrgChartTeams({ teams }: { teams: Team[] }) {
  const tree = flattenTeamTree(teams);

  return (
    <Stack sx={{ gap: 1.5 }} data-testid="OrgChart.Teams.List">
      {tree.map(({ team, depth }) => (
        <Box
          key={team.id}
          data-testid="OrgChart.Teams.Item"
          sx={{
            ml: depth * 3,
            pl: 1.5,
            borderLeft: depth > 0 ? '2px solid' : 'none',
            borderColor: 'divider',
          }}
        >
          <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {team.name}
            </Typography>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              <Identifier variant="muted">{formatCount(team.members.length)}</Identifier>{' '}
              {team.members.length === 1 ? 'member' : 'members'}
            </Typography>
            {team.leads.map((lead) => (
              <Chip
                key={lead.id}
                size="small"
                variant="outlined"
                label={`lead: ${lead.displayName}`}
              />
            ))}
            {/*
              `Team.projects` is already clipped to what this reader may see — the server does it,
              in the same assembler `/admin/teams` uses. An empty list here means "none you can
              see", not "none", which is why nothing counts what is missing.
            */}
            {team.projects.map((project) => (
              <Chip
                key={project.projectId}
                size="small"
                label={`${project.projectName} · ${project.roleName}`}
              />
            ))}
          </Stack>
        </Box>
      ))}
    </Stack>
  );
}
