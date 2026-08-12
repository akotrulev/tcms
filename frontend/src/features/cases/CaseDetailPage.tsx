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
  Tooltip,
  Typography,
} from '@mui/material';
import AddLinkIcon from '@mui/icons-material/AddLinkOutlined';
import ContentCopyIcon from '@mui/icons-material/ContentCopyOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import EditIcon from '@mui/icons-material/Edit';
import HistoryIcon from '@mui/icons-material/History';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import type { CaseConditionRef, DocLink, TestCase, TestStep } from '../../api/types';
import {
  useAttachCaseFile,
  useCase,
  useDeleteCaseFile,
  useProject,
  useUnlinkCaseExternal,
} from '../../api/queries';
import { AttachmentsPanel } from '../../components/AttachmentList';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { CaseCloneDialog } from './CaseCloneDialog';
import { CaseDeleteDialog } from './CaseDeleteDialog';
import { ReviewPanel } from '../review/ReviewPanel';
import { CaseVersionPanel } from './CaseVersionPanel';
import { ExternalLinkDialog } from './ExternalLinkDialog';
import { FlakePanel } from './FlakePanel';
import { AutomationLabel, HealthChip, LifecycleChip, PriorityLabel, StatusChip } from '../../components/StatusChip';
import { formatAge, formatDateTime, formatSeconds } from '../../lib/format';
import { linkRelationshipLabels, stepFormatLabels } from '../../lib/labels';
import { ScrollX } from '../../components/ScrollX';

/**
 * Test case detail.
 *
 * Layout follows the design plan: content on the left, metadata sidebar on the right. The
 * split is not cosmetic — the steps are what a tester reads top-to-bottom while executing,
 * and the classification and ownership metadata is reference material they consult
 * occasionally. Interleaving them would make the reading path stutter.
 */
