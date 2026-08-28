import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  ListItemText,
  Menu,
  MenuItem,
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
import DescriptionIcon from '@mui/icons-material/DescriptionOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlineOutlined';
import EditIcon from '@mui/icons-material/Edit';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import HistoryIcon from '@mui/icons-material/History';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import type {
  CaseConditionRef,
  DocLink,
  LifecycleStatus,
  TestCase,
  TestStep,
} from '../../api/types';
import {
  useAttachCaseFile,
  useCase,
  useCaseFeature,
  useDeleteCaseFile,
  useProject,
  useRelabelCaseExternalLink,
  useSetCaseStatus,
  useUnlinkCaseExternal,
} from '../../api/queries';
import { AttachmentsPanel } from '../../components/AttachmentList';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { LinkRelationshipControl } from '../../components/LinkRelationshipControl';
import { PermissionGate } from '../../components/PermissionGate';
import { UnlinkDialog } from '../../components/UnlinkDialog';
import { CaseCloneDialog } from './CaseCloneDialog';
import { CaseDeleteDialog } from './CaseDeleteDialog';
import { ReviewPanel } from '../review/ReviewPanel';
import { CaseVersionPanel } from './CaseVersionPanel';
import { ExternalLinkDialog } from './ExternalLinkDialog';
import { IssueLinksPanel } from './IssueLinksPanel';
import { FlakePanel } from './FlakePanel';
import { StepDataView } from './StepDataCell';
import { AutomationLabel, HealthChip, LifecycleChip, PriorityLabel, StatusChip } from '../../components/StatusChip';
import { EM_DASH, formatAge, formatDateTime, formatSeconds } from '../../lib/format';
import { stepFormatLabels, stepKeywordLabels } from '../../lib/labels';
import { describeLinkTarget } from '../../lib/links';
import { ScrollX } from '../../components/ScrollX';

/**
 * Test case detail.
 *
 * Layout follows the design plan: content on the left, metadata sidebar on the right. The
 * split is not cosmetic — the steps are what a tester reads top-to-bottom while executing,
 * and the classification and ownership metadata is reference material they consult
 * occasionally. Interleaving them would make the reading path stutter.
 *
 * ## The lifecycle menu (§227, audit A1)
 *
 * Until it existed, moving this case along §6g's ladder meant leaving this page, finding the row in
 * the grid, selecting it and opening the bulk dialog — four steps and a different screen to do the
 * thing this screen is about. `ReviewPanel` was already mounted below and rendered a standing that
 * nothing here could change.
 *
 * The control is a menu rather than a row of buttons because **the moves swap rather than
 * accumulate**: a case offers only the edges its current status actually has, so the set is one to
 * three items and a fixed row would either grey two thirds of itself or change width per status.
 * `SuiteList`'s kebab is the same decision on the same ladder.
 */

/**
 * §6g's ladder, from each status, with the key each edge costs.
 *
 * **The permission column mirrors the server's `LifecycleLadder.permissionFor` and is a disabling
 * hint, never the gate** — `suiteMoves` in `SuiteList.tsx` and `planMoves` have exactly the same
 * relationship to the same class. Getting it wrong here shows a control that 403s, which is §31's
 * failure and not a security one; the server decides, from a status only it holds.
 *
 * **The rule in one sentence: `case:approve` buys a sign-off, and it buys moving a case that has
 * one.** So every approve-keyed row below is an `APPROVED` row or an `APPROVED` target — the
 * reading a target-decides rule gets wrong is that *leaving* an approval is an approval act
 * whatever the target is called, which is exactly the defect §75 found on the bulk path.
 *
 * A local table rather than a shared one, matching `suiteMoves`: the two ladders are the same
 * transitions under **different keys**, so a shared table would have to be parameterised by the
 * key pair and would then be one edit from giving a case a suite's permissions.
 *
 * `satisfies Record<LifecycleStatus, …>` so a fifth status is a compile error here rather than a
 * case with no moves at all.
 */
