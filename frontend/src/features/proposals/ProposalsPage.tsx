import {
  Box,
  Chip,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  Tooltip,
  Typography,
} from '@mui/material';
import SmartToyOutlinedIcon from '@mui/icons-material/SmartToyOutlined';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import { useAiProposals, useProject } from '../../api/queries';
import type { ProposalStatus } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { formatDateTime } from '../../lib/format';
import { proposalStatusLabels } from '../../lib/labels';
import { VerdictChip } from './VerdictChip';

/**
 * **The review queue** — generated cases waiting for a person (§138, plan §6w.6).
 *
 * This screen is one half of a boundary whose other half is not in this app at all. An agent files
 * proposals over MCP or the CLI; nothing here can file one. What it can do is the thing §6w.6 says
 * must happen before anything generated reaches the repository: *a human accepts it.*
 *
 * ## The status lives in the URL, like every other filter
 *
 * `?status=REJECTED` is a link somebody pastes into a conversation about a generation profile that
 * is producing rubbish. The default is `PENDING` because a queue is a list of things waiting; the
 * decided tabs exist because §6w.6 keeps rejections precisely so *"rejected four times in five"* is
 * answerable, and a tab is the cheapest way to make that a thing anyone can actually look at.
 *
 * ## Every row shows the verdict AND who claimed it
 *
 * {@link VerdictChip} refuses to render one without the other, and this screen never reaches past
 * it. §6w.1a's argument is that a claim shown as a finding is how a coverage number gets inflated,
 * and a queue is exactly where somebody skims.
 *
 * ## The empty state is the normal state
 *
 * Most projects will never have a proposal in them, and the empty text is the only place this
 * product says how generation works at all. It names the credential and the surface rather than
 * apologising, because the reader of an empty review queue is somebody deciding whether to try it.
 */
export function ProposalsPage() {
  const { projectId = '' } = useParams();
  const [params, setParams] = useSearchParams();

  const status = readStatus(params.get('status'));
  const project = useProject(projectId);
  const proposals = useAiProposals(projectId, status);

  return (
    <Box>
      <PageHeader
        testId="Proposals.Header"
        title="Generated cases"
        documentTitle={
          project.data ? `Generated cases · ${project.data.name}` : 'Generated cases'
        }
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Generated cases' },
        ]}
        subtitle="Cases an agent has proposed. Nothing here is in the repository yet — accepting one creates it exactly as writing it by hand would, with you as its author."
      />

      <Panel testId="Proposals.Panel" dense>
        <Tabs
          value={status}
          onChange={(_, next: ProposalStatus) => {
            // Replace rather than push: flipping between tabs is looking around, not navigating,
            // and a back button that walks three tabs backwards is the version everybody hates.
            setParams(next === 'PENDING' ? {} : { status: next }, { replace: true });
          }}
          sx={{ mb: 1.5 }}
          data-testid="Proposals.Tabs"
        >
          <Tab value="PENDING" label="Waiting" />
          <Tab value="ACCEPTED" label="Accepted" />
          <Tab value="REJECTED" label="Rejected" />
        </Tabs>

        <DataRegion
          testId="Proposals.List"
          state={{ ...proposals, refetch: () => void proposals.refetch() }}
          isEmpty={(page) => page.items.length === 0}
          empty={emptyFor(status)}
        >
          {(page) => (
            <ScrollX>
              <Table size="small" data-testid="Proposals.Grid">
                <TableHead>
                  <TableRow>
                    <TableCell sx={{ color: 'text.secondary' }}>Proposed case</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 260 }}>Grounding</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 90 }} align="right">
                      Steps
                    </TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 220 }}>Filed by</TableCell>
                    <TableCell sx={{ color: 'text.secondary', width: 180 }}>Filed</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {page.items.map((row) => (
                    <TableRow key={row.id} data-testid="Proposals.Row" hover>
                      <TableCell>
                        <RouterLink
                          to={`/projects/${projectId}/generated-cases/${row.id}`}
                          data-testid="Proposals.Open"
                        >
                          {row.title}
                        </RouterLink>
                        {status !== 'PENDING' && (
                          <Chip
                            size="small"
                            variant="outlined"
                            sx={{ ml: 1 }}
                            label={proposalStatusLabels[row.status]}
                          />
                        )}
                      </TableCell>
                      <TableCell>
                        <VerdictChip verdict={row.verdict} source={row.verdictSource} />
                      </TableCell>
                      <TableCell align="right">{row.stepCount}</TableCell>
                      <TableCell>
                        <FiledBy
                          name={row.createdBy?.displayName ?? null}
                          byAgent={row.filedByAgent}
                        />
                      </TableCell>
                      <TableCell>{formatDateTime(row.createdAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </ScrollX>
          )}
        </DataRegion>
      </Panel>
    </Box>
  );
}

/**
 * Who filed it, and whether a credential did.
 *
 * The person is always named — §6j's guarantee is that authorship is a person, and on an
 * agent-filed proposal that person is the token's *owner*. The robot icon is the second half of the
 * same sentence: *"Ana's agent filed this"* reads differently from *"Ana typed this"*, and which of
 * Ana's tokens it was is an audit-trail question rather than a review-screen one.
 *
 * An unresolvable person renders as nobody rather than hiding the row, which is
 * `TestCaseSummary.owner`'s rule one screen over.
 */
function FiledBy({ name, byAgent }: { name: string | null; byAgent: boolean }) {
  return (
    <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75 }}>
      {byAgent && (
        <Tooltip title="Filed by an API token this person owns, not typed by them.">
          <SmartToyOutlinedIcon fontSize="small" sx={{ color: 'text.secondary' }} />
        </Tooltip>
      )}
      <Typography variant="body2" sx={{ color: name ? 'text.primary' : 'text.secondary' }}>
        {name ?? 'Unknown'}
      </Typography>
    </Stack>
  );
}

/**
 * `?status=` is user input, so it is validated rather than cast.
 *
 * An unrecognised value falls back to `PENDING` instead of being sent on: the server would answer
 * 400 for a filter the person cannot see or fix, and a queue that fails to load because of a typo
 * in a pasted URL is the worst version of this.
 */
function readStatus(raw: string | null): ProposalStatus {
  return raw === 'ACCEPTED' || raw === 'REJECTED' ? raw : 'PENDING';
}

function emptyFor(status: ProposalStatus) {
  if (status === 'ACCEPTED') {
    return {
      kind: 'no-records' as const,
      title: 'Nothing accepted yet',
      body: 'Accepted proposals stay listed here with the case each one became, so the trail from a generated suggestion to a case in the repository survives.',
    };
  }
  if (status === 'REJECTED') {
    return {
      kind: 'no-records' as const,
      title: 'Nothing rejected yet',
      body: 'Rejections are kept rather than deleted — a generation profile whose proposals are rejected four times in five is only a measurable fact if the rejections survive.',
    };
  }
  return {
    kind: 'no-records' as const,
    title: 'No cases waiting for review',
    body: 'An agent files proposals here through the MCP server or the tcms CLI, using an API token with the Agent role. Nothing it proposes reaches the repository until somebody accepts it on this screen.',
  };
}