export function CaseDetailPage() {
  const { projectId = '', caseId = '' } = useParams();
  const project = useProject(projectId);
  const testCase = useCase(projectId, caseId);
  const navigate = useNavigate();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [cloning, setCloning] = useState(false);

  return (
    <Box>
      <DataRegion testId="CaseDetail.Content" state={{ ...testCase, refetch: () => void testCase.refetch() }}>
        {(data) => (
          <>
            <PageHeader
              testId="CaseDetail.Header"
              title={data.title}
              documentTitle={`${data.key} ${data.title}`}
              crumbs={[
                { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
                { label: 'Test cases', to: `/projects/${projectId}/cases` },
                // An unfiled case has no folder to point at, so the crumb becomes the grid's own
                // unfiled filter rather than a link to `?suiteId=null` — which would render every
                // case in the project and read as the crumb being broken.
                data.suiteId === null
                  ? { label: 'Not filed', to: `/projects/${projectId}/cases?unfiled=true` }
                  : {
                      label: data.suiteName ?? '',
                      to: `/projects/${projectId}/cases?suiteId=${data.suiteId}`,
                    },
                { label: data.key },
              ]}
              meta={
                <>
                  <Identifier variant="strong">{data.key}</Identifier>
                  <Tooltip title="The version currently saved. Runs pin the version they executed.">
                    <Box>
                      <Identifier variant="muted">v{data.currentVersionNo}</Identifier>
                    </Box>
                  </Tooltip>
                  <LifecycleChip status={data.lifecycleStatus} testId="CaseDetail.Lifecycle" />
                  <Chip size="small" variant="outlined" label={stepFormatLabels[data.stepFormat]} />
                </>
              }
              actions={
                <>
                  <Button
                    component={RouterLink}
                    to={`/projects/${projectId}/cases/${caseId}/history`}
                    startIcon={<HistoryIcon />}
                    data-testid="CaseDetail.HistoryLink"
                  >
                    Execution history
                  </Button>
                  {/*
                    §129. `case:create` and not `case:edit`: a clone creates a case and changes
                    nothing about this one, which is the key the endpoint charges. Disabled rather
                    than hidden (§31) — an Automation Service token holds `case:create` only in
                    quarantine and a Viewer holds neither, and both should be able to see that the
                    action exists.
                  */}
                  <PermissionGate projectId={projectId} permission="case:create">
                    {({ allowed }) => (
                      <Button
                        startIcon={<ContentCopyIcon />}
                        disabled={!allowed}
                        onClick={() => setCloning(true)}
                        data-testid="CaseDetail.CloneButton"
                      >
                        Clone
                      </Button>
                    )}
                  </PermissionGate>
                  {/*
                    The denied branch renders a plain `<button>`, NOT a disabled link. MUI's
                    `disabled` on `component={RouterLink}` only adds `aria-disabled` and kills
                    pointer events — the anchor keeps its `href`, so Enter on a focused link and a
                    middle-click both still navigate. A disabled-looking link that opens the editor
                    is worse than no gate at all, because it looks like the gate worked.
                  */}
                  <PermissionGate projectId={projectId} permission="case:edit">
                    {({ allowed }) =>
                      allowed ? (
                        <Button
                          component={RouterLink}
                          to={`/projects/${projectId}/cases/${caseId}/edit`}
                          variant="contained"
                          startIcon={<EditIcon />}
                          data-testid="CaseDetail.EditButton"
                        >
                          Edit case
                        </Button>
                      ) : (
                        <Button
                          variant="contained"
                          disabled
                          startIcon={<EditIcon />}
                          data-testid="CaseDetail.EditButton"
                        >
                          Edit case
                        </Button>
                      )
                    }
                  </PermissionGate>
                  {/*
                    The one gate in the app that asks about the ROW, not just the caller.
                    `case:delete` is OWN-qualified for a Test Engineer (roles spec §A5), so holding
                    it is not the same as holding it *here* — and `data.createdBy` is passed through
                    including when it is null, because an unauthored case is one the OWN grant does
                    not reach. Before `createdBy` was on the wire this button enabled on every case
                    and the caller discovered whose was whose by being refused.
                  */}
                  <PermissionGate
                    projectId={projectId}
                    permission="case:delete"
                    authorId={data.createdBy?.id ?? null}
                  >
                    {({ allowed }) => (
                      <Button
                        color="error"
                        startIcon={<DeleteOutlineIcon />}
                        disabled={!allowed}
                        onClick={() => setConfirmingDelete(true)}
                        data-testid="CaseDetail.DeleteButton"
                      >
                        Delete
                      </Button>
                    )}
                  </PermissionGate>
                </>
              }
            />

            {/*
              Mounted only while confirming, so the dialog's own reset-on-open effect starts from a
              clean mutation each time rather than from the previous case's refusal.
            */}
            {confirmingDelete && (
              <CaseDeleteDialog
                open
                onClose={() => setConfirmingDelete(false)}
                projectId={projectId}
                testCase={data}
                onDeleted={() => {
                  // `replace`, so Back does not return to a detail screen for a case that is gone
                  // and answer 404 at a URL the user reached by pressing Back.
                  navigate(`/projects/${projectId}/cases`, { replace: true });
                }}
              />
            )}

            {/* Mounted only while open, for the delete dialog's reason one block up. */}
            {cloning && (
              <CaseCloneDialog
                open
                projectId={projectId}
                testCase={data}
                onClose={() => setCloning(false)}
                onCloned={(created) => {
                  // Straight to the copy: the thing the user now wants to do is edit it, and a
                  // toast on the source's screen leaves them to find a case whose key they have
                  // not seen. NOT `replace` — unlike the delete above, the case behind is still
                  // there, and Back to the original is a reasonable thing to want.
                  navigate(`/projects/${projectId}/cases/${created.id}`);
                }}
              />
            )}

            {data.healthSignals.length > 0 && (
              <Stack direction="row" sx={{ gap: 0.75, flexWrap: 'wrap', mb: 2 }} data-testid="CaseDetail.Health">
                {data.healthSignals.map((signal) => (
                  <HealthChip key={signal} signal={signal} />
                ))}
              </Stack>
            )}

            <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 3, alignItems: 'flex-start' }}>
              <Stack sx={{ gap: 3, flex: 1, minWidth: 0, width: '100%' }}>
                <Panel title="What this proves" testId="CaseDetail.Summary">
                  <Stack sx={{ gap: 2 }}>
                    <Field label="Objective" value={data.objective} />
                    <Field label="Description" value={data.description} />
                    <Field label="Expected outcome" value={data.expectedOutcome} />
                  </Stack>
                </Panel>

                <ConditionPanel
                  title="Preconditions"
                  conditions={data.preconditions ?? []}
                  testId="CaseDetail.Preconditions"
                  emptyBody="No preconditions. The case runs from a clean session."
                />

                <StepsPanel testCase={data} />

                <ConditionPanel
                  title="Postconditions"
                  conditions={data.postconditions ?? []}
                  testId="CaseDetail.Postconditions"
                  emptyBody="No postconditions. Nothing needs cleaning up after this case."
                />

                {/*
                  Below the steps and the conditions, above traceability: an attachment on a case is
                  supporting material for the procedure above it — a reference screenshot, a sample
                  CSV — and traceability is about a different question (what requirement this
                  covers). It is in the CONTENT column and not the metadata sidebar for the same
                  reason: a tester reads it while executing.
                */}
                <CaseAttachments projectId={projectId} caseId={caseId} attachments={data.attachments} />

                <DocLinksPanel
                  links={data.relatedDocuments ?? []}
                  projectId={projectId}
                  caseId={caseId}
                />
              </Stack>

              {/* Metadata sidebar. */}
              <Box sx={{ width: { xs: '100%', lg: 320 }, flexShrink: 0 }}>
                <Stack sx={{ gap: 3 }}>
                  <Panel title="Last result" testId="CaseDetail.LastResult">
                    {data.lastResult?.statusKind ? (
                      <Stack sx={{ gap: 1 }}>
                        <StatusChip kind={data.lastResult.statusKind} size="medium" />
                        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                          {formatDateTime(data.lastResult.executedAt)}
                        </Typography>
                        {data.lastResult.releaseVersion && (
                          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                            Release <Identifier variant="muted">{data.lastResult.releaseVersion}</Identifier>
                          </Typography>
                        )}
                      </Stack>
                    ) : (
                      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                        Never executed.
                      </Typography>
                    )}
                  </Panel>

                  <Panel title="Classification" testId="CaseDetail.Classification">
                    <Stack sx={{ gap: 1.5 }}>
                      <MetaRow label="Suite" value={data.suiteName} />
                      <MetaRow label="Test type" value={data.testType ?? '—'} />
                      <MetaRow label="Test level" value={data.testLevel ?? '—'} />
                      <MetaRow
                        label="Features"
                        value={
                          data.features.length ? (
                            <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                              {data.features.map((feature) => (
                                <Chip key={feature} size="small" variant="outlined" label={feature} />
                              ))}
                            </Stack>
                          ) : (
                            '—'
                          )
                        }
                      />
                      <MetaRow
                        label="Priority"
                        value={<PriorityLabel priority={data.priority} testId="CaseDetail.Priority" />}
                      />
                      <MetaRow label="Estimated" value={formatSeconds(data.estimatedDurationSec)} />
                    </Stack>
                  </Panel>

                  <Panel title="Automation" testId="CaseDetail.Automation">
                    <Stack sx={{ gap: 1.5 }}>
                      <MetaRow
                        label="Status"
                        value={<AutomationLabel status={data.automationStatus} testId="CaseDetail.AutomationStatus" />}
                      />
                      {data.automationKey && (
                        <MetaRow
                          label="Test method"
                          value={
                            <Tooltip title={data.automationKey}>
                              <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
                                <Identifier variant="muted">{data.automationKey}</Identifier>
                              </Box>
                            </Tooltip>
                          }
                        />
                      )}
                      {data.automationTags && data.automationTags.length > 0 && (
                        <MetaRow
                          label="Tags"
                          value={
                            <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                              {data.automationTags.map((tag) => (
                                <Identifier key={tag} variant="muted">
                                  {tag}
                                </Identifier>
                              ))}
                            </Stack>
                          }
                        />
                      )}
                      {data.notAutomatableReason && (
                        <MetaRow label="Why not automatable" value={data.notAutomatableReason} />
                      )}
                    </Stack>
                  </Panel>

                  <Panel title="Ownership" testId="CaseDetail.Ownership">
                    <Stack sx={{ gap: 1.5 }}>
                      {/*
                        owner is nullable: test_case.owner_id is, so an imported case or one
                        whose owner was offboarded has none. "Unassigned" rather than a blank
                        row — a missing value and a missing field read the same otherwise.
                      */}
                      <MetaRow label="Owner" value={data.owner?.displayName ?? 'Unassigned'} />
                      <MetaRow
                        label="Updated"
                        value={
                          <Tooltip title={formatDateTime(data.updatedAt)}>
                            <Box component="span">{formatAge(data.updatedAt)}</Box>
                          </Tooltip>
                        }
                      />
                    </Stack>
                  </Panel>

                  {/*
                    Directly under Automation, because that is what it is about: a flake score is
                    computed from repeat runs under identical conditions, which in practice means
                    CI. Above the review panel for the reason that one gives about its own place —
                    this is a panel a reader acts on, and a quarantined test is a standing decision
                    somebody has to either justify or undo.
                  */}
                  <FlakePanel projectId={projectId} testCase={data} />

                  {/*
                    Above the version history and below the metadata, because it is the panel a
                    reader acts on: an open review is a question somebody is waiting on an answer
                    to, while the version list is reference material. `currentVersionNo` is passed
                    so the panel can say the case has moved past the version under review — the
                    server cannot compute that, holding only one of the two numbers.
                  */}
                  <ReviewPanel
                    projectId={projectId}
                    subjectType="CASE"
                    subjectId={caseId}
                    currentVersionNo={data.currentVersionNo}
                  />

                  {/*
                    Below Ownership rather than above it: the sidebar reads as "what is this case"
                    (classification, automation, who owns it) and then "what has happened to it".
                    It is also the tallest panel here, and putting a scrolling region above three
                    short ones pushes them off the fold on a laptop.
                  */}
                  <CaseVersionPanel
                    projectId={projectId}
                    caseId={caseId}
                    currentVersionNo={data.currentVersionNo}
                  />
                </Stack>
              </Box>
            </Stack>
          </>
        )}
      </DataRegion>
    </Box>
  );
}

