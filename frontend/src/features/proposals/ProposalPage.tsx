import { useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { useAnnounce } from '../../a11y/useAnnounce';
import { ApiError } from '../../api/client';
import {
  useAcceptProposal,
  useAiProposal,
  useRejectProposal,
  useVerifiedRequirements,
  type VerifiedRequirement,
} from '../../api/queries';
import type { AiProposal } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import {
  Badge,
  Banner,
  DocumentSurface,
  DocumentTitle,
  ProcedureLine,
} from '../../components/kit';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { Button, Textarea } from '../../components/ui';
import { cn } from '../../lib/cn';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { linkRelationshipLabels, proposalStatusLabels } from '../../lib/labels';
import { VerdictChip } from './VerdictChip';

/** `.sec h3` from `renders/tcms.css` — the rail's section heading, one spelling on this screen. */
const SECTION_HEADING = 'm-0 mb-2.5 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

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

  /*
    ~~`useProject`, for the project's name in this screen's own breadcrumb.~~ →
    **superseded (021 R2): the shell names the project**, from the read it already makes
    for the rail's switcher — so this page's copy of that read went with its trail.
  */
  const proposal = useAiProposal(projectId, proposalId);
  const accept = useAcceptProposal(projectId);
  const reject = useRejectProposal(projectId);

  const [note, setNote] = useState('');

  const fieldErrors =
    accept.error instanceof ApiError ? (accept.error.fieldErrors ?? null) : null;

  /*
    The tab title and the arrival announcement, raised here because this screen has no
    `PageHeader` — 007 T11, following T7. Design language rule 1 forbids a page `<h1>` repeating
    what the document below already says, and on this screen the proposal's title *is* the
    document's title. React 19 hoists a bare `<title>`, so the two things the header did that
    nothing else does survive its removal.
  */
  const pageTitle = proposal.data ? `${proposal.data.title} · Generated case` : 'Generated case';
  const announce = useAnnounce();
  useEffect(() => {
    announce(`${pageTitle} page loaded`);
  }, [announce, pageTitle]);

  return (
    <div className="flex min-h-full flex-col">
      <title>{`${pageTitle} · TCMS`}</title>

      <DataRegion
        testId="Proposal.Detail"
        state={{ ...proposal, refetch: () => void proposal.refetch() }}
      >
        {(row) => (
          <div className="flex min-w-0 flex-1 flex-col lg:flex-row">
            {/*
              THE DOCUMENT, THE EVIDENCE BESIDE IT, THE VERDICT LAST — `renders/02-record-as-document.html`
              panel D, and the reading order §6w.6 asks for: *"the review UI shows the grounding
              evidence alongside the proposal so the reviewer is reviewing against the spec, not
              against their memory of it."* Accept sits under the excerpts it is a judgement about,
              not in a toolbar above them.
            */}
            <DocumentSurface measure="procedure" className="flex-1">
              <Banner tone="steel" className="mb-5">
                <span>
                  <strong className="font-medium text-ink">Generated, not written.</strong> Filed{' '}
                  {formatDateTime(row.createdAt)} by{' '}
                  {row.createdBy?.displayName ?? 'someone no longer in this company'}
                  {row.filedByAgent ? ', through an API token they own' : ''}. It is not in the
                  repository until you accept it.
                </span>
              </Banner>

              <div className="mb-4 flex flex-wrap items-center gap-2">
                <VerdictChip verdict={row.verdict} source={row.verdictSource} />
                <Badge tone="plain" data-testid="Proposal.Status">
                  {proposalStatusLabels[row.status]}
                </Badge>
              </div>

              {row.status === 'ACCEPTED' && (
                <Banner tone="steel" className="mb-4" data-testid="Proposal.Accepted">
                  <span>
                    {row.acceptedCase ? (
                      <>
                        Accepted by {row.decidedBy?.displayName ?? 'someone'} — this became{' '}
                        <RouterLink
                          to={`/projects/${projectId}/cases/${row.acceptedCase.id}`}
                          className="text-steel underline-offset-4 hover:underline"
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
                  </span>
                </Banner>
              )}

              {row.status === 'REJECTED' && (
                <Banner tone="warn" className="mb-4" data-testid="Proposal.Rejected">
                  <span>
                    Rejected by {row.decidedBy?.displayName ?? 'someone'}
                    {row.decisionNote ? ` — ${row.decisionNote}` : '. No reason was given.'}
                  </span>
                </Banner>
              )}

              <ProposedCase proposal={row} />
            </DocumentSurface>

            <aside className="w-full shrink-0 border-line bg-panel lg:w-[340px] lg:border-l">
              {/*
                Above the grounding, because it is the narrower question and the one accept can
                refuse: the evidence is somebody else's report about the case, and this is what the
                case says about the specification.
              */}
              <section className="border-b border-line px-4 py-4" data-testid="Proposal.VerifiesPanel">
                <h2 className={SECTION_HEADING}>Claimed requirements</h2>
                <ClaimedRequirements projectId={projectId} verifies={row.verifies} />
              </section>

              <section className="border-b border-line px-4 py-4" data-testid="Proposal.GroundingPanel">
                <h2 className={SECTION_HEADING}>Grounding evidence</h2>
                {row.grounding ? (
                  <>
                    <p className="mt-0 mb-2 text-[12.5px] leading-[1.55] text-ink-3">
                      Sent by the proposer and shown as it arrived. TCMS did not produce this and
                      does not check it.
                    </p>
                    {/*
                      Rendered as preformatted TEXT, never as markup and never parsed into a shape
                      this product invented. The payload comes from a third-party agent, and the one
                      thing a review screen must not do is give somebody else's output the authority
                      of the product's own vocabulary.
                    */}
                    <ScrollX>
                      <pre
                        data-testid="Proposal.Grounding"
                        className="m-0 rounded-md bg-field p-2.5 font-mono text-[11.5px] leading-[1.6] whitespace-pre-wrap text-ink-2"
                      >
                        {JSON.stringify(row.grounding, null, 2)}
                      </pre>
                    </ScrollX>
                  </>
                ) : (
                  <p className="m-0 text-[12.5px] leading-[1.55] text-ink-3">
                    None was sent. Nobody has checked this proposal against anything — read it
                    against the specification it claims to cover.
                  </p>
                )}
              </section>

              {row.status === 'PENDING' && (
                <section className="px-4 py-4" data-testid="Proposal.DecisionPanel">
                  <h2 className={SECTION_HEADING}>Your decision</h2>

                  {fieldErrors && (
                    <Banner tone="warn" className="mb-3" data-testid="Proposal.AcceptErrors">
                      <span>
                        This proposal cannot become a case as it stands. It was filed before these
                        things changed, or the agent got them wrong:
                        <ul className="mt-1.5 mb-0 list-disc pl-4">
                          {Object.entries(fieldErrors).map(([field, messages]) => (
                            <li key={field}>
                              <strong className="font-mono text-ink">{field}</strong>:{' '}
                              {messages.join(' ')}
                            </li>
                          ))}
                        </ul>
                      </span>
                    </Banner>
                  )}
                  {accept.error && !fieldErrors && (
                    <Banner tone="bad" className="mb-3" data-testid="Proposal.AcceptFailed">
                      <span>{describeError(accept.error).body}</span>
                    </Banner>
                  )}
                  {reject.error && (
                    <Banner tone="bad" className="mb-3" data-testid="Proposal.RejectFailed">
                      <span>{describeError(reject.error).body}</span>
                    </Banner>
                  )}

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
                      <Button
                        variant="primary"
                        className="w-full"
                        title={allowed ? undefined : reason}
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
                    )}
                  </PermissionGate>

                  <div className="mt-4" data-testid="Proposal.Note">
                    <label
                      htmlFor="Proposal.Note.Input"
                      className="mb-1 block text-[12px] font-medium text-ink-2"
                    >
                      Why are you rejecting it? (optional)
                    </label>
                    <Textarea
                      id="Proposal.Note.Input"
                      rows={2}
                      maxLength={2000}
                      value={note}
                      onChange={(event) => setNote(event.target.value)}
                    />
                    <p className="mt-1 mb-0 text-[11.5px] leading-[1.5] text-ink-4">
                      Kept with the rejection. It is the only signal anyone has for whether a
                      generation profile is worth running again.
                    </p>
                  </div>

                  <PermissionGate projectId={projectId} permission="case:create">
                    {({ allowed, reason }) => (
                      <Button
                        variant="danger"
                        className="mt-2 w-full"
                        title={allowed ? undefined : reason}
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
                    )}
                  </PermissionGate>
                </section>
              )}
            </aside>
          </div>
        )}
      </DataRegion>
    </div>
  );
}

