import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useAcceptProposal, useAiProposal, useProject, useRejectProposal } from '../../api/queries';
import type { AiProposal } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { proposalStatusLabels } from '../../lib/labels';
import { VerdictChip } from './VerdictChip';

/**
 * One proposal, and the decision — **the screen where §6w.6's boundary is actually enforced**
 * (§138).
 *
 * ## What is being reviewed, and against what
 *
 * The proposed case is rendered from the stored payload, not from a case: there is no case yet, so
 * there is no key, no owner and no version. Beside it is the **grounding evidence** the proposer
 * sent, shown verbatim and never interpreted — it is a report from somebody else's critic, and this
 * product cannot know its shape. The reviewer is meant to read the two together, which is §6w.6's
 * whole requirement: *"the review UI shows the grounding evidence alongside the proposal so the
 * reviewer is reviewing against the spec, not against their memory of it."*
 *
 * ## Accept creates a case, and the copy says so before the click
 *
 * Not "approve". Accepting runs the ordinary create path, so what appears is a `DRAFT` case
 * authored by the person who accepted it, which then travels the review workflow like any other.
 * There is no path from a model to `Approved`, and the button must not imply one.
 *
 * ## A 400 here is normal, and it is the most useful thing on the screen
 *
 * The payload was stored unvalidated on purpose, so accept is where "is this a legal case" is
 * finally asked — against the project as it is *now*. A suite deleted last week, a retired shared
 * step, a vocabulary value that no longer exists: each lands as a field error on `TestCaseInput`'s
 * own names, and the reviewer's next move is usually Reject. So the errors are rendered inline and
 * in full rather than as a toast, and the Reject control stays live beside them.
 */
