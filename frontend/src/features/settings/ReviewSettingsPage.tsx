import { useEffect, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  useProject,
  useProjectSettings,
  useReviewImpact,
  useUpdateProjectSettings,
} from '../../api/queries';
import type { EditAfterApproval, ProjectSettings, ReviewImpact } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner } from '../../components/kit';
import { SettingGroup, SettingRow } from '../../components/kit/SettingRow';
import { Button, Input, Switch } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import { formatCount, formatDateTime } from '../../lib/format';
import { editAfterApprovalDescriptions, editAfterApprovalLabels } from '../../lib/labels';
import { fullSettingsUpdate } from './settingsUpdate';

/**
 * The project's review policy — **plan §6g's four settings, which had nowhere to live until
 * `V23`.**
 *
 * Each of the four was blocking something that was already built and already shipped:
 *
 * - **the opt-in**, so §76's reviewer apparatus applied to every project whether its team wanted it
 *   or not — §6g's very first sentence says review is "off by default";
 * - **`requiredPeerReviews`**, a column on every review row, written from a database default, read
 *   by nothing;
 * - **author-cannot-approve-own-work**, which §6g calls "the single most valuable setting here" and
 *   the product simply did not have, so whoever submitted a case could sign it off themselves;
 * - **the edit-after-approval mode**, whose Retain half `ReviewPanel` has rendered as a stale
 *   marker since §76 while the Re-review half did not exist.
 *
 * §82 added a third group for §6g's two **gating** rules, and they are deliberately not review
 * settings: they bind on a project with review switched off, because §6g's opt-in suppresses the
 * reviewer apparatus and not the status ladder. Grouping them under "Where review applies" would
 * tell an admin the opposite in the one place they decide whether to turn them on.
 *
 * ## Read by everyone, written by an admin
 *
 * The `GET` is gated on project visibility alone and the `PUT` on `project:settings`, which is not
 * an inconsistency: §6g requires that a project with review off shows "no extra UI at all", and
 * that is a decision every reader's screen makes — a Viewer's included. So this page renders its
 * current state for anybody who can reach it and disables the controls for anybody who cannot
 * change them, which is §31's disable-don't-hide rather than a 403 on arrival.
 *
 * ## The form is local state until Save
 *
 * Six controls that only make sense together — turning review on and setting a peer minimum in one
 * action is one decision, and a per-control autosave would fire the "review is now on" invalidation
 * three times while somebody is still deciding. So this keeps a draft and `PUT`s the whole value,
 * which is also the shape the contract declares.
 *
 * ## Every setting states its blast radius, in the row (007 T15)
 *
 * ~~The counts were a panel of their own at the foot of the page, under "What this would land
 * on".~~ → **superseded.** Family 7's rule is that the consequence belongs *to the setting* —
 * *"counted against live data, at rest, before the toggle moves, so the reader decides with the
 * number rather than after it"* — and a reader deciding whether to switch case review off should
 * not have to scroll past two more groups to find the eleven cases it lands on. The same numbers,
 * the same testids, one screen-length earlier.
 */
export function ReviewSettingsPage() {
  const { projectId = '' } = useParams();

  const project = useProject(projectId);
  const settings = useProjectSettings(projectId);

  return (
    <div>
      <PageHeader
        testId="ReviewSettings.Header"
        title="Review"
        documentTitle={project.data ? `Review · ${project.data.name}` : 'Review'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Review' },
        ]}
        subtitle="Whether cases, suites and plans go through review before they count as authoritative — and what approval requires when they do. Review is off by default."
      />

      <DataRegion
        testId="ReviewSettings.Region"
        state={{ ...settings, refetch: () => void settings.refetch() }}
      >
        {(current) => (
          <PermissionGate projectId={projectId} permission="project:settings">
            {({ allowed }) => (
              <ReviewSettingsForm
                projectId={projectId}
                current={current}
                canEdit={allowed}
              />
            )}
          </PermissionGate>
        )}
      </DataRegion>
    </div>
  );
}