/**
 * The case's files.
 *
 * A component rather than the panel inline, because the two mutation hooks need `caseId` and hooks
 * cannot be called inside the `DataRegion` render prop's conditional branches without moving the
 * whole page's hook order under the query's state. `StepActionField` exists for the same reason one
 * screen over.
 *
 * **The list is `TestCase.attachments` and there is no `GET .../attachments`.** `TestCaseSummary`
 * carries none, so this is detail-only exactly as §121 landed `TestCase.flake` — a second read path
 * would be a second answer to "what is attached to this case", free to disagree the moment one grew
 * a filter.
 */
function CaseAttachments({
  projectId,
  caseId,
  attachments,
}: {
  projectId: string;
  caseId: string;
  attachments: TestCase['attachments'];
}) {
  const attach = useAttachCaseFile(projectId, caseId);
  const remove = useDeleteCaseFile(projectId, caseId);

  return (
    <AttachmentsPanel
      projectId={projectId}
      // `case:edit`, with NO author arm — the slice's decision, argued in the plan, in the
      // endpoint's description and in `AttachmentList`'s header. Not `case:delete`, which is the
      // OWN-qualified key beside it and would refuse a lead's own screenshot to the engineer who
      // may rewrite every step of that case.
      permission="case:edit"
      attachments={attachments}
      onAttach={(file) => attach.mutate(file)}
      attaching={attach.isPending}
      attachError={attach.isError ? attach.error : null}
      onDelete={(id) => remove.mutate(id)}
      deleting={remove.isPending}
      testId="CaseDetail.Attachments"
      emptyBody="No files attached. Reference screenshots, sample data and anything else the steps above depend on go here."
    />
  );
}

