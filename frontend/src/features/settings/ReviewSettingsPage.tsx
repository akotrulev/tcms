import {
  Alert,
  Box,
  Button,
  Divider,
  FormControl,
  FormControlLabel,
  FormHelperText,
  MenuItem,
  Select,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useProject, useProjectSettings, useUpdateProjectSettings } from '../../api/queries';
import type { EditAfterApproval, ProjectSettings } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { editAfterApprovalDescriptions, editAfterApprovalLabels } from '../../lib/labels';
import { inputTestId, selectTestId } from '../../lib/muiTestIds';

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
 * §82 added a third panel for §6g's two **gating** rules, and they are deliberately not review
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
 */
export function ReviewSettingsPage() {
  const { projectId = '' } = useParams();

  const project = useProject(projectId);
  const settings = useProjectSettings(projectId);

  return (
    <Box>
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
    </Box>
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
    draft.reviewEnabledCase || draft.reviewEnabledSuite || draft.reviewEnabledPlan;

  const gatingOn = draft.requireApprovedCaseForRun || draft.requireApprovedPlanToStart;

  return (
    <Stack sx={{ gap: 3 }} component="form" data-testid="ReviewSettings.Form"
      onSubmit={(event) => {
        event.preventDefault();
        if (!peersInvalid) {
          /*
            EVERY field, and the two gating ones are why this is worth a comment. The endpoint is a
            PUT of the whole value and the server reads an absent boolean as `false` — so a field
            listed on the type and omitted here would not fail to save, it would silently switch
            the setting OFF on every save of this form. That is `ui-rules`' highest-severity form
            defect (an edit that shows success and discards a value), and for a gating rule it means
            an admin fixing a typo in the peer-review count quietly reopens the gate.
          */
          save.mutate({
            reviewEnabledCase: draft.reviewEnabledCase,
            reviewEnabledSuite: draft.reviewEnabledSuite,
            reviewEnabledPlan: draft.reviewEnabledPlan,
            requiredPeerReviews: draft.requiredPeerReviews,
            authorMayApproveOwnWork: draft.authorMayApproveOwnWork,
            editAfterApproval: draft.editAfterApproval,
            requireApprovedCaseForRun: draft.requireApprovedCaseForRun,
            requireApprovedPlanToStart: draft.requireApprovedPlanToStart,
          });
        }
      }}
    >
      <Panel title="Where review applies" testId="ReviewSettings.Enabled">
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
          Review is per artifact type. A project can require sign-off on release plans without
          putting every test case through a queue.
        </Typography>

        <Stack>
          {(
            [
              ['reviewEnabledCase', 'Test cases', 'The script is correct, complete and executable — §6g calls this the common case.'],
              ['reviewEnabledSuite', 'Suites', "The suite's composition is right. Reviewing a suite does not approve the cases in it."],
              ['reviewEnabledPlan', 'Test plans', 'Sign-off that this is the right scope to execute for the release.'],
            ] as const
          ).map(([field, label, help]) => (
            <FormControlLabel
              key={field}
              sx={{ alignItems: 'flex-start', mb: 1.5 }}
              control={
                <Switch
                  checked={draft[field]}
                  disabled={!canEdit}
                  onChange={(event) =>
                    setDraft((d) => ({ ...d, [field]: event.target.checked }))
                  }
                  // `slotProps`, not `inputProps` — MUI v7's spelling, and the pre-v7 one is what
                  // this file was first written with, which is the trap firing a FOURTH time
                  // (§71, §74, and §74's own note that the dialog was written from a file
                  // predating the helper). `muiTestIds` owns the shape; the prop name is the part
                  // it cannot own.
                  slotProps={inputTestId(`ReviewSettings.${field}`)}
                />
              }
              label={
                <Box sx={{ pt: 0.75 }}>
                  <Typography variant="body2">{label}</Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    {help}
                  </Typography>
                </Box>
              }
            />
          ))}
        </Stack>

        {!reviewOn && (
          <Alert severity="info" sx={{ mt: 1 }} data-testid="ReviewSettings.AllOff">
            Review is off everywhere in this project, so no review panel is shown on any artifact.
            The Draft / In review / Approved statuses still work — turning review off does not take
            them away, it stops a review record being kept.
          </Alert>
        )}
      </Panel>

      <Panel title="What approval requires" testId="ReviewSettings.Approval">
        {/*
          Rendered whatever the switches say, deliberately, rather than hidden while review is off.
          These are the settings somebody configures *before* turning review on, and a section that
          appears only once the feature is enabled forces the two decisions into the wrong order.
        */}
        <Stack sx={{ gap: 3 }}>
          <FormControlLabel
            sx={{ alignItems: 'flex-start' }}
            control={
              <Switch
                // Displayed as the RULE, stored as the PERMISSION — the contract field is
                // `authorMayApproveOwnWork` so no consumer reads a double negative, and the switch
                // is inverted here because "author cannot approve own work: on" is how §6g words it
                // and how an admin thinks about it.
                checked={!draft.authorMayApproveOwnWork}
                disabled={!canEdit}
                onChange={(event) =>
                  setDraft((d) => ({ ...d, authorMayApproveOwnWork: !event.target.checked }))
                }
                slotProps={inputTestId('ReviewSettings.authorCannotApprove')}
              />
            }
            label={
              <Box sx={{ pt: 0.75 }}>
                <Typography variant="body2">
                  The person who submits something cannot approve it
                </Typography>
                <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                  On by default. Whoever submitted an artifact for review needs somebody else to
                  sign it off.
                </Typography>
              </Box>
            }
          />

          <TextField
            label="Peer reviews required before approval"
            type="number"
            size="small"
            sx={{ maxWidth: 320 }}
            value={draft.requiredPeerReviews}
            disabled={!canEdit}
            error={peersInvalid}
            helperText={
              peersInvalid
                ? 'Between 0 and 20.'
                : 'Counted as people other than the submitter whose latest verdict is LGTM. Changing this applies to reviews submitted from now on, not to ones already open.'
            }
            onChange={(event) =>
              setDraft((d) => ({ ...d, requiredPeerReviews: Number(event.target.value) }))
            }
            slotProps={{
              htmlInput: { min: 0, max: 20, 'data-testid': 'ReviewSettings.requiredPeerReviews' },
            }}
          />

          <FormControl size="small" sx={{ maxWidth: 420 }} disabled={!canEdit}>
            <Typography variant="body2" sx={{ mb: 1 }}>
              When an approved artifact is edited
            </Typography>
            <Select
              value={draft.editAfterApproval}
              onChange={(event) =>
                setDraft((d) => ({
                  ...d,
                  editAfterApproval: event.target.value as EditAfterApproval,
                }))
              }
              SelectDisplayProps={selectTestId('ReviewSettings.editAfterApproval')}
            >
              {(['RETAIN', 'RE_REVIEW'] as const).map((mode) => (
                <MenuItem key={mode} value={mode} data-testid={`ReviewSettings.mode.${mode}`}>
                  {editAfterApprovalLabels[mode]}
                </MenuItem>
              ))}
            </Select>
            <FormHelperText>
              {editAfterApprovalDescriptions[draft.editAfterApproval]}
            </FormHelperText>
          </FormControl>

          {draft.editAfterApproval === 'RE_REVIEW' && (
            <Alert severity="warning" data-testid="ReviewSettings.ReReviewWarning">
              Anybody who can edit an approved artifact can end its approval by saving it. That is
              what this setting does — approval attaches to the content that was reviewed — but it
              means a test engineer's ordinary edit will return a lead's sign-off to draft.
            </Alert>
          )}
        </Stack>
      </Panel>

      {/*
        A THIRD panel rather than two more switches in "Where review applies", because these are not
        review settings — they are the only settings on this screen that bind on a project with
        review switched off. §6g's opt-in suppresses the reviewer apparatus; these read the status
        ladder, which every project has. Putting them under the opt-ins would say the opposite, in
        the one place an admin decides whether to turn them on.
      */}
      <Panel title="Approval required before work starts" testId="ReviewSettings.Gating">
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
          These apply whether or not review is switched on above — they read the Draft / In review /
          Approved status, not the review process. A test lead can override either one when a
          release cannot wait.
        </Typography>

        <Stack>
          {(
            [
              [
                'requireApprovedCaseForRun',
                'Only approved test cases can go into a run',
                'A run is refused if anything in it is not approved — including cases pulled in through a plan’s scope.',
              ],
              [
                'requireApprovedPlanToStart',
                'Only approved plans can be started',
                'A draft plan cannot be started. Reopening a closed plan is unaffected.',
              ],
            ] as const
          ).map(([field, label, help]) => (
            <FormControlLabel
              key={field}
              sx={{ alignItems: 'flex-start', mb: 1.5 }}
              control={
                <Switch
                  checked={draft[field]}
                  disabled={!canEdit}
                  onChange={(event) =>
                    setDraft((d) => ({ ...d, [field]: event.target.checked }))
                  }
                  slotProps={inputTestId(`ReviewSettings.${field}`)}
                />
              }
              label={
                <Box sx={{ pt: 0.75 }}>
                  <Typography variant="body2">{label}</Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    {help}
                  </Typography>
                </Box>
              }
            />
          ))}
        </Stack>

        {gatingOn && (
          <Alert severity="warning" sx={{ mt: 1 }} data-testid="ReviewSettings.GatingWarning">
            This stops work that is already under way. Nobody can start a run or a release until
            somebody with approval rights has signed the content off — plan §6g calls this
            &ldquo;powerful and genuinely obstructive&rdquo;, which is why it is off by default.
          </Alert>
        )}
      </Panel>

      {save.isError && (
        <Alert severity="error" data-testid="ReviewSettings.Error">
          {/*
            `describeError` is the ONE place an error becomes user-facing copy, and it returns a
            title and a body rather than a string — rendering the raw error here is what that file
            exists to prevent.
          */}
          {describeError(save.error).body}
        </Alert>
      )}

      <Divider />

      <Stack direction="row" sx={{ gap: 2, alignItems: 'center' }}>
        <Button
          type="submit"
          variant="contained"
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

        <Box sx={{ flex: 1 }} />

        {/*
          Absent for a project nobody has configured — including every project V23 backfilled, which
          is all of them on the day this ships. "Never changed" is the true statement; attributing
          the defaults to whoever created the project would not be.
        */}
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {current.updatedAt
            ? `Last changed ${formatDateTime(current.updatedAt)}${
                current.updatedBy ? ` by ${current.updatedBy.displayName}` : ''
              }`
            : 'Never changed'}
        </Typography>
      </Stack>
    </Stack>
  );
}