export function ProposalPage() {
  const { projectId = '', proposalId = '' } = useParams();
  const navigate = useNavigate();

  const project = useProject(projectId);
  const proposal = useAiProposal(projectId, proposalId);
  const accept = useAcceptProposal(projectId);
  const reject = useRejectProposal(projectId);

  const [note, setNote] = useState('');

  const fieldErrors =
    accept.error instanceof ApiError ? (accept.error.fieldErrors ?? null) : null;

  return (
    <Box>
      <PageHeader
        testId="Proposal.Header"
        title={proposal.data?.title ?? 'Generated case'}
        documentTitle={
          proposal.data ? `${proposal.data.title} · Generated case` : 'Generated case'
        }
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Generated cases', to: `/projects/${projectId}/generated-cases` },
          { label: proposal.data?.title ?? 'Proposal' },
        ]}
        subtitle="Nothing here is in the repository. Accepting creates the case exactly as writing it by hand would — as a draft, with you as its author."
      />

      <DataRegion
        testId="Proposal.Detail"
        state={{ ...proposal, refetch: () => void proposal.refetch() }}
      >
        {(row) => (
          <Stack sx={{ gap: 2 }}>
            <Panel testId="Proposal.SummaryPanel" dense>
              <Stack sx={{ gap: 1.5 }}>
                <Stack
                  direction="row"
                  sx={{ alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}
                >
                  <VerdictChip verdict={row.verdict} source={row.verdictSource} />
                  <Chip
                    size="small"
                    variant="outlined"
                    label={proposalStatusLabels[row.status]}
                    data-testid="Proposal.Status"
                  />
                </Stack>
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Filed {formatDateTime(row.createdAt)} by{' '}
                  {row.createdBy?.displayName ?? 'someone no longer in this company'}
                  {row.filedByAgent ? ', through an API token they own' : ''}.
                </Typography>

                {row.status === 'ACCEPTED' && (
                  <Alert severity="info" data-testid="Proposal.Accepted">
                    {row.acceptedCase ? (
                      <>
                        Accepted by {row.decidedBy?.displayName ?? 'someone'} — this became{' '}
                        <RouterLink
                          to={`/projects/${projectId}/cases/${row.acceptedCase.id}`}
                          data-testid="Proposal.AcceptedCase"
                        >
                          <Identifier>{row.acceptedCase.key}</Identifier>
                        </RouterLink>
                        .
                      </>
                    ) : (
                      /*
                        Accepted, and the case has since been deleted. Said plainly rather than
                        rendered as pending: the decision was made, and deleting the case is a
                        second decision rather than an undo of the first.
                      */
                      <>The case this became has since been deleted.</>
                    )}
                  </Alert>
                )}

                {row.status === 'REJECTED' && (
                  <Alert severity="warning" data-testid="Proposal.Rejected">
                    Rejected by {row.decidedBy?.displayName ?? 'someone'}
                    {row.decisionNote ? ` — ${row.decisionNote}` : '. No reason was given.'}
                  </Alert>
                )}
              </Stack>
            </Panel>

            <Panel testId="Proposal.CasePanel" title="The proposed case" dense>
              <ProposedCase proposal={row} />
            </Panel>

            <Panel testId="Proposal.GroundingPanel" title="Grounding evidence" dense>
              {row.grounding ? (
                <>
                  <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1 }}>
                    Sent by the proposer and shown as it arrived. TCMS did not produce this and does
                    not check it.
                  </Typography>
                  {/*
                    Rendered as preformatted TEXT, never as markup and never parsed into a shape
                    this product invented. The payload comes from a third-party agent, and the one
                    thing a review screen must not do is give somebody else's output the authority
                    of the product's own vocabulary.
                  */}
                  <ScrollX>
                    <Box
                      component="pre"
                      data-testid="Proposal.Grounding"
                      sx={{
                        m: 0,
                        p: 1.5,
                        fontSize: 13,
                        bgcolor: 'action.hover',
                        borderRadius: 1,
                        whiteSpace: 'pre-wrap',
                      }}
                    >
                      {JSON.stringify(row.grounding, null, 2)}
                    </Box>
                  </ScrollX>
                </>
              ) : (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  None was sent. Nobody has checked this proposal against anything — read it against
                  the specification it claims to cover.
                </Typography>
              )}
            </Panel>

            {row.status === 'PENDING' && (
              <Panel testId="Proposal.DecisionPanel" title="Your decision" dense>
                {fieldErrors && (
                  <Alert severity="warning" sx={{ mb: 2 }} data-testid="Proposal.AcceptErrors">
                    <Typography variant="body2" sx={{ mb: 1 }}>
                      This proposal cannot become a case as it stands. It was filed before these
                      things changed, or the agent got them wrong:
                    </Typography>
                    <ul style={{ margin: 0, paddingLeft: 18 }}>
                      {Object.entries(fieldErrors).map(([field, messages]) => (
                        <li key={field}>
                          <strong>{field}</strong>: {messages.join(' ')}
                        </li>
                      ))}
                    </ul>
                  </Alert>
                )}
                {accept.error && !fieldErrors && (
                  <Alert severity="error" sx={{ mb: 2 }} data-testid="Proposal.AcceptFailed">
                    {describeError(accept.error).body}
                  </Alert>
                )}
                {reject.error && (
                  <Alert severity="error" sx={{ mb: 2 }} data-testid="Proposal.RejectFailed">
                    {describeError(reject.error).body}
                  </Alert>
                )}

                <TextField
                  fullWidth
                  multiline
                  minRows={2}
                  size="small"
                  label="Why are you rejecting it? (optional)"
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  slotProps={{ htmlInput: { maxLength: 2000 } }}
                  data-testid="Proposal.Note"
                  sx={{ mb: 2 }}
                  helperText="Kept with the rejection. It is the only signal anyone has for whether a generation profile is worth running again."
                />

                <Divider sx={{ mb: 2 }} />

                <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
                  {/*
                    Disable-don't-hide (§31), and the permission is `case:create` because accepting
                    IS creating. A Viewer sees both buttons greyed with the reason, which is how
                    they learn the capability exists and is worth asking for.

                    There is no gate here for the OTHER refusal this endpoint can give — an API
                    token may not decide — because this app has no token-authenticated session. That
                    rule is the server's and is enforced there; the SPA cannot produce the caller
                    it applies to.
                  */}
                  <PermissionGate projectId={projectId} permission="case:create">
                    {({ allowed, reason }) => (
                      <span title={allowed ? undefined : reason}>
                        <Button
                          variant="contained"
                          disabled={!allowed || accept.isPending}
                          data-testid="Proposal.Accept"
                          onClick={() => {
                            accept.mutate(proposalId, {
                              onSuccess: (created) => {
                                // Straight to the case. What the reviewer wanted was a case in the
                                // repository, and leaving them on a decided proposal makes them
                                // hunt for the thing they just made.
                                void navigate(`/projects/${projectId}/cases/${created.id}`);
                              },
                            });
                          }}
                        >
                          Accept and create the case
                        </Button>
                      </span>
                    )}
                  </PermissionGate>

                  <PermissionGate projectId={projectId} permission="case:create">
                    {({ allowed, reason }) => (
                      <span title={allowed ? undefined : reason}>
                        <Button
                          variant="outlined"
                          color="warning"
                          disabled={!allowed || reject.isPending}
                          data-testid="Proposal.Reject"
                          onClick={() => {
                            reject.mutate({
                              proposalId,
                              // Blank is null, not '': the field is nullable on the contract and an
                              // empty string is a reason somebody typed nothing into.
                              note: note.trim() === '' ? null : note.trim(),
                            });
                          }}
                        >
                          Reject
                        </Button>
                      </span>
                    )}
                  </PermissionGate>
                </Stack>
              </Panel>
            )}
          </Stack>
        )}
      </DataRegion>
    </Box>
  );
}