function Field({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <Box>
      <Typography variant="caption" sx={{ color: 'text.secondary', fontWeight: 600, display: 'block', mb: 0.25 }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
        {value || <Box component="span" sx={{ color: 'text.secondary' }}>Not set</Box>}
      </Typography>
    </Box>
  );
}

function MetaRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <Stack direction="row" sx={{ gap: 1, alignItems: 'flex-start' }}>
      <Typography variant="body2" sx={{ color: 'text.secondary', width: 108, flexShrink: 0 }}>
        {label}
      </Typography>
      <Box sx={{ flex: 1, minWidth: 0, fontSize: '0.8125rem' }}>{value}</Box>
    </Stack>
  );
}

/**
 * Conditions attached to a case may be **inherited from a suite**, and that has to be visible:
 * a tester needs to know a step happens before their case even though it isn't written on it,
 * and an author needs to know that editing it changes every case in the subtree, not just
 * this one. An unmarked inherited condition reads as local and gets "fixed" locally.
 */
function ConditionPanel({
  title,
  conditions,
  testId,
  emptyBody,
}: {
  title: string;
  conditions: CaseConditionRef[];
  testId: string;
  emptyBody: string;
}) {
  return (
    <Panel title={title} testId={testId}>
      {conditions.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {emptyBody}
        </Typography>
      ) : (
        <Stack sx={{ gap: 1.5 }}>
          {/*
           * Rendered in the order the server sent, and NOT re-sorted by `position`.
           *
           * `position` is sibling order *within one suite* — it says nothing about which suite
           * comes first. A case's list is assembled across its whole ancestry (plan §6f:
           * inherited walking the suite path upward, outermost folder first, then the case's
           * own), so sorting by position interleaves depths and puts a rule set three folders
           * down ahead of the one it depends on. Exactly the trap `SuiteRepository` records for
           * the suite tree, where ordering the flat list by position lets a child precede its
           * own parent.
           *
           * It sorted here until the live pass, and it was invisible against the mock: the mock
           * hands out one flat ascending sequence, so position order and resolution order agree
           * there and only there.
           */}
          {conditions.map((condition) => (
              <Box
                key={condition.id}
                data-testid={`${testId}.Item`}
                sx={{
                  pl: 1.5,
                  borderLeft: '2px solid',
                  borderColor: condition.inherited ? 'var(--color-info)' : 'divider',
                }}
              >
                <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                  <Typography variant="body2" sx={{ fontWeight: 500 }}>
                    {condition.title}
                  </Typography>
                  {condition.inherited && (
                    <Tooltip
                      title={`Inherited from the ${condition.inheritedFrom} suite. Editing it affects every case beneath that suite.`}
                    >
                      <Chip
                        size="small"
                        label={`Inherited from ${condition.inheritedFrom}`}
                        data-testid={`${testId}.InheritedMarker`}
                        sx={{
                          bgcolor: 'transparent',
                          color: 'var(--color-info)',
                          border: '1px solid color-mix(in srgb, var(--color-info) 45%, transparent)',
                        }}
                      />
                    </Tooltip>
                  )}
                  {condition.inline && <Chip size="small" variant="outlined" label="Only on this case" />}
                </Stack>
                <Stack component="ol" sx={{ m: 0, pl: 2.5, gap: 0.25 }}>
                  {condition.body.map((line, i) => (
                    <Box component="li" key={`${condition.id}-${i}`}>
                      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                        {line}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              </Box>
            ))}
        </Stack>
      )}
    </Panel>
  );
}

/**
 * The dual step format (plan §6a): a case carries **either** structured steps **or** Gherkin,
 * never both, and the renderer switches on the case's own declared format rather than
 * guessing from which field happens to be populated.
 */
function StepsPanel({ testCase }: { testCase: TestCase }) {
  const isGherkin = testCase.stepFormat === 'GHERKIN';

  return (
    <Panel
      title="Steps"
      testId="CaseDetail.Steps"
      actions={<Chip size="small" variant="outlined" label={stepFormatLabels[testCase.stepFormat]} />}
      dense={!isGherkin}
    >
      {isGherkin ? (
        <GherkinView source={testCase.gherkinSource} />
      ) : (
        <StructuredSteps steps={testCase.steps ?? []} />
      )}
    </Panel>
  );
}

/** Gherkin keywords are highlighted; the rest is left exactly as authored. */
function GherkinView({ source }: { source: string | null | undefined }) {
  if (!source) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        No scenario written yet.
      </Typography>
    );
  }

  const KEYWORDS = /^(\s*)(Feature|Scenario Outline|Scenario|Background|Examples|Given|When|Then|And|But):?\s?/;

  return (
    <Box
      component="pre"
      data-testid="CaseDetail.Steps.Gherkin"
      sx={{
        m: 0,
        p: 2,
        fontFamily: 'var(--font-mono)',
        fontSize: '0.8125rem',
        lineHeight: 1.7,
        bgcolor: 'background.default',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        overflowX: 'auto',
      }}
    >
      {source.split('\n').map((line, i) => {
        const match = KEYWORDS.exec(line);
        if (!match) return <Box key={i}>{line || ' '}</Box>;
        return (
          <Box key={i}>
            {match[1]}
            <Box component="span" sx={{ color: 'primary.main', fontWeight: 600 }}>
              {match[2]}
            </Box>
            {line.slice(match[0].length - (match[0].endsWith(' ') ? 1 : 0)).replace(/^:/, ':')}
          </Box>
        );
      })}
    </Box>
  );
}