function ReviewSettingsForm({
  projectId,
  current,
  canEdit,
}: {
  projectId: string;
  current: ProjectSettings;
  canEdit: boolean;
}) {
  const save = useUpdateProjectSettings(projectId);
  const [draft, setDraft] = useState(current);
  /*
    What a change here would land on (005 N7/S7), read at the moment the screen opened and offered
    only to somebody who can change something — it costs the write key, see `useReviewImpact`.

    Its failure is silent and does not stop anybody saving: a policy is editable whether or not we
    could count what it would touch, so every consequence below is rendered only when the answer
    arrived. The counts span every module in the product (`documentation`'s drafts, `execution`'s
    blocked plans), which is why they are one server-side read rather than six the client would
    assemble.
  */
  const impact = useReviewImpact(projectId, canEdit);
  const counts = impact.data;

  /*
    Re-seeded when the server's copy changes identity — after a save, or after another admin's
    change arrives through an invalidation. Keyed on the fields rather than on the object, because
    TanStack Query hands back a new object reference on every refetch and comparing references
    would discard a half-filled form every time the window regained focus.
  */
  const serverFingerprint = JSON.stringify(current);
  useEffect(() => {
    setDraft(JSON.parse(serverFingerprint) as ProjectSettings);
  }, [serverFingerprint]);

  /*
    Mirrors the server's own bound (V23's CHECK, and ProjectSettingsService's constant). Held here
    as well so the refusal arrives before the request rather than as a field error afterwards —
    the constraint is the backstop, this is the check.
  */
  const peersInvalid =
    !Number.isInteger(draft.requiredPeerReviews) ||
    draft.requiredPeerReviews < 0 ||
    draft.requiredPeerReviews > 20;

  const dirty = JSON.stringify(draft) !== serverFingerprint;

  const reviewOn =
    draft.reviewEnabledCase ||
    draft.reviewEnabledSuite ||
    draft.reviewEnabledPlan ||
    draft.reviewEnabledDoc;

  const gatingOn = draft.requireApprovedCaseForRun;

  const edit = (patch: Partial<ProjectSettings>) => setDraft((d) => ({ ...d, ...patch }));

  return (
    <form
      className="flex max-w-[960px] flex-col gap-2"
      data-testid="ReviewSettings.Form"
      onSubmit={(event) => {
        event.preventDefault();
        if (!peersInvalid) {
          /*
            EVERY field, through `fullSettingsUpdate` — including `reviewEnabledIssue`, which this
            form does not offer and General does (D33). ~~The nine fields were listed here.~~ →
            superseded by the shared function, because the failure this comment describes had
            already happened once: `reviewEnabledIssue` landed on the contract, this list was not
            extended, and saving the review policy silently switched the issue close gate off.
          */
          save.mutate(fullSettingsUpdate(draft));
        }
      }}
    >
      <SettingGroup title="Where review applies" data-testid="ReviewSettings.Enabled">
        <p className="mb-1 max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3">
          Review is per artifact type. A project can require sign-off on release plans without
          putting every test case through a queue.
        </p>

        <SettingRow
          label="Test cases"
          description="The script is correct, complete and executable — §6g calls this the common case."
          consequence={
            counts && (
              <>
                <ImpactCount field="casesInReview" value={counts.casesInReview} /> in review right
                now. Turning this off leaves them where they stand — nothing is discarded, and the
                Draft / In review / Approved statuses go on working.
              </>
            )
          }
          control={
            <Switch
              checked={draft.reviewEnabledCase}
              disabled={!canEdit}
              onCheckedChange={(checked) => edit({ reviewEnabledCase: checked })}
              data-testid="ReviewSettings.reviewEnabledCase"
            />
          }
        />
        <SettingRow
          label="Suites"
          description="The suite's composition is right. Reviewing a suite does not approve the cases in it."
          consequence={
            counts && (
              <>
                <ImpactCount field="suitesInReview" value={counts.suitesInReview} /> in review right
                now.
              </>
            )
          }
          control={
            <Switch
              checked={draft.reviewEnabledSuite}
              disabled={!canEdit}
              onCheckedChange={(checked) => edit({ reviewEnabledSuite: checked })}
              data-testid="ReviewSettings.reviewEnabledSuite"
            />
          }
        />
        <SettingRow
          label="Test plans"
          description="Sign-off that this is the right scope to execute for the release."
          consequence={
            counts && (
              <>
                <ImpactCount field="plansInReview" value={counts.plansInReview} /> in review right
                now.
              </>
            )
          }
          control={
            <Switch
              checked={draft.reviewEnabledPlan}
              disabled={!canEdit}
              onCheckedChange={(checked) => edit({ reviewEnabledPlan: checked })}
              data-testid="ReviewSettings.reviewEnabledPlan"
            />
          }
        />
        <SettingRow
          label="Documentation pages"
          description="The page says what the product does. Reviewing a page does not approve the cases linked to it."
          consequence={
            counts && (
              <>
                <ImpactCount field="docsInReview" value={counts.docsInReview} /> in review, and{' '}
                <ImpactCount field="draftDocPages" value={counts.draftDocPages} /> still in draft —
                turning this on is what puts the drafts through a queue when their authors publish.
              </>
            )
          }
          control={
            <Switch
              checked={draft.reviewEnabledDoc}
              disabled={!canEdit}
              onCheckedChange={(checked) => edit({ reviewEnabledDoc: checked })}
              data-testid="ReviewSettings.reviewEnabledDoc"
            />
          }
        />

        {!reviewOn && (
          <Banner tone="neutral" className="mt-3" data-testid="ReviewSettings.AllOff">
            <span>
              Review is off everywhere in this project, so no review panel is shown on any artifact.
              The Draft / In review / Approved statuses still work — turning review off does not take
              them away, it stops a review record being kept.
            </span>
          </Banner>
        )}
      </SettingGroup>

      {/*
        Rendered whatever the switches say, deliberately, rather than hidden while review is off.
        These are the settings somebody configures *before* turning review on, and a group that
        appears only once the feature is enabled forces the two decisions into the wrong order.
      */}
      <SettingGroup title="What approval requires" data-testid="ReviewSettings.Approval">
        <SettingRow
          label="The person who submits something cannot approve it"
          description="On by default. Whoever submitted an artifact for review needs somebody else to sign it off."
          consequence="With one reviewer required, this is what stops a single person writing and approving a case alone."
          control={
            <Switch
              // Displayed as the RULE, stored as the PERMISSION — the contract field is
              // `authorMayApproveOwnWork` so no consumer reads a double negative, and the switch
              // is inverted here because "author cannot approve own work: on" is how §6g words it
              // and how an admin thinks about it.
              checked={!draft.authorMayApproveOwnWork}
              disabled={!canEdit}
              onCheckedChange={(checked) => edit({ authorMayApproveOwnWork: !checked })}
              data-testid="ReviewSettings.authorCannotApprove"
            />
          }
        />
        <SettingRow
          label="Peer reviews required before approval"
          description="Counted as people other than the submitter whose latest verdict is LGTM. Changing this applies to reviews submitted from now on, not to ones already open."
          consequence={
            peersInvalid ? (
              <span className="text-[color:var(--color-failed)]">Between 0 and 20.</span>
            ) : (
              counts && (
                <>
                  Raising it leaves the{' '}
                  <ImpactCount field="awaitingFinalApproval" value={counts.awaitingFinalApproval} />{' '}
                  already waiting on a final approval waiting for one more. They do not lose the
                  approvals they have.
                </>
              )
            )
          }
          control={
            <Input
              type="number"
              min={0}
              max={20}
              className="w-[92px] text-right font-mono tabular-nums"
              value={draft.requiredPeerReviews}
              disabled={!canEdit}
              aria-invalid={peersInvalid}
              aria-label="Peer reviews required before approval"
              onChange={(event) =>
                setDraft((d) => ({ ...d, requiredPeerReviews: Number(event.target.value) }))
              }
              data-testid="ReviewSettings.requiredPeerReviews"
            />
          }
        />
        <SettingRow
          label="When an approved artifact is edited"
          description={editAfterApprovalDescriptions[draft.editAfterApproval]}
          consequence={
            draft.editAfterApproval === 'RE_REVIEW' ? (
              <span data-testid="ReviewSettings.ReReviewWarning">
                Anybody who can edit an approved artifact can end its approval by saving it. That is
                what this setting does — approval attaches to the content that was reviewed — but it
                means a test engineer's ordinary edit will return a lead's sign-off to draft.
              </span>
            ) : (
              'The approval stands and the artifact is marked as changed since it was reviewed, which is what the stale marker on the review panel reads.'
            )
          }
          control={
            <NativeSelect
              value={draft.editAfterApproval}
              disabled={!canEdit}
              aria-label="When an approved artifact is edited"
              onChange={(event) =>
                setDraft((d) => ({ ...d, editAfterApproval: event.target.value as EditAfterApproval }))
              }
              data-testid="ReviewSettings.editAfterApproval"
            >
              {(['RETAIN', 'RE_REVIEW'] as const).map((mode) => (
                <option key={mode} value={mode} data-testid={`ReviewSettings.mode.${mode}`}>
                  {editAfterApprovalLabels[mode]}
                </option>
              ))}
            </NativeSelect>
          }
        />
      </SettingGroup>

      {/*
        A THIRD group rather than two more switches in "Where review applies", because these are not
        review settings — they are the only settings on this screen that bind on a project with
        review switched off. §6g's opt-in suppresses the reviewer apparatus; these read the status
        ladder, which every project has. Putting them under the opt-ins would say the opposite, in
        the one place an admin decides whether to turn them on.
      */}
      <SettingGroup title="Approval required before work starts" data-testid="ReviewSettings.Gating">
        <p className="mb-1 max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3">
          This applies whether or not review is switched on above — it reads the Draft / In review /
          Approved status, not the review process. A test lead can override it when a release cannot
          wait.
        </p>

        <SettingRow
          label="Only approved test cases can go into a run"
          description="A run is refused if anything in it is not approved — including cases pulled in through a plan’s scope."
          consequence={counts && <BlockedPlans projectId={projectId} plans={counts.plansBlockedFromRunning} />}
          control={
            <Switch
              checked={draft.requireApprovedCaseForRun}
              disabled={!canEdit}
              onCheckedChange={(checked) => edit({ requireApprovedCaseForRun: checked })}
              data-testid="ReviewSettings.requireApprovedCaseForRun"
            />
          }
        />

        {gatingOn && (
          <Banner tone="warn" className="mt-3" data-testid="ReviewSettings.GatingWarning">
            <span>
              This stops work that is already under way. Nobody can start a run or a release until
              somebody with approval rights has signed the content off — plan §6g calls this
              &ldquo;powerful and genuinely obstructive&rdquo;, which is why it is off by default.
            </span>
          </Banner>
        )}
      </SettingGroup>

      {save.isError && (
        <Banner tone="bad" data-testid="ReviewSettings.Error">
          {/*
            `describeError` is the ONE place an error becomes user-facing copy, and it returns a
            title and a body rather than a string — rendering the raw error here is what that file
            exists to prevent.
          */}
          <span>{describeError(save.error).body}</span>
        </Banner>
      )}

      <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-line pt-4">
        <Button
          type="submit"
          variant="primary"
          disabled={!canEdit || !dirty || peersInvalid || save.isPending}
          data-testid="ReviewSettings.Save"
        >
          {save.isPending ? 'Saving…' : 'Save settings'}
        </Button>
        <Button
          type="button"
          disabled={!dirty || save.isPending}
          onClick={() => setDraft(JSON.parse(serverFingerprint) as ProjectSettings)}
          data-testid="ReviewSettings.Reset"
        >
          Discard changes
        </Button>

        {/*
          Absent for a project nobody has configured — including every project V23 backfilled, which
          is all of them on the day this ships. "Never changed" is the true statement; attributing
          the defaults to whoever created the project would not be.
        */}
        <span className="ml-auto text-[11.5px] text-ink-4">
          {current.updatedAt
            ? `Last changed ${formatDateTime(current.updatedAt)}${
                current.updatedBy ? ` by ${current.updatedBy.displayName}` : ''
              }`
            : 'Never changed'}
        </span>
        <span className="text-[11.5px] text-ink-4">
          Nothing here takes effect until Save, and every change is written to the audit trail with
          the old value.
        </span>
      </div>
    </form>
  );
}

