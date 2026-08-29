import { ChevronDown, Copy, History, Link2, Pencil, Trash2 } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import type {
  CaseConditionRef,
  DocLink,
  LifecycleStatus,
  StepKeyword,
  TestCase,
  TestStep,
} from '../../api/types';
import {
  useAttachCaseFile,
  useCase,
  useCaseFeature,
  useDeleteCaseFile,
  useRelabelCaseExternalLink,
  useSetCaseStatus,
  useUnlinkCaseExternal,
} from '../../api/queries';
import { TopStripSlot } from '../../app/topStripSlot';
import { AttachmentsPanel } from '../../components/AttachmentList';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Banner,
  DocumentSurface,
  DocumentTitle,
  ProcedureLine,
  RailRow,
  type ProcedureKeyword,
} from '../../components/kit';
import { LinkRelationshipControl } from '../../components/LinkRelationshipControl';
import { PermissionGate } from '../../components/PermissionGate';
import { UnlinkDialog } from '../../components/UnlinkDialog';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
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
import { stepFormatLabels } from '../../lib/labels';
import { describeLinkTarget } from '../../lib/links';

/** `.sec h3` from `renders/tcms.css` — one spelling of a rail section heading on this screen. */
const SECTION_HEADING = 'm-0 mb-2.5 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

/**
 * A step's keyword, in the four the kit's procedure line paints — design language rule 5, where
 * `Given` is the green `Passed` is and `Then` is the blue `Retest` is. `stepKeywordLabels` is no
 * longer read here because `ProcedureLine` owns the word as well as the hue: two spellings of
 * "Given" on one screen is exactly the drift rule 5 exists to stop.
 */