function StructuredSteps({ steps }: { steps: TestStep[] }) {
  if (steps.length === 0) {
    return (
      <Box sx={{ p: 2 }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          No steps written yet.
        </Typography>
      </Box>
    );
  }

  return (
    <ScrollX>
      <Table size="small" data-testid="CaseDetail.Steps.Grid">
        <TableHead>
          <TableRow>
            <TableCell sx={{ width: 44, color: 'text.secondary' }}>#</TableCell>
            <TableCell sx={{ color: 'text.secondary' }}>Action</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: '22%' }}>Test data</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: '32%' }}>Expected result</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {[...steps]
            .sort((a, b) => a.position - b.position)
            .map((step, index) => (
              <TableRow key={step.id} data-testid="CaseDetail.Steps.Row">
                <TableCell sx={{ verticalAlign: 'top' }}>
                  <Identifier variant="muted">{index + 1}</Identifier>
                </TableCell>
                <TableCell sx={{ verticalAlign: 'top' }} data-testid="CaseDetail.Steps.Row.Action">
                  {step.action}
                  {step.sharedStepId && (
                    <Tooltip title="This step is shared. Editing it changes every case that uses it.">
                      <Chip size="small" variant="outlined" label="Shared" sx={{ ml: 1 }} />
                    </Tooltip>
                  )}
                </TableCell>
                <TableCell sx={{ verticalAlign: 'top', color: 'text.secondary' }}>
                  {step.testData || '—'}
                </TableCell>
                <TableCell sx={{ verticalAlign: 'top' }} data-testid="CaseDetail.Steps.Row.Expected">
                  {step.expectedResult || '—'}
                </TableCell>
              </TableRow>
            ))}
        </TableBody>
      </Table>
    </ScrollX>
  );
}