const caseMoves = {
  DRAFT: [
    { target: 'IN_REVIEW', label: 'Submit for review', permission: 'case:edit' },
    { target: 'DEPRECATED', label: 'Deprecate', permission: 'case:edit' },
  ],
  IN_REVIEW: [
    { target: 'APPROVED', label: 'Approve', permission: 'case:approve' },
    { target: 'DRAFT', label: 'Return to draft', permission: 'case:edit' },
    { target: 'DEPRECATED', label: 'Deprecate', permission: 'case:edit' },
  ],
  APPROVED: [
    { target: 'DRAFT', label: 'Return to draft', permission: 'case:approve' },
    { target: 'DEPRECATED', label: 'Deprecate', permission: 'case:approve' },
  ],
  // One way back, and it lands on DRAFT: approval attaches to reviewed content by a named person,
  // so a retired case returning straight to APPROVED would be a sign-off nobody gave (§6g's clone
  // rule). `case:edit`, symmetric with the retire above it — nothing is unmade coming back, because
  // the sign-off, if there was one, ended at the retirement.
  DEPRECATED: [{ target: 'DRAFT', label: 'Restore to draft', permission: 'case:edit' }],
} as const satisfies Record<
  LifecycleStatus,
  readonly { target: LifecycleStatus; label: string; permission: string }[]
