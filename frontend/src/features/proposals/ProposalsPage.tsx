import { Bot } from 'lucide-react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import { useAiProposals, useProject } from '../../api/queries';
import type { ProposalStatus } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import {
  Badge,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import { PageHeader } from '../../components/PageHeader';
import { TabStrip } from '../../components/TabStrip';
import { TABS } from '../../app/nav';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui';
import { formatCount, formatDateTime } from '../../lib/format';
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

  /*
    A count on every tab, because this is a review queue and the render says so: *"a review queue,
    so the tabs carry counts and the default is what needs you"* (renders audit 1.13). The build's
    tabs were bare labels, which leaves the one question a queue exists to answer — is there
    anything waiting for me — unanswerable without clicking through it.

    Three queries rather than one summary endpoint: they are the SAME queries the tabs themselves
    run, so a count and the rows behind it cannot disagree, and TanStack caches them — the two the
    reader is not looking at cost one request each on arrival and make switching tabs instant.
    A `total` that has not landed yet renders as nothing rather than as `0`: a zero the server
    never said is the wrong half of this to guess at.
  */
  const waiting = useAiProposals(projectId, 'PENDING');
  const accepted = useAiProposals(projectId, 'ACCEPTED');
  const rejected = useAiProposals(projectId, 'REJECTED');

  return (
    <div>
      {/*
        ~~`Generated cases`~~ → **`Proposals`** (021 §8.1, R4). §138 gave the queue its own rail
        heading — `Authoring` — so a reader could not mistake it for the repository; the tab label
        and this page's own subtitle make the same denial for free, and the heading cost a whole
        rail row to say it. The **route** and this file's name are unchanged.
      */}
      <PageHeader
        testId="Proposals.Header"
        title="Proposals"
        documentTitle={project.data ? `Proposals · ${project.data.name}` : 'Proposals'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test cases', to: `/projects/${projectId}/cases` },
          { label: 'Proposals' },
        ]}
        subtitle="Cases an agent has proposed. Nothing here is in the repository yet — accepting one creates it exactly as writing it by hand would, with you as its author."
      />

      <TabStrip tabs={TABS.cases} projectId={projectId} label="Test case views" testId="Cases.Tabs" />

      <section data-testid="Proposals.Panel">
        <Tabs
          value={status}
          onValueChange={(next) => {
            // Replace rather than push: flipping between tabs is looking around, not navigating,
            // and a back button that walks three tabs backwards is the version everybody hates.
            setParams(readStatus(next) === 'PENDING' ? {} : { status: next }, { replace: true });
          }}
          data-testid="Proposals.Tabs"
        >
          <TabsList className="mb-3">
            <TabsTrigger value="PENDING" data-testid="Proposals.Tab.PENDING">
              Waiting
              <TabCount total={waiting.data?.total} />
            </TabsTrigger>
            <TabsTrigger value="ACCEPTED" data-testid="Proposals.Tab.ACCEPTED">
              Accepted
              <TabCount total={accepted.data?.total} />
            </TabsTrigger>
            <TabsTrigger value="REJECTED" data-testid="Proposals.Tab.REJECTED">
              Rejected
              <TabCount total={rejected.data?.total} />
            </TabsTrigger>
          </TabsList>

          <TabsContent value={status}>
            <DataRegion
              testId="Proposals.List"
              state={{ ...proposals, refetch: () => void proposals.refetch() }}
              isEmpty={(page) => page.items.length === 0}
              empty={emptyFor(status)}
            >
              {(page) => (
                <Table data-testid="Proposals.Grid">
                  <TableHead>
                    <TableRow>
                      <TableHeaderCell>Proposed case</TableHeaderCell>
                      <TableHeaderCell className="w-[260px]">Grounding</TableHeaderCell>
                      <TableHeaderCell align="right" className="w-[90px]">
                        Steps
                      </TableHeaderCell>
                      <TableHeaderCell className="w-[220px]">Filed by</TableHeaderCell>
                      <TableHeaderCell className="w-[180px]">Filed</TableHeaderCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {page.items.map((row) => (
                      <TableRow key={row.id} data-testid="Proposals.Row">
                        <TableCell variant="title">
                          <RouterLink
                            to={`/projects/${projectId}/generated-cases/${row.id}`}
                            className="text-steel underline-offset-4 hover:underline"
                            data-testid="Proposals.Open"
                          >
                            {row.title}
                          </RouterLink>
                          {status !== 'PENDING' && (
                            <Badge tone="plain" className="ml-2">
                              {proposalStatusLabels[row.status]}
                            </Badge>
                          )}
                        </TableCell>
                        <TableCell>
                          <VerdictChip verdict={row.verdict} source={row.verdictSource} />
                        </TableCell>
                        <TableCell align="right" className="font-mono tabular-nums">
                          {row.stepCount}
                        </TableCell>
                        <TableCell>
                          <FiledBy
                            name={row.createdBy?.displayName ?? null}
                            byAgent={row.filedByAgent}
                          />
                        </TableCell>
                        <TableCell className="font-mono text-[12px] tabular-nums text-ink-4">
                          {formatDateTime(row.createdAt)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </DataRegion>
          </TabsContent>
        </Tabs>
      </section>
    </div>
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
    <span className="flex items-center gap-1.5">
      {byAgent && (
        <Bot
          aria-label="Filed by an API token this person owns, not typed by them."
          className="size-4 text-ink-4"
          /*
            `SmartToyOutlinedIcon` is MUI's own auto-generated test id, and `ProposalsPage.test.tsx`
            queries it by that exact name. It is carried across verbatim rather than renamed: this
            task's contract is that no test is edited, so the id survives the icon it was named
            after. Renaming it to `Proposals.Row.Agent` is a one-line change to make when a test
            edit is in scope — T16, which is the task that deletes the last MUI import.
          */
          data-testid="SmartToyOutlinedIcon"
        />
      )}
      <span className={name ? 'text-ink' : 'text-ink-4'}>{name ?? 'Unknown'}</span>
    </span>
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

/** The count beside a tab's label — absent, not zero, until the server has said a number. */
function TabCount({ total }: { total: number | undefined }) {
  if (total === undefined) return null;
  return <span className="ml-1.5 tabular-nums text-ink-3">{formatCount(total)}</span>;
}