/**
 * Traceability — what this case says it covers, and where.
 *
 * Requirements are documentation blocks rather than a separate entity, so a link points at a
 * specific block anchor — and a **stale** link is called out, because a link to a spec paragraph
 * that has since changed is worse than no link: it looks like coverage while covering the old text.
 *
 * ## Three kinds, and only one of them was ever renderable
 *
 * The external branches this panel used to carry were written when the panel was, and until §169
 * **no response could populate them**: `doc_link` designed `EXTERNAL_GIT` and `EXTERNAL_URL` from
 * M0 and nothing wrote either. What was here rendered `externalRepo` as a bare label for both
 * kinds — a blank line for a URL link, and for a git link a repository name with the file, the
 * range and the pin dropped into a caption below it. It is §168's `CoverageMarker` one panel over:
 * a branch that typechecked for seventy sections against a field nothing sent.
 *
 * ## Only external links carry a Remove
 *
 * A block link is withdrawn on the documentation page under `doc:edit`, and no endpoint removes one
 * from the case end — offering a control here that would fail is worse than not offering it. The
 * asymmetry is the permission split §169 argues at the service: a link to a paragraph belongs to
 * the documentation, a link to a file belongs to the case.
 */
function DocLinksPanel({
  links,
  projectId,
  caseId,
}: {
  links: DocLink[];
  projectId: string;
  caseId: string;
}) {
  const staleCount = links.filter((l) => l.stale).length;
  const [linking, setLinking] = useState(false);
  const remove = useUnlinkCaseExternal(projectId, caseId);

  return (
    <Panel
      title="Traceability"
      testId="CaseDetail.DocLinks"
      actions={
        <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }}>
          {staleCount > 0 && (
            <Chip
              size="small"
              label={`${staleCount} stale`}
              data-testid="CaseDetail.DocLinks.StaleCount"
              sx={{
                bgcolor: 'transparent',
                color: 'var(--color-failed)',
                border: '1px solid color-mix(in srgb, var(--color-failed) 45%, transparent)',
              }}
            />
          )}
          {/*
            Disabled rather than hidden (§31): a Viewer should be able to see that the product can
            do this. `case:edit` and not `doc:edit` — the endpoint's gate, because this link renders
            here and on no documentation page.
          */}
          <PermissionGate projectId={projectId} permission="case:edit">
            {({ allowed }) => (
              <Button
                size="small"
                startIcon={<AddLinkIcon />}
                disabled={!allowed}
                onClick={() => setLinking(true)}
                data-testid="CaseDetail.DocLinks.AddButton"
              >
                Link code or a URL
              </Button>
            )}
          </PermissionGate>
        </Stack>
      }
    >
      {links.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Not linked to any specification. Nothing traces this case back to a requirement.
        </Typography>
      ) : (
        <Stack sx={{ gap: 1.5 }} divider={<Divider />}>
          {links.map((link) => (
            <Box key={link.id} data-testid="CaseDetail.DocLinks.Item">
              <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 0.5 }}>
                <Chip size="small" variant="outlined" label={linkRelationshipLabels[link.relationship]} />
                <DocLinkTarget link={link} projectId={projectId} />
                {link.pageKey && <Identifier variant="muted">{link.pageKey}</Identifier>}
                {link.targetKind !== 'DOC_BLOCK' && (
                  <PermissionGate projectId={projectId} permission="case:edit">
                    {({ allowed }) =>
                      allowed ? (
                        <Button
                          size="small"
                          color="inherit"
                          startIcon={<DeleteOutlineIcon />}
                          disabled={remove.isPending}
                          onClick={() => remove.mutate(link.id)}
                          sx={{ ml: 'auto', color: 'text.secondary' }}
                          data-testid="CaseDetail.DocLinks.Remove"
                        >
                          Remove
                        </Button>
                      ) : null
                    }
                  </PermissionGate>
                )}
              </Stack>

              {link.blockExcerpt && (
                <Typography variant="body2" sx={{ color: 'text.secondary', pl: 1.5, borderLeft: '2px solid', borderColor: 'divider' }}>
                  {link.blockExcerpt}
                </Typography>
              )}

              {link.stale && (
                <Alert severity="warning" sx={{ mt: 1 }} data-testid="CaseDetail.DocLinks.StaleMarker">
                  <Typography variant="body2">
                    The linked text changed after this case was last edited. Re-read it before trusting
                    this case as coverage.
                  </Typography>
                </Alert>
              )}
            </Box>
          ))}
        </Stack>
      )}

      <ExternalLinkDialog
        open={linking}
        onClose={() => setLinking(false)}
        projectId={projectId}
        caseId={caseId}
      />
    </Panel>
  );
}