/**
 * One number of the blast radius, wearing the testid the panel that used to hold it wore.
 *
 * The testids are `ReviewSettings.Impact.<field>` and they did not move when the counts did — a
 * locator is a contract with the QA suite, and renaming one because a number changed neighbours
 * is how a screen loses its coverage silently.
 */
function ImpactCount({ field, value }: { field: keyof ReviewImpact; value: number }) {
  return (
    <b className="font-mono font-medium tabular-nums text-ink-3" data-testid={`ReviewSettings.Impact.${field}`}>
      {formatCount(value)}
    </b>
  );
}

/**
 * The plans a run would currently be refused for — named, and linked.
 *
 * "3 plans" is not something an administrator can act on and "Q3 compliance sweep" is; the remedy
 * is per plan, so the plans are the answer. Empty is the good state and says so rather than
 * rendering nothing: a consequence that disappears when it is healthy makes the row's shape change
 * under the reader, and "nothing is blocked" is worth being able to see the answer to.
 */
function BlockedPlans({
  projectId,
  plans,
}: {
  projectId: string;
  plans: ReviewImpact['plansBlockedFromRunning'];
}) {
  if (plans.length === 0) {
    return <span data-testid="ReviewSettings.Impact.NoBlockedPlans">No plan is currently blocked from running.</span>;
  }

  return (
    <span data-testid="ReviewSettings.Impact.BlockedPlans">
      {formatCount(plans.length)} {plans.length === 1 ? 'plan' : 'plans'} would be refused a run
      today — their scope holds a case that is not approved:{' '}
      {plans.map((plan, index) => (
        <span key={plan.planId}>
          {index > 0 && ', '}
          <RouterLink
            to={`/projects/${projectId}/plans/${plan.planId}`}
            className="text-steel hover:underline"
            data-testid="ReviewSettings.Impact.BlockedPlan"
          >
            {plan.key} — {plan.name}
          </RouterLink>
        </span>
      ))}
    </span>
  );
}