const KEYWORD = {
  GIVEN: 'given',
  WHEN: 'when',
  THEN: 'then',
  AND: 'and',
  BUT: 'but',
} as const satisfies Record<StepKeyword, ProcedureKeyword>;

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
  const testCase = useCase(projectId, caseId);
  const navigate = useNavigate();
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [cloning, setCloning] = useState(false);

  /*
    The tab title and the arrival announcement, raised here because this screen no longer has a
    `PageHeader` (007 T11, following T7). Design language rule 1: *"no page heading repeating the
    rail's active item"* — and on a record read as a document the case's own title is the heading,
    drawn once by `DocumentTitle` at the top of the document. React 19 hoists a bare `<title>`, so
    the two things the header did that nothing else does survive its removal; the breadcrumb moves
    into the top strip, where the render puts it.
  */
  const pageTitle = testCase.data ? `${testCase.data.key} ${testCase.data.title}` : 'Test case';
  const announce = useAnnounce();
  useEffect(() => {
    announce(`${pageTitle} page loaded`);
  }, [announce, pageTitle]);

  return (
    <div className="flex min-h-full flex-col">
      <title>{`${pageTitle} · TCMS`}</title>

      {/*
        THE BREADCRUMB IS CHROME AND STAYS UP. It renders outside the region on purpose: a failed
        or slow read must not take the only non-rail way back to the list with it — the resilience
        rule that a failure is scoped to the response-dependent subtree, applied to the one piece
        of this screen that is not about the case's body.
      */}
      <TopStripSlot>
        <nav
          aria-label="Breadcrumb"
          data-testid="CaseDetail.Breadcrumbs"
          className="mr-auto hidden min-w-0 truncate text-[12.5px] text-ink-3 sm:block"
        >
          <RouterLink to={`/projects/${projectId}/cases`} className="hover:underline">
            Test cases
          </RouterLink>
          {' / '}
          {/*
            An unfiled case has no folder to point at, so the crumb becomes the grid's own
            unfiled filter rather than a link to `?suiteId=null` — which would render every
            case in the project and read as the crumb being broken.
          */}
          {testCase.data && (
            <>
              <RouterLink
                to={
                  testCase.data.suiteId === null
                    ? `/projects/${projectId}/cases?unfiled=true`
                    : `/projects/${projectId}/cases?suiteId=${testCase.data.suiteId}`
                }
                className="hover:underline"
              >
                {testCase.data.suiteId === null ? 'Not filed' : (testCase.data.suiteName ?? '')}
              </RouterLink>
              {' / '}
            </>
          )}
          <span className="font-medium text-ink-2">{testCase.data?.key ?? 'Case'}</span>
        </nav>
      </TopStripSlot>

      <DataRegion testId="CaseDetail.Content" state={{ ...testCase, refetch: () => void testCase.refetch() }}>
        {(data) => (
          <>
            <TopStripSlot>
              <div className="flex flex-wrap items-center gap-2" data-testid="CaseDetail.Actions">
                <Button asChild size="sm" data-testid="CaseDetail.HistoryLink">
                  <RouterLink to={`/projects/${projectId}/cases/${caseId}/history`}>
                    <History aria-hidden className="size-3.5" />
                    Execution history
                  </RouterLink>
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
                      size="sm"
                      disabled={!allowed}
                      onClick={() => setCloning(true)}
                      data-testid="CaseDetail.CloneButton"
                    >
                      <Copy aria-hidden className="size-3.5" />
                      Clone
                    </Button>
                  )}
                </PermissionGate>

                {/*
                  The denied branch renders a plain `<button>`, NOT a disabled link.

                  ~~MUI's `disabled` on `component={RouterLink}` only adds `aria-disabled` and kills
                  pointer events — the anchor keeps its `href`, so Enter on a focused link and a
                  middle-click both still navigate.~~ → **superseded by 007 T11**: the control is
                  `Button asChild` over a `<RouterLink>` now, and `asChild` has the same hole for
                  the same reason — `disabled` is not an anchor attribute, so a "disabled" link is
                  a link. A disabled-looking link that opens the editor is worse than no gate at
                  all, because it looks like the gate worked. So the two branches render two
                  different elements, exactly as before.
                */}
                <PermissionGate projectId={projectId} permission="case:edit">
                  {({ allowed }) =>
                    allowed ? (
                      <Button asChild size="sm" variant="primary" data-testid="CaseDetail.EditButton">
                        <RouterLink to={`/projects/${projectId}/cases/${caseId}/edit`}>
                          <Pencil aria-hidden className="size-3.5" />
                          Edit case
                        </RouterLink>
                      </Button>
                    ) : (
                      <Button size="sm" variant="primary" disabled data-testid="CaseDetail.EditButton">
                        <Pencil aria-hidden className="size-3.5" />
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
                      size="sm"
                      variant="danger"
                      disabled={!allowed}
                      onClick={() => setConfirmingDelete(true)}
                      data-testid="CaseDetail.DeleteButton"
                    >
                      <Trash2 aria-hidden className="size-3.5" />
                      Delete
                    </Button>
                  )}
                </PermissionGate>
              </div>
            </TopStripSlot>

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

            <div className="flex min-w-0 flex-1 flex-col lg:flex-row">
              {/*
                THE DOCUMENT COLUMN — `renders/02-record-as-document.html` panel A.

                Nine titled panels became one document and a rail. What a reader came here for is
                the procedure, so it gets the width and the ink at the editor's own 74ch measure:
                pressing *Edit* lands the caret in text that has not moved a pixel, which is the
                thesis the whole family rests on. The classification, ownership and traceability
                metadata is reference material and moves to the rail.
              */}
              <DocumentSurface measure="procedure" className="min-w-0 flex-1">
                <div className="mb-4 flex flex-wrap items-center gap-2">
                  <Identifier variant="strong">{data.key}</Identifier>
                  <span title="The version currently saved. Runs pin the version they executed.">
                    <Identifier variant="muted">v{data.currentVersionNo}</Identifier>
                  </span>
                  <LifecycleChip status={data.lifecycleStatus} testId="CaseDetail.Lifecycle" />
                  <Badge tone="plain">{stepFormatLabels[data.stepFormat]}</Badge>
                  {/*
                    Provenance, and a badge rather than a link because there is nothing to link to:
                    the `.feature` file lives in somebody's automation repository and this instance
                    has never held it (§255). What it answers is "why does this case say that",
                    which is a question a reader of an imported case asks and had no way to.
                  */}
                  {data.importedFrom && (
                    <span title="Imported from a .feature file. This instance does not hold the file.">
                      <Badge tone="plain" data-testid="CaseDetail.ImportedFrom">
                        Imported from {data.importedFrom.path}:{data.importedFrom.line}
                      </Badge>
                    </span>
                  )}
                </div>

                {data.healthSignals.length > 0 && (
                  <div className="mb-4 flex flex-wrap gap-1.5" data-testid="CaseDetail.Health">
                    {/* In the order the server sent them, which is worst first (§179) — the same
                        ranking the grid's single chip is chosen by, so the two screens agree about
                        which of a case's problems is the one to read first. */}
                    {data.healthSignals.map((raised) => (
                      <HealthChip key={raised.signal} signal={raised.signal} severity={raised.severity} />
                    ))}
                  </div>
                )}

                {/*
                  *Why this exists* is the only prose above the rule, and it is the objective —
                  the render's `.dwhy`, and the same decision the editor took. `description` and
                  `expectedOutcome` survive below the procedure (007 §4 defers deleting them,
                  because they are on the wire and hold data), in the document's narrative column
                  rather than in a titled *What this proves* panel.
                */}
                <DocumentTitle
                  whyLabel="Why this exists"
                  why={
                    data.objective ? (
                      <span data-testid="CaseDetail.Objective">{data.objective}</span>
                    ) : undefined
                  }
                >
                  {data.title}
                </DocumentTitle>

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

                {(data.description || data.expectedOutcome) && (
                  <section className="mt-8 border-t border-line pt-5">
                    <div className="grid gap-5 md:grid-cols-2">
                      {data.description && <Field label="Description" value={data.description} />}
                      {data.expectedOutcome && (
                        <Field label="Expected outcome" value={data.expectedOutcome} />
                      )}
                    </div>
                  </section>
                )}

                {/*
                  Below the steps and the conditions, above traceability: an attachment on a case is
                  supporting material for the procedure above it — a reference screenshot, a sample
                  CSV — and traceability is about a different question (what requirement this
                  covers). It is in the CONTENT column and not the metadata rail for the same
                  reason: a tester reads it while executing.
                */}
                <div className="mt-8">
                  <CaseAttachments projectId={projectId} caseId={caseId} attachments={data.attachments} />
                </div>

                <div className="mt-8">
                  <DocLinksPanel
                    links={data.relatedDocuments ?? []}
                    projectId={projectId}
                    caseId={caseId}
                  />
                </div>

                {/*
                  004 Phase 3's issue links, below traceability and deliberately not inside it: that
                  panel is about what this case COVERS, and this is about what is wrong with the
                  thing it covers. The two have different lifetimes and one list would call a closed
                  bug and a requirement both "related".
                */}
                <div className="mt-8">
                  <IssueLinksPanel projectId={projectId} caseId={caseId} />
                </div>
              </DocumentSurface>

              {/*
                THE RAIL — quiet at rest, values right-aligned, one section heading per group
                (`tcms.css`'s `.sec h3`, which is not a panel). Every row here is `readOnly`: this
                screen reads a case, and the control that changes any of these values is the editor
                one click away. `RailRow`'s read-only branch is a `<div>` rather than a disabled
                button for exactly that reason — there is no action behind it to enable.
              */}
              <aside className="w-full shrink-0 border-line bg-panel lg:w-[300px] lg:border-l">
                <section className="border-b border-line px-4 py-4" data-testid="CaseDetail.Summary">
                  <h2 className={SECTION_HEADING}>Summary</h2>
                  <RailRow readOnly label="Suite" value={data.suiteName ?? EM_DASH} />
                  <RailRow
                    readOnly
                    label="Priority"
                    value={<PriorityLabel priority={data.priority} testId="CaseDetail.Priority" />}
                  />
                  <RailRow readOnly label="Estimated" value={formatSeconds(data.estimatedDurationSec)} />
                  {/*
                    owner is nullable: test_case.owner_id is, so an imported case or one
                    whose owner was offboarded has none. "Unassigned" rather than a blank
                    row — a missing value and a missing field read the same otherwise.
                  */}
                  <RailRow readOnly label="Owner" value={data.owner?.displayName ?? 'Unassigned'} />
                  <RailRow
                    readOnly
                    label="Updated"
                    value={<span title={formatDateTime(data.updatedAt)}>{formatAge(data.updatedAt)}</span>}
                  />
                </section>

                <section className="border-b border-line px-4 py-4" data-testid="CaseDetail.LastResult">
                  <h2 className={SECTION_HEADING}>Last result</h2>
                  {data.lastResult?.statusKind ? (
                    <>
                      <RailRow
                        readOnly
                        label="Latest"
                        value={<StatusChip kind={data.lastResult.statusKind} />}
                      />
                      <RailRow
                        readOnly
                        label="Executed"
                        value={formatDateTime(data.lastResult.executedAt)}
                      />
                      {data.lastResult.releaseVersion && (
                        <RailRow
                          readOnly
                          label="Release"
                          value={<Identifier variant="muted">{data.lastResult.releaseVersion}</Identifier>}
                        />
                      )}
                    </>
                  ) : (
                    <p className="m-0 text-[13px] text-ink-4">Never executed.</p>
                  )}
                </section>

                <section className="border-b border-line px-4 py-4" data-testid="CaseDetail.Automation">
                  <h2 className={SECTION_HEADING}>Automation</h2>
                  <RailRow
                    readOnly
                    label="Status"
                    value={<AutomationLabel status={data.automationStatus} testId="CaseDetail.AutomationStatus" />}
                  />
                  {data.automationKey && (
                    <RailRow
                      readOnly
                      label="Test method"
                      value={
                        <span className="truncate" title={data.automationKey}>
                          <Identifier variant="muted">{data.automationKey}</Identifier>
                        </span>
                      }
                    />
                  )}
                  {data.automationTags && data.automationTags.length > 0 && (
                    <RailRow
                      readOnly
                      label="Tags"
                      value={
                        <span className="flex flex-wrap justify-end gap-1">
                          {data.automationTags.map((tag) => (
                            <Identifier key={tag} variant="muted">
                              {tag}
                            </Identifier>
                          ))}
                        </span>
                      }
                    />
                  )}
                  {data.notAutomatableReason && (
                    <p className="mt-1.5 mb-0 text-[12px] leading-[1.5] text-ink-4">
                      {data.notAutomatableReason}
                    </p>
                  )}
                </section>

                <section className="border-b border-line px-4 py-4" data-testid="CaseDetail.Classification">
                  <h2 className={SECTION_HEADING}>Classification</h2>
                  {/*
                    §0 item 3, rule 4 — *nothing configured, nothing shown*. A project that has
                    never defined a vocabulary gets one sentence in the same words the editor's
                    rail uses, not three rows of em dashes.
                  */}
                  {data.testType || data.testLevel || data.features.length > 0 ? (
                    <>
                      <RailRow readOnly label="Test type" value={data.testType ?? EM_DASH} />
                      <RailRow readOnly label="Test level" value={data.testLevel ?? EM_DASH} />
                      <RailRow
                        readOnly
                        label="Features"
                        value={
                          data.features.length ? (
                            <span className="flex flex-wrap justify-end gap-1">
                              {data.features.map((feature) => (
                                <Badge key={feature} tone="plain">
                                  {feature}
                                </Badge>
                              ))}
                            </span>
                          ) : (
                            EM_DASH
                          )
                        }
                      />
                    </>
                  ) : (
                    <p className="m-0 text-[12.5px] leading-[1.55] text-ink-4">
                      This project has no test types, levels or features defined, so there is
                      nothing to show.
                    </p>
                  )}
                </section>

                {/*
                  Directly under Automation, because that is what it is about: a flake score is
                  computed from repeat runs under identical conditions, which in practice means
                  CI. Above the review panel for the reason that one gives about its own place —
                  this is a panel a reader acts on, and a quarantined test is a standing decision
                  somebody has to either justify or undo.
                */}
                <div className="border-b border-line px-4 py-4">
                  <FlakePanel projectId={projectId} testCase={data} />
                </div>

                {/*
                  Above the version history and below the metadata, because it is the panel a
                  reader acts on: an open review is a question somebody is waiting on an answer
                  to, while the version list is reference material. `currentVersionNo` is passed
                  so the panel can say the case has moved past the version under review — the
                  server cannot compute that, holding only one of the two numbers.
                */}
                <div className="border-b border-line px-4 py-4">
                  <ReviewPanel
                    projectId={projectId}
                    subjectType="CASE"
                    subjectId={caseId}
                    currentVersionNo={data.currentVersionNo}
                  />
                </div>

                {/*
                  Below Ownership rather than above it: the rail reads as "what is this case"
                  (classification, automation, who owns it) and then "what has happened to it".
                  It is also the tallest panel here, and putting a scrolling region above three
                  short ones pushes them off the fold on a laptop.
                */}
                <div className="px-4 py-4">
                  <CaseVersionPanel
                    projectId={projectId}
                    caseId={caseId}
                    currentVersionNo={data.currentVersionNo}
                  />
                </div>
              </aside>
            </div>
          </>
        )}
      </DataRegion>
    </div>
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
  const [open, setOpen] = useState(false);
  const setStatus = useSetCaseStatus(projectId, caseId);
  const moves = caseMoves[testCase.lifecycleStatus];

  return (
    <div
      className="relative"
      onKeyDown={(event) => {
        if (event.key === 'Escape') setOpen(false);
      }}
    >
      <Button
        size="sm"
        onClick={() => setOpen((current) => !current)}
        disabled={setStatus.isPending}
        aria-haspopup="menu"
        aria-expanded={open}
        data-testid="CaseDetail.StatusButton"
      >
        Status
        <ChevronDown aria-hidden className="size-3.5" />
      </Button>
      {open && (
        <>
          {/*
            A scrim rather than a document listener: the menu closes on the next click anywhere,
            and the element that swallows that click is a real one in the tree, so nothing has to
            be registered, removed, or reasoned about across a re-render. It is `aria-hidden`
            because it is a dismissal surface and not a control — Escape is the keyboard's way out,
            and it is bound on the wrapper above rather than on the menu, which never holds focus.
          */}
          <div aria-hidden className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
          <div
            role="menu"
            data-testid="CaseDetail.StatusMenu"
            className="absolute right-0 z-50 mt-1 min-w-[200px] rounded-md border border-line bg-panel p-1 shadow-[0_16px_32px_-8px_rgb(0_0_0/60%)]"
          >
            {moves.map((option) => (
              <PermissionGate
                key={option.target}
                projectId={projectId}
                permission={option.permission}
                wrapper="block"
              >
                {({ allowed }) => (
                  <button
                    type="button"
                    role="menuitem"
                    aria-disabled={!allowed || setStatus.isPending ? 'true' : undefined}
                    data-testid={`CaseDetail.StatusMenu.${option.target}`}
                    onClick={() => {
                      if (!allowed || setStatus.isPending) return;
                      setStatus.mutate(option.target);
                      setOpen(false);
                    }}
                    className={
                      'block w-full rounded-[4px] px-2.5 py-1.5 text-left text-[13px] ' +
                      'focus-visible:outline-2 focus-visible:outline-offset-[-2px] ' +
                      'focus-visible:outline-[color:var(--color-given)] ' +
                      (allowed && !setStatus.isPending
                        ? 'cursor-pointer text-ink-2 hover:bg-wash-2 hover:text-ink'
                        : 'cursor-not-allowed text-ink-4')
                    }
                  >
                    {option.label}
                  </button>
                )}
              </PermissionGate>
            ))}
          </div>
        </>
      )}
    </div>
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
    <div>
      <span className="mb-1 block font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">
        {label}
      </span>
      <p className="m-0 text-[13px] leading-[1.6] whitespace-pre-wrap text-ink-2">
        {value || <span className="text-ink-4">Not set</span>}
      </p>
    </div>
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
  const inherited = conditions.filter((condition) => condition.inherited);

  return (
    <section className="mb-6" data-testid={testId}>
      <h2 className={SECTION_HEADING}>{title}</h2>

      {/*
        INHERITED RULES ARE A LINE, NOT A PANEL — the render's decision for panel A: what matters
        is that something runs before step 1 and that it came from the suite, and a titled panel
        listing them made the reader scroll past the procedure to reach it. The list itself is
        still here, because a tester executing this case has to be able to read the rules; what
        went is the box around it.
      */}
      {inherited.length > 0 && (
        <Banner tone="neutral" className="mb-3">
          <span>
            <strong className="font-medium text-ink">
              {inherited.length} {inherited.length === 1 ? 'rule' : 'rules'} inherited from{' '}
              {inherited[0].inheritedFrom}
            </strong>{' '}
            {title === 'Preconditions' ? 'run before step 1' : 'run after the last step'}. Editing
            one affects every case beneath that suite.
          </span>
        </Banner>
      )}

      {conditions.length === 0 ? (
        <p className="m-0 text-[13px] text-ink-4">{emptyBody}</p>
      ) : (
        <div className="flex flex-col gap-3">
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
            <div
              key={condition.id}
              data-testid={`${testId}.Item`}
              className="border-l-2 pl-3"
              style={{
                borderColor: condition.inherited ? 'var(--color-info)' : 'rgb(255 255 255 / 8%)',
              }}
            >
              <div className="mb-1 flex flex-wrap items-center gap-2">
                <span className="text-[13px] font-medium text-ink">{condition.title}</span>
                {condition.inherited && (
                  <span
                    title={`Inherited from the ${condition.inheritedFrom} suite. Editing it affects every case beneath that suite.`}
                  >
                    <Badge tone="steel" data-testid={`${testId}.InheritedMarker`}>
                      Inherited from {condition.inheritedFrom}
                    </Badge>
                  </span>
                )}
                {condition.inline && <Badge tone="plain">Only on this case</Badge>}
              </div>
              <ol className="m-0 flex list-decimal flex-col gap-0.5 pl-5 text-[13px] text-ink-3">
                {condition.body.map((line, i) => (
                  <li key={`${condition.id}-${i}`}>{line}</li>
                ))}
              </ol>
            </div>
          ))}
        </div>
      )}

      {declined.length > 0 && (
        <div className="mt-4 flex flex-col gap-1" data-testid={`${testId}.Declined`}>
          <span className="font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">
            Turned off for this case
          </span>
          {declined.map((condition) => (
            <div
              key={condition.id}
              data-testid={`${testId}.DeclinedItem`}
              className="flex flex-wrap items-center gap-2"
            >
              <span className="text-[13px] text-ink-4 line-through">{condition.title}</span>
              {/* Named for the same reason the inherited badge above is: "declined" alone leaves
                  the reader without the one fact that makes it actionable — which folder set the
                  rule, and therefore where it still applies. */}
              <span className="text-[12px] text-ink-4">
                {condition.inheritedFrom
                  ? `still applies to every other case in ${condition.inheritedFrom}`
                  : 'still applies to every other case in the folder'}
              </span>
            </div>
          ))}
        </div>
      )}
    </section>
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
    <section className="mb-6" data-testid="CaseDetail.Steps">
      <div className="mb-2.5 flex items-center gap-2">
        <h2 className={SECTION_HEADING + ' mb-0 mr-auto'}>Steps</h2>
        {/*
          Offered on EVERY case, including a structured one, because the endpoint renders every
          case: numbered steps are a scenario whose steps have no keyword, and `*` is Gherkin's
          own spelling for that. An action present on some cases and absent on others, for a
          reason the author never chose, is the thing a 409 here would have produced.
        */}
        <Button
          size="sm"
          variant="quiet"
          onClick={() => setViewingFeature(true)}
          data-testid="CaseDetail.Steps.ViewFeature"
        >
          View as .feature
        </Button>
      </div>

      {steps.length > 0 ? (
        <StructuredSteps steps={steps} />
      ) : testCase.gherkinSource ? (
        <div className="flex flex-col gap-3">
          <Banner tone="warn" data-testid="CaseDetail.Steps.NotMigrated">
            <span>
              <strong className="block font-medium text-ink">Not yet migrated</strong>
              This case&rsquo;s scenario is still stored as text, so it cannot be executed step by
              step. Ask your operator to run <Identifier>migrate-gherkin-source</Identifier>.
            </span>
          </Banner>
          <GherkinView source={testCase.gherkinSource} />
        </div>
      ) : (
        <p className="m-0 text-[13px] text-ink-4">No steps written yet.</p>
      )}

      {viewingFeature && (
        <FeatureDialog
          projectId={projectId}
          testCase={testCase}
          onClose={() => setViewingFeature(false)}
        />
      )}
    </section>
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
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            <Identifier>{testCase.key}.feature</Identifier>
          </DialogTitle>
        </DialogHeader>
        <DataRegion
          testId="CaseDetail.Feature"
          state={{ ...feature, refetch: () => void feature.refetch() }}
        >
          {(text) => (
            <pre
              data-testid="CaseDetail.Feature.Text"
              className="m-0 max-h-[60vh] overflow-auto rounded-md border border-line bg-field p-3 font-mono text-[12.5px] leading-[1.7] whitespace-pre text-ink-2"
            >
              {text}
            </pre>
          )}
        </DataRegion>
        <DialogFooter>
          <Button onClick={onClose} data-testid="CaseDetail.Feature.Close">
            Close
          </Button>
        </DialogFooter>
      </DialogContent>
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
    <pre
      data-testid="CaseDetail.Steps.Gherkin"
      className="m-0 overflow-x-auto rounded-md border border-line bg-field p-3 font-mono text-[12.5px] leading-[1.7] text-ink-2"
    >
      {source.split('\n').map((line, i) => {
        const match = KEYWORDS.exec(line);
        if (!match) return <div key={i}>{line || ' '}</div>;
        return (
          <div key={i}>
            {match[1]}
            <span className="font-medium" style={{ color: 'var(--color-given)' }}>
              {match[2]}
            </span>
            {line.slice(match[0].length - (match[0].endsWith(' ') ? 1 : 0)).replace(/^:/, ':')}
          </div>
        );
      })}
    </pre>
  );
}