/**
 * The requirements the proposal says this case verifies, resolved against the pages they name.
 *
 * **The *no longer exists* row is the point of this panel, not its edge case.** `verifies` is served
 * as filed and unresolved, and accept validates every target against the project as it is now — so a
 * block deleted since the proposal was written is a 400 the reviewer would otherwise meet only by
 * pressing Accept, on a screen whose whole job is to let them decide before they press it.
 */
function ClaimedRequirements({
  projectId,
  verifies,
}: {
  projectId: string;
  verifies: AiProposal['verifies'];
}) {
  const requirements = useVerifiedRequirements(projectId, verifies);

  return (
    <DataRegion
      testId="Proposal.Verifies"
      state={requirements}
      isEmpty={(rows) => rows.length === 0}
      empty={{
        kind: 'by-design',
        title: 'It claims nothing',
        body:
          'This proposal names no requirement, so accepting it links the case to no specification. '
          + 'Read it against the page yourself before deciding.',
      }}
    >
      {(rows) => (
        <div className="flex flex-col gap-2">
          <p className="mt-0 mb-1 text-[12.5px] leading-[1.55] text-ink-3">
            Written when the case was proposed and linked to the specification the moment you
            accept. Only what it <em>verifies</em> counts as coverage.
          </p>
          {rows.map((requirement) => (
            <ClaimedRequirementCard
              key={`${requirement.blockId}:${requirement.relationship}`}
              projectId={projectId}
              requirement={requirement}
            />
          ))}
        </div>
      )}
    </DataRegion>
  );
}