>;

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
                  {/*
                    Provenance, and a chip rather than a link because there is nothing to link to:
                    the `.feature` file lives in somebody's automation repository and this instance
                    has never held it (§255). What it answers is "why does this case say that",
                    which is a question a reader of an imported case asks and had no way to.
                  */}
                  {data.importedFrom && (
                    <Tooltip title="Imported from a .feature file. This instance does not hold the file.">
                      <Chip
                        size="small"
                        variant="outlined"
                        data-testid="CaseDetail.ImportedFrom"
                        label={`Imported from ${data.importedFrom.path}:${data.importedFrom.line}`}
                      />
                    </Tooltip>
                  )}
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
                  <LifecycleMenu projectId={projectId} caseId={caseId} testCase={data} />
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
                    Asks about the ROW as well as the caller — and since V51 (§207) the row half
                    no longer changes the answer: `case:delete` is unqualified for every role that
                    holds it, so any non-Viewer contributor deletes any case.

                    ~~`case:delete` is OWN-qualified for a Test Engineer (roles spec §A5), so
                    holding it is not the same as holding it *here*. Before `createdBy` was on the
                    wire this button enabled on every case and the caller discovered whose was
                    whose by being refused.~~ That was true from §65 until 2026-08-14.

                    `authorId` is still passed, deliberately. The qualifier is data on the server
                    (see IdentityProjectAccess.canDoOnAuthored), so a future OWN grant must find
                    this gate already asking the question — hard-coding "authorship never matters"
                    is what would re-create the original defect inverted: an enabled Delete that
                    403s. It costs one already-loaded field.
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
                {/* In the order the server sent them, which is worst first (§179) — the same
                    ranking the grid's single chip is chosen by, so the two screens agree about
                    which of a case's problems is the one to read first. */}
                {data.healthSignals.map((raised) => (
                  <HealthChip key={raised.signal} signal={raised.signal} severity={raised.severity} />
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
                  declined={data.declinedPreconditions ?? []}
                  testId="CaseDetail.Preconditions"
                  emptyBody="No preconditions. The case runs from a clean session."
                />

                <StepsPanel testCase={data} projectId={projectId} />

                <ConditionPanel
                  title="Postconditions"
                  conditions={data.postconditions ?? []}
                  declined={data.declinedPostconditions ?? []}
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

                {/*
                  004 Phase 3's issue links, below traceability and deliberately not inside it: that
                  panel is about what this case COVERS, and this is about what is wrong with the
                  thing it covers. The two have different lifetimes and one list would call a closed
                  bug and a requirement both "related".
                */}
                <IssueLinksPanel projectId={projectId} caseId={caseId} />
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
                      <MetaRow label="Test type" value={data.testType ?? EM_DASH} />
                      <MetaRow label="Test level" value={data.testLevel ?? EM_DASH} />
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
                            EM_DASH
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
 * The lifecycle control — one button, the moves this case's status actually has.
 *
 * ## Disabled with a reason, never hidden (§31)
 *
 * Each item is gated on the key its own edge costs, so a Test Engineer looking at a case in review
 * sees **Approve** greyed beside a live **Return to draft** — which is the shape of the rule, made
 * visible. Hiding the approve item would leave a contributor with no way to learn why they cannot
 * sign anything off.
 *
 * The **button** itself is never gated: a Viewer may read this case, and a control that vanishes
 * for them teaches nothing, while one that opens onto three greyed items says exactly what the
 * situation is.
 *
 * ## The refusals it does not try to predict
 *
 * Two things can still refuse after the click, and neither is worth a second copy of the server's
 * rules here: the ladder itself (a 409 the menu already avoids by offering only legal edges) and
 * **§6g's review policy** — author-cannot-approve-own-work and the minimum peer LGTM — which
 * depends on the review record, the project's settings and who is asking. Predicting that here
 * would be a third implementation of `ReviewService.refuses`, and it would be wrong on exactly the
 * cases where it mattered. The refusal is raised by the global handler and read beside
 * `ReviewPanel`, which is the panel that explains it.
 */
function LifecycleMenu({
  projectId,
  caseId,
  testCase,
}: {
  projectId: string;
  caseId: string;
  testCase: TestCase;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const setStatus = useSetCaseStatus(projectId, caseId);
  const moves = caseMoves[testCase.lifecycleStatus];

  return (
    <>
      <Button
        startIcon={<ExpandMoreIcon />}
        onClick={(event) => setAnchor(event.currentTarget)}
        disabled={setStatus.isPending}
        aria-haspopup="menu"
        data-testid="CaseDetail.StatusButton"
      >
        Status
      </Button>
      <Menu
        open={!!anchor}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        data-testid="CaseDetail.StatusMenu"
      >
        {moves.map((option) => (
          <PermissionGate
            key={option.target}
            projectId={projectId}
            permission={option.permission}
            wrapper="block"
          >
            {({ allowed }) => (
              <MenuItem
                disabled={!allowed || setStatus.isPending}
                data-testid={`CaseDetail.StatusMenu.${option.target}`}
                onClick={() => {
                  setStatus.mutate(option.target);
                  setAnchor(null);
                }}
              >
                <ListItemText>{option.label}</ListItemText>
              </MenuItem>
            )}
          </PermissionGate>
        ))}
      </Menu>
    </>
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
  declined,
  testId,
  emptyBody,
}: {
  title: string;
  conditions: CaseConditionRef[];
  /**
   * The folder's rules this case has turned off (§6f's opt-out, served since §187).
   *
   * Rendered, and below the procedure rather than inside it. A reader looking at a case filed under
   * *Billing* has to be able to tell "this case does not require being logged in" from "nobody ever
   * decided" — and before this field existed the two rendered identically, because the row simply
   * was not there. It is deliberately NOT part of the list above: that list is the procedure, and a
   * struck-through line in the middle of a checklist is a step a tester has to decide about.
   */
  declined: CaseConditionRef[];
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

      {declined.length > 0 && (
        <Stack sx={{ gap: 0.5, mt: 2 }} data-testid={`${testId}.Declined`}>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            Turned off for this case
          </Typography>
          {declined.map((condition) => (
            <Stack
              key={condition.id}
              direction="row"
              data-testid={`${testId}.DeclinedItem`}
              sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}
            >
              <Typography
                variant="body2"
                sx={{ textDecoration: 'line-through', color: 'text.disabled' }}
              >
                {condition.title}
              </Typography>
              {/* Named for the same reason the inherited chip above is: "declined" alone leaves the
                  reader without the one fact that makes it actionable — which folder set the rule,
                  and therefore where it still applies. */}
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                {condition.inheritedFrom
                  ? `still applies to every other case in ${condition.inheritedFrom}`
                  : 'still applies to every other case in the folder'}
              </Typography>
            </Stack>
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
/**
 * The case's procedure — **one renderer for both lenses since §255**.
 *
 * ## `GherkinView` is a fallback now, not a branch
 *
 * A case is step rows in either lens, so the rows are what this panel draws and the keyword is a
 * prefix on the action rather than a different screen. What is left of the old branch is the
 * population `migrate-gherkin-source` has not reached: a case with **zero rows and a stored
 * `gherkinSource`**, which renders read-only with the note naming the command. That is a state an
 * operator can end, and saying so is the difference between a fallback and a feature — the whole
 * branch leaves the product in `002-`, once the backfill has run everywhere.
 *
 * The order of the two questions is load-bearing: **rows first**. A migrated case still carries its
 * blob (the backfill deliberately does not clear it, so `002-` can read it as proof of coverage),
 * so asking about the blob first would show every migrated case the old text for ever.
 */
function StepsPanel({
  testCase,
  projectId,
}: {
  testCase: TestCase;
  projectId: string;
}) {
  const steps = testCase.steps ?? [];
  const [viewingFeature, setViewingFeature] = useState(false);

  return (
    <Panel
      title="Steps"
      testId="CaseDetail.Steps"
      actions={
        <Stack direction="row" sx={{ gap: 1, alignItems: 'center' }}>
          {/*
            Offered on EVERY case, including a structured one, because the endpoint renders every
            case: numbered steps are a scenario whose steps have no keyword, and `*` is Gherkin's
            own spelling for that. An action present on some cases and absent on others, for a
            reason the author never chose, is the thing a 409 here would have produced.
          */}
          <Button
            size="small"
            startIcon={<DescriptionIcon />}
            onClick={() => setViewingFeature(true)}
            data-testid="CaseDetail.Steps.ViewFeature"
          >
            View as .feature
          </Button>
          <Chip size="small" variant="outlined" label={stepFormatLabels[testCase.stepFormat]} />
        </Stack>
      }
      dense={steps.length > 0}
    >
      {steps.length > 0 ? (
        <StructuredSteps steps={steps} />
      ) : testCase.gherkinSource ? (
        <Stack sx={{ gap: 2, p: 2 }}>
          <Alert severity="info" data-testid="CaseDetail.Steps.NotMigrated">
            <AlertTitle>Not yet migrated</AlertTitle>
            This case&rsquo;s scenario is still stored as text, so it cannot be executed step by
            step. Ask your operator to run <Identifier>migrate-gherkin-source</Identifier>.
          </Alert>
          <GherkinView source={testCase.gherkinSource} />
        </Stack>
      ) : (
        <Box sx={{ p: 2 }}>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            No steps written yet.
          </Typography>
        </Box>
      )}
      {viewingFeature && (
        <FeatureDialog
          projectId={projectId}
          testCase={testCase}
          onClose={() => setViewingFeature(false)}
        />
      )}
    </Panel>
  );
}

/**
 * The `.feature` render of one case (§255) — `GET .../cases/{caseId}/feature`.
 *
 * A dialog rather than a route: it is a **view of the case on screen**, not a second page about it,
 * and the text is short enough to read where it was asked for. Mounted only while open, so the
 * request is made by opening it — most readers never do.
 *
 * `DataRegion` for the loading and failed states like every other read in this app; there is no
 * empty state, because the endpoint is never empty for a case that exists (a zero-row legacy case
 * renders its stored blob).
 */
function FeatureDialog({
  projectId,
  testCase,
  onClose,
}: {
  projectId: string;
  testCase: TestCase;
  onClose: () => void;
}) {
  const feature = useCaseFeature(projectId, testCase.id);

  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        <Identifier>{testCase.key}.feature</Identifier>
      </DialogTitle>
      <DialogContent>
        <DataRegion
          testId="CaseDetail.Feature"
          state={{ ...feature, refetch: () => void feature.refetch() }}
        >
          {(text) => (
            <Box
              component="pre"
              data-testid="CaseDetail.Feature.Text"
              sx={{
                m: 0,
                p: 2,
                fontFamily: 'var(--font-mono)',
                fontSize: '0.8125rem',
                lineHeight: 1.7,
                whiteSpace: 'pre',
                overflowX: 'auto',
                bgcolor: 'background.default',
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: 1,
              }}
            >
              {text}
            </Box>
          )}
        </DataRegion>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="CaseDetail.Feature.Close">
          Close
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * A stored `gherkin_source` blob, keywords highlighted — **the not-yet-migrated fallback only**.
 *
 * It is no longer a rendering of the Gherkin lens: `StepsPanel` above draws the rows for both
 * lenses, and this draws text nothing can execute. It leaves the product with the field, in `002-`.
 */
function GherkinView({ source }: { source: string }) {

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

/**
 * The rows, in both lenses (§255).
 *
 * The keyword is a **prefix on the action** rather than a column of its own: it is part of the
 * sentence a reader reads — *"Given the account is funded"* — and a column would put a word in one
 * cell and its object in the next, which is unreadable on a structured case where most rows have
 * none. `THEN` is styled as the assertion it is, so the row that says what should happen is the one
 * the eye lands on; that is the same signal `CaseCompleteness` reads to decide whether a case
 * asserts anything at all.
 *
 * The two block arguments render **under the action, spanning nothing** — `StepDataView`, the same
 * component the execution checklist uses, because a table that wraps differently on the two screens
 * is an author and a tester disagreeing about what the data says.
 */
function StructuredSteps({ steps }: { steps: TestStep[] }) {
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
                  {step.keyword && (
                    <Box
                      component="span"
                      data-testid="CaseDetail.Steps.Row.Keyword"
                      sx={{
                        mr: 0.75,
                        fontWeight: 600,
                        color: step.keyword === 'THEN' ? 'success.main' : 'primary.main',
                      }}
                    >
                      {stepKeywordLabels[step.keyword]}
                    </Box>
                  )}
                  {step.action}
                  <StepDataView
                    docString={step.docString}
                    dataTable={step.dataTable}
                    testId="CaseDetail.Steps.Row.Data"
                  />
                </TableCell>
                <TableCell sx={{ verticalAlign: 'top', color: 'text.secondary' }}>
                  {step.testData || EM_DASH}
                </TableCell>
                <TableCell sx={{ verticalAlign: 'top' }} data-testid="CaseDetail.Steps.Row.Expected">
                  {step.expectedResult || EM_DASH}
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
 *
 * ## Two link buttons, because there are two acts (A8, §230)
 *
 * The same split leaves this panel able to *write* only the external half, so a reader who wants to
 * claim a requirement paragraph has to go to the documentation space — and until §230 nothing here
 * said so, which made the panel's own empty state ("Nothing traces this case back to a requirement")
 * a dead end for the one target kind it names. The second button is a **jump**, not a second
 * writer: it carries the case in the URL and the block link is still created over there, under
 * `doc:edit`, which is why that is the key this control asks for rather than this panel's own
 * `case:edit`. Asking the wrong one would send a Test Engineer's Viewer colleague to a page whose
 * every Link button is disabled.
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
  // The link being withdrawn, not a boolean — the dialog names it, and this panel renders one
  // Remove per row. See `UnlinkDialog`'s header for why the confirmation exists at all (§173).
  const [withdrawing, setWithdrawing] = useState<DocLink | null>(null);
  const remove = useUnlinkCaseExternal(projectId, caseId);
  const relabel = useRelabelCaseExternalLink(projectId, caseId);
  const navigate = useNavigate();

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
          <PermissionGate projectId={projectId} permission="doc:edit">
            {({ allowed }) => (
              <Button
                size="small"
                startIcon={<AddLinkIcon />}
                disabled={!allowed}
                onClick={() =>
                  navigate(`/projects/${projectId}/docs?linkCase=${encodeURIComponent(caseId)}`)
                }
                data-testid="CaseDetail.DocLinks.LinkSpecButton"
              >
                Link a specification
              </Button>
            )}
          </PermissionGate>
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
                {/* The claim, correctable in place since §174 — `case:edit`, this panel's own
                    key and the one the endpoint charges. */}
                <PermissionGate projectId={projectId} permission="case:edit">
                  {({ allowed }) => (
                    <LinkRelationshipControl
                      value={link.relationship}
                      onChange={(relationship) =>
                        relabel.mutate({ linkId: link.id, relationship })
                      }
                      // A block link's claim is corrected on the documentation page, where the
                      // block is — the same split that gives it no Remove here.
                      disabled={!allowed || link.targetKind === 'DOC_BLOCK'}
                      pending={relabel.isPending}
                      testId="CaseDetail.DocLinks.Relationship"
                    />
                  )}
                </PermissionGate>
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
                          onClick={() => setWithdrawing(link)}
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

      {withdrawing && (
        <UnlinkDialog
          open
          onClose={() => setWithdrawing(null)}
          // The text form of what `DocLinkTarget` renders as markup, so the dialog names the same
          // object the row does without putting a link that navigates away inside a confirmation.
          target={describeLinkTarget(withdrawing)}
          // This end's own sentence, and deliberately not the block end's: nothing here is
          // recounted, because an external link contributes to no coverage marker. What the reader
          // needs to know is that the thing at the other end survives — the panel's Remove sits
          // beside a file path, and "remove" beside a file path invites the wrong reading.
          consequence="Only the record of the link is removed. The code and the case are untouched."
          onConfirm={() =>
            remove.mutate(withdrawing.id, { onSuccess: () => setWithdrawing(null) })
          }
          pending={remove.isPending}
          error={remove.isError ? remove.error : null}
          reset={remove.reset}
          testId="CaseDetail.DocLinks.Unlink"
        />
      )}
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