/**
 * The rows, in both lenses (§255) — **the procedure, at the editor's own measure since 007 T11**.
 *
 * The keyword is a **prefix on the action** rather than a column of its own: it is part of the
 * sentence a reader reads — *"Given the account is funded"* — and a column would put a word in one
 * cell and its object in the next, which is unreadable on a structured case where most rows have
 * none.
 *
 * ~~A four-column table: #, Action, Test data, Expected result.~~ → **superseded.** The renders'
 * whole thesis for this family is that read and write are the same document, and the editor draws
 * a numbered line with the keyword coloured by rule 5's palette. A table of the same rows is a
 * second shape for one thing, and the two screens then disagree about what a case is. So the rows
 * are `kit/ProcedureLine`, and the two fields a table gave columns to — `testData` and
 * `expectedResult` — become the quiet second and third lines under the action they belong to,
 * which is `Table`'s own rule (UX#113: *a quiet second line in the cell, never a twelfth column*)
 * applied where the cell has become a line.
 *
 * The two block arguments render **under the action** — `StepDataView`, the same component the
 * execution checklist uses, because a table that wraps differently on the two screens is an author
 * and a tester disagreeing about what the data says.
 */
function StructuredSteps({ steps }: { steps: TestStep[] }) {
  return (
    <div data-testid="CaseDetail.Steps.Grid">
      {[...steps]
        .sort((a, b) => a.position - b.position)
        .map((step, index) => (
          <ProcedureLine
            key={step.id}
            line={index + 1}
            keyword={step.keyword ? KEYWORD[step.keyword] : undefined}
            keywordTestId="CaseDetail.Steps.Row.Keyword"
            data-testid="CaseDetail.Steps.Row"
          >
            <span data-testid="CaseDetail.Steps.Row.Action">{step.action}</span>
            <StepDataView
              docString={step.docString}
              dataTable={step.dataTable}
              testId="CaseDetail.Steps.Row.Data"
            />
            {step.testData && (
              <span className="mt-0.5 block font-mono text-[12px] text-ink-4">
                {step.testData}
              </span>
            )}
            {step.expectedResult && (
              <span
                className="mt-0.5 block text-[13px] text-ink-3"
                data-testid="CaseDetail.Steps.Row.Expected"
              >
                {'→ '}
                {step.expectedResult}
              </span>
            )}
          </ProcedureLine>
        ))}
    </div>
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

  return (
    <section data-testid="CaseDetail.DocLinks">
      <div className="mb-2.5 flex flex-wrap items-center gap-2">
        <h2 className={SECTION_HEADING + ' mb-0 mr-auto'}>Traceability</h2>
        {staleCount > 0 && (
          <Badge tone="failed" data-testid="CaseDetail.DocLinks.StaleCount">
            {staleCount} stale
          </Badge>
        )}
        {/*
          Disabled rather than hidden (§31): a Viewer should be able to see that the product can
          do this. `case:edit` and not `doc:edit` — the endpoint's gate, because this link renders
          here and on no documentation page.
        */}
        <PermissionGate projectId={projectId} permission="doc:edit">
          {({ allowed }) => (
            allowed ? (
              <Button asChild size="sm" variant="quiet" data-testid="CaseDetail.DocLinks.LinkSpecButton">
                <RouterLink to={`/projects/${projectId}/docs?linkCase=${encodeURIComponent(caseId)}`}>
                  <Link2 aria-hidden className="size-3.5" />
                  Link a specification
                </RouterLink>
              </Button>
            ) : (
              <Button size="sm" variant="quiet" disabled data-testid="CaseDetail.DocLinks.LinkSpecButton">
                <Link2 aria-hidden className="size-3.5" />
                Link a specification
              </Button>
            )
          )}
        </PermissionGate>
        <PermissionGate projectId={projectId} permission="case:edit">
          {({ allowed }) => (
            <Button
              size="sm"
              variant="quiet"
              disabled={!allowed}
              onClick={() => setLinking(true)}
              data-testid="CaseDetail.DocLinks.AddButton"
            >
              <Link2 aria-hidden className="size-3.5" />
              Link code or a URL
            </Button>
          )}
        </PermissionGate>
      </div>

      {links.length === 0 ? (
        <p className="m-0 text-[13px] text-ink-4">
          Not linked to any specification. Nothing traces this case back to a requirement.
        </p>
      ) : (
        <div className="flex flex-col divide-y divide-line">
          {links.map((link) => (
            <div key={link.id} className="py-2.5 first:pt-0" data-testid="CaseDetail.DocLinks.Item">
              <div className="mb-1 flex flex-wrap items-center gap-2">
                {/* The claim, correctable in place since §174 — `case:edit`, this panel's own
                    key and the one the endpoint charges. */}
                <PermissionGate projectId={projectId} permission="case:edit">
                  {({ allowed }) => (
                    <LinkRelationshipControl
                      value={link.relationship}
                      onChange={(relationship) => relabel.mutate({ linkId: link.id, relationship })}
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
                          size="sm"
                          variant="quiet"
                          className="ml-auto"
                          disabled={remove.isPending}
                          onClick={() => setWithdrawing(link)}
                          data-testid="CaseDetail.DocLinks.Remove"
                        >
                          Remove
                        </Button>
                      ) : null
                    }
                  </PermissionGate>
                )}
              </div>

              {link.blockExcerpt && (
                <p className="m-0 border-l-2 border-line pl-3 text-[13px] text-ink-3">
                  {link.blockExcerpt}
                </p>
              )}

              {link.stale && (
                <Banner tone="warn" className="mt-2" data-testid="CaseDetail.DocLinks.StaleMarker">
                  <span>
                    The linked text changed after this case was last edited. Re-read it before
                    trusting this case as coverage.
                  </span>
                </Banner>
              )}
            </div>
          ))}
        </div>
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
          onConfirm={() => remove.mutate(withdrawing.id, { onSuccess: () => setWithdrawing(null) })}
          pending={remove.isPending}
          error={remove.isError ? remove.error : null}
          reset={remove.reset}
          testId="CaseDetail.DocLinks.Unlink"
        />
      )}
    </section>
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
        className="text-[13px] font-medium text-steel underline-offset-4 hover:underline"
        data-testid="CaseDetail.DocLinks.PageLink"
      >
        {link.pageTitle}
      </RouterLink>
    );
  }

  if (link.targetKind === 'EXTERNAL_URL') {
    return (
      // `noopener` with `_blank` is not optional: without it the opened page can navigate this tab
      // through `window.opener`, and this href is a value somebody typed. The scheme is refused at
      // the write, which is the other half of the same problem.
      <a
        href={link.externalUrl ?? undefined}
        target="_blank"
        rel="noopener noreferrer"
        className="text-[13px] font-medium text-steel underline-offset-4 hover:underline"
        data-testid="CaseDetail.DocLinks.ExternalUrl"
      >
        {link.externalUrl}
        <span className="sr-only"> (opens in a new tab)</span>
      </a>
    );
  }

  // EXTERNAL_GIT. The path, the range and the pin are ONE identifier and render as one:
  // `acme/checkout · src/Refund.java:40–58 at 4f9c2b1`. Splitting the sha into a caption, as this
  // panel used to, presents the pin as a footnote — and the pin is what makes the range mean
  // anything six months later (#26).
  return (
    <span
      className="flex flex-wrap items-center gap-1.5 text-[13px]"
      data-testid="CaseDetail.DocLinks.ExternalGit"
    >
      <span className="font-medium text-ink">{link.externalRepo}</span>
      <Identifier variant="muted">
        {link.externalPath}
        {link.externalLineStart != null && `:${link.externalLineStart}`}
        {link.externalLineEnd != null &&
          link.externalLineEnd !== link.externalLineStart &&
          `–${link.externalLineEnd}`}
      </Identifier>
      <span className="text-[12px] text-ink-4">at</span>
      <Identifier variant="muted">{link.externalCommitSha}</Identifier>
    </span>
  );
}