function ClaimedRequirementCard({
  projectId,
  requirement,
}: {
  projectId: string;
  requirement: VerifiedRequirement;
}) {
  const gone = requirement.text === null;

  return (
    <div
      data-testid="Proposal.Verifies.Item"
      className={cn(
        'flex flex-col gap-1.5 rounded-md border p-2.5',
        gone
          ? 'border-[color:color-mix(in_oklab,var(--color-blocked)_34%,transparent)]'
          : 'border-line',
      )}
    >
      <div className="flex flex-wrap items-center gap-1.5">
        {requirement.page ? (
          <RouterLink
            // The block as a query parameter, the page as the path segment — the spelling the
            // documentation screen reads to open at one block, shared with the issue's spec panel.
            to={`/projects/${projectId}/docs/${requirement.page.id}?block=${requirement.blockId}`}
            data-testid="Proposal.Verifies.Item.Page"
            className="text-[12.5px] font-semibold text-ink no-underline hover:text-steel hover:underline"
          >
            {requirement.page.title}
          </RouterLink>
        ) : (
          <span className="text-[12.5px] font-semibold text-ink">The page it named</span>
        )}
        {requirement.page && <Identifier variant="muted">{requirement.page.key}</Identifier>}
        <Badge tone="plain">{linkRelationshipLabels[requirement.relationship]}</Badge>
      </div>

      {gone ? (
        <p
          data-testid="Proposal.Verifies.Item.Gone"
          className="m-0 text-[12.5px] leading-[1.55] text-ink-3"
        >
          {requirement.page
            ? 'This statement is no longer on that page. '
            : 'That page no longer exists. '}
          Accepting will be refused until the claim is filed again against something that exists.
        </p>
      ) : (
        <p
          data-testid="Proposal.Verifies.Item.Text"
          className="m-0 border-l-2 border-line pl-2.5 text-[13px] leading-[1.55] text-ink-2"
        >
          {requirement.text}
        </p>
      )}
    </div>
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
      <>
        <DocumentTitle>{proposal.title}</DocumentTitle>
        <Banner tone="warn" data-testid="Proposal.Unreadable">
          <span>
            This proposal can no longer be read as a test case — it was filed against an older
            version of the API. Reject it; it cannot be accepted.
          </span>
        </Banner>
      </>
    );
  }

  return (
    <>
      {/*
        The title and the objective are the document's own, not a page header's: design language
        rule 1, and the proposal's title is what the reader came for. `Why this exists` is the one
        piece of prose above the rule — `renders/02-record-as-document.html` panel A's decision,
        inherited here so the two screens agree about what a case is.
      */}
      <DocumentTitle
        whyLabel="Why this exists"
        why={
          proposed.objective ? (
            <span data-testid="Proposal.Objective">{proposed.objective}</span>
          ) : undefined
        }
      >
        {proposal.title}
      </DocumentTitle>

      {proposed.steps && proposed.steps.length > 0 ? (
        <div data-testid="Proposal.Steps">
          {proposed.steps.map((step, index) => (
            <ProcedureLine key={index} line={index + 1} data-testid="Proposal.Step">
              {step.action}
              {step.expectedResult && (
                <span className="mt-0.5 block text-[13px] text-ink-4">
                  {'\u2192 '}
                  {step.expectedResult}
                </span>
              )}
            </ProcedureLine>
          ))}
        </div>
      ) : (
        <p className="m-0 text-[13px] text-ink-3">
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
        </p>
      )}

      {/*
        `description` and `expectedOutcome` under the procedure rather than in a titled panel over
        it — 007 §4 keeps both fields (they are on the wire and hold data) and moves them into the
        document's narrative column, exactly as T7 did on the editor.
      */}
      {(proposed.description || proposed.expectedOutcome) && (
        <section className="mt-8 border-t border-line pt-5">
          <div className="grid gap-5 md:grid-cols-2">
            {proposed.description && (
              <Field label="Description" value={proposed.description} testId="Proposal.Description" />
            )}
            {proposed.expectedOutcome && (
              <Field label="Expected outcome" value={proposed.expectedOutcome} />
            )}
          </div>
        </section>
      )}
    </>
  );
}

function Field({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <div>
      <span className="mb-1 block font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">
        {label}
      </span>
      <p data-testid={testId} className="m-0 text-[13px] leading-[1.6] whitespace-pre-wrap text-ink-2">
        {value}
      </p>
    </div>
  );
}