/**
 * What the link points at, by kind — one place, so the three cannot be rendered three ways.
 *
 * The switch is on `targetKind` and never on which field happens to be populated. A row is one kind
 * and the other kind's properties are null, which the contract states at the schema: a renderer
 * branching on `link.externalRepo` draws a git link as an empty page reference the day a repository
 * is left unnamed, and the server refuses that combination precisely so nothing has to guess.
 */
function DocLinkTarget({ link, projectId }: { link: DocLink; projectId: string }) {
  if (link.targetKind === 'DOC_BLOCK' && link.pageId) {
    return (
      <RouterLink
        to={`/projects/${projectId}/docs/${link.pageId}#${link.blockId}`}
        data-testid="CaseDetail.DocLinks.PageLink"
      >
        <Typography variant="body2" component="span" sx={{ fontWeight: 500 }}>
          {link.pageTitle}
        </Typography>
      </RouterLink>
    );
  }

  if (link.targetKind === 'EXTERNAL_URL') {
    return (
      // `noopener` with `_blank` is not optional: without it the opened page can navigate this tab
      // through `window.opener`, and this href is a value somebody typed. The scheme is refused at
      // the write, which is the other half of the same problem.
      <Typography
        component="a"
        href={link.externalUrl ?? undefined}
        target="_blank"
        rel="noopener noreferrer"
        variant="body2"
        sx={{ fontWeight: 500 }}
        data-testid="CaseDetail.DocLinks.ExternalUrl"
      >
        {link.externalUrl}
      </Typography>
    );
  }

  // EXTERNAL_GIT. The path, the range and the pin are ONE identifier and render as one:
  // `acme/checkout · src/Refund.java:40–58 at 4f9c2b1`. Splitting the sha into a caption, as this
  // panel used to, presents the pin as a footnote — and the pin is what makes the range mean
  // anything six months later (#26).
  return (
    <Stack
      direction="row"
      sx={{ alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}
      data-testid="CaseDetail.DocLinks.ExternalGit"
    >
      <Typography variant="body2" component="span" sx={{ fontWeight: 500 }}>
        {link.externalRepo}
      </Typography>
      <Identifier variant="muted">
        {link.externalPath}
        {link.externalLineStart != null && `:${link.externalLineStart}`}
        {link.externalLineEnd != null &&
          link.externalLineEnd !== link.externalLineStart &&
          `–${link.externalLineEnd}`}
      </Identifier>
      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
        at
      </Typography>
      <Identifier variant="muted">{link.externalCommitSha}</Identifier>
    </Stack>
  );
}