/**
 * The proposed case, rendered from the stored payload.
 *
 * **Null `testCase` is a real state**, not an error: the payload was stored before a field left the
 * contract and can no longer be read. The proposal still renders and can still be rejected, which
 * is the only decision left to make about it — an error page here would leave the row in the queue
 * with nothing anybody could do.
 */
function ProposedCase({ proposal }: { proposal: AiProposal }) {
  const proposed = proposal.testCase;

  if (!proposed) {
    return (
      <Alert severity="warning" data-testid="Proposal.Unreadable">
        This proposal can no longer be read as a test case — it was filed against an older version
        of the API. Reject it; it cannot be accepted.
      </Alert>
    );
  }

  return (
    <Stack sx={{ gap: 1.5 }}>
      {proposed.objective && (
        <Field label="Objective" value={proposed.objective} testId="Proposal.Objective" />
      )}
      {proposed.description && (
        <Field label="Description" value={proposed.description} testId="Proposal.Description" />
      )}
      {proposed.expectedOutcome && (
        <Field label="Expected outcome" value={proposed.expectedOutcome} />
      )}

      {proposed.steps && proposed.steps.length > 0 ? (
        <ScrollX>
          <Table size="small" data-testid="Proposal.Steps">
            <TableHead>
              <TableRow>
                <TableCell sx={{ color: 'text.secondary', width: 48 }}>#</TableCell>
                <TableCell sx={{ color: 'text.secondary' }}>Action</TableCell>
                <TableCell sx={{ color: 'text.secondary' }}>Expected result</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {proposed.steps.map((step, index) => (
                <TableRow key={index} data-testid="Proposal.Step">
                  <TableCell>{index + 1}</TableCell>
                  <TableCell>{step.action}</TableCell>
                  <TableCell>{step.expectedResult ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </ScrollX>
      ) : (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {/*
            One sentence where there were two. The other branch read the proposal's
            `gherkinSource` and said "Written as a Gherkin scenario" — a field that left
            `TestCaseInput` in §255, because a scenario is step rows in either lens now. A
            proposal filed before then still stores the blob, and it reads as UNREADABLE rather
            than as a scenario: `AiProposal.testCase` is null for a payload the current input
            schema cannot describe, which is the `Proposal.Unreadable` branch above and is the
            honest answer — the accept path would refuse it.
          */}
          No steps — this cannot be accepted until an agent proposes one with steps.
        </Typography>
      )}
    </Stack>
  );
}

function Field({
  label,
  value,
  testId,
}: {
  label: string;
  value: string;
  testId?: string;
}) {
  return (
    <Box>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        {label}
      </Typography>
      <Typography variant="body2" data-testid={testId} sx={{ whiteSpace: 'pre-wrap' }}>
        {value}
      </Typography>
    </Box>
  );
}
