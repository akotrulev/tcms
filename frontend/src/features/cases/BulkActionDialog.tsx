import {
  Alert,
  AlertTitle,
  Autocomplete,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import type {
  AutomationStatus,
  BulkPayload,
  BulkResult,
  Condition,
  LifecycleStatus,
} from '../../api/types';
import { ApiError } from '../../api/client';
import { useCan } from '../../auth/useCan';
import { Identifier } from '../../components/Identifier';
import { automationLabels, lifecycleLabels } from '../../lib/labels';
import {
  useBulkCases,
  useConditions,
  useProjectMembers,
  useSuites,
} from '../../api/queries';
import { describeError } from '../../lib/describeError';
import { formatCount } from '../../lib/format';

/**
 * Bulk edits are **preview, then commit**.
 *
 * The dialog runs the operation as a dry run first and shows exactly what would change,
 * what is already compliant, and what would be skipped and why. Only then does the confirm
 * button commit. A mass edit that applies straight from a selection is how someone
 * "approves 240 cases" at 5pm and discovers on Monday that 30 of them shouldn't have been.
 *
 * The result is reported **per item**, not as a blanket toast: "38 changed, 4 already
 * approved, 2 skipped" with the skipped ones named. A blanket success over a partial
 * failure is the worse half of that defect, because the user believes all 44 went through.
 *
 * ## Approving is gated separately from editing, inside one select
 *
 * `case:approve` is Lead-and-up while `case:edit` reaches Test Engineer (roles spec's Review
 * table; plan §6g's single gate), so the server's permission depends on the *value* of the status
 * field rather than on the action. §31's disable-don't-hide therefore applies to **one option**,
 * not to the dialog: a Test Engineer gets a working dialog with "Approved" greyed out and a reason
 * on hover, instead of a form that submits and comes back 403.
 *
 * That is possible here and was not in §61's artifact register, and the difference is worth
 * knowing: `case:approve` is a real permission key, so `GET /me/permissions` carries it. §61's
 * refusal turned on a *qualifier*, which is not on the wire at all — there the button had to enable
 * and the server had to answer with a field error.
 */

/** The contract's `BulkOperation.action` values, in the order the picker offers them. */
type Action =
  | 'SET_STATUS'
  | 'SUBMIT_FOR_REVIEW'
  | 'SET_OWNER'
  | 'MOVE_TO_SUITE'
  | 'ADD_TAGS'
  | 'SET_AUTOMATION_STATUS'
  | 'ATTACH_CONDITIONS'
  | 'DETACH_CONDITIONS';

const ACTIONS: { value: Action; label: string }[] = [
  { value: 'SET_STATUS', label: 'Change status' },
  { value: 'SUBMIT_FOR_REVIEW', label: 'Submit for review' },
  { value: 'SET_OWNER', label: 'Assign an owner' },
  { value: 'MOVE_TO_SUITE', label: 'Move to a suite' },
  { value: 'ADD_TAGS', label: 'Add tags' },
  { value: 'SET_AUTOMATION_STATUS', label: 'Change automation status' },
  { value: 'ATTACH_CONDITIONS', label: 'Add a precondition' },
  { value: 'DETACH_CONDITIONS', label: 'Remove a precondition' },
];

/**
 * The payload field each action reads, so a server error can be routed to the right control.
 *
 * Kept as data rather than as a chain of `if`s in the error handler: the server names the field it
 * refused, and the mapping from field to control has to exist somewhere. A message that matches no
 * control here falls back to the dialog-level alert rather than vanishing.
 */
const FIELDS = [
  'caseIds',
  'lifecycleStatus',
  'automationStatus',
  'notAutomatableReason',
  'ownerId',
  'suiteId',
  'tags',
  'conditionId',
] as const;

type FieldName = (typeof FIELDS)[number];

const TestIdPrefix = 'Cases.BulkDialog';

export function BulkActionDialog({
  open,
  onClose,
  projectId,
  selectedIds,
  onCommitted,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  selectedIds: string[];
  onCommitted: (result: BulkResult) => void;
}) {
  const [action, setAction] = useState<Action>('SET_STATUS');
  const [lifecycleValue, setLifecycleValue] = useState<LifecycleStatus>('IN_REVIEW');
  const [automationValue, setAutomationValue] = useState<AutomationStatus>('AUTOMATED');
  const [notAutomatableReason, setNotAutomatableReason] = useState('');
  const [ownerId, setOwnerId] = useState('');
  const [suiteId, setSuiteId] = useState('');
  const [tags, setTags] = useState<string[]>([]);
  const [conditionId, setConditionId] = useState('');

  const [preview, setPreview] = useState<BulkResult | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({});
  const [rootError, setRootError] = useState<string | null>(null);

  // Every list the dialog can need. `enabled` is not gated on the current action on purpose: these
  // are small, cached per project, and shared with the screens behind this dialog, so fetching on
  // open costs one round trip the user has usually already paid and removes a spinner from the
  // moment they switch operation.
  const conditions = useConditions(projectId, '', 'PRE');
  const suites = useSuites(projectId);
  const members = useProjectMembers(projectId, action === 'SET_OWNER');
  const bulk = useBulkCases(projectId);

  /**
   * The approve verdict, read here rather than through `PermissionGate`.
   *
   * **`PermissionGate` cannot be used inside a `Select`, and the way it fails is silent.** MUI's
   * `Select` clones its *direct* children to inject `role="option"`, `data-value`, `aria-selected`
   * and the handler that actually selects the value. A `MenuItem` wrapped in anything — the gate's
   * render prop, its denied-branch tooltip span — is invisible to that pass, so it renders as an
   * inert `role="menuitem"` with no `data-value`: present, clickable-looking, and **impossible to
   * choose**. A live pass found exactly that, with the option unselectable for a Company Admin who
   * holds the permission, while the component test went green because it looked the item up by
   * `data-testid` and asked whether it was disabled — and an element MUI never processed carries no
   * `aria-disabled` either, so "not disabled" and "not an option at all" read identically.
   *
   * So the verdict is computed in the body and passed as a plain `disabled` prop, and the reason is
   * rendered *inside* the item rather than in a tooltip wrapper around it. `PermissionGate` remains
   * right everywhere it wraps a button or a bare `Menu` item; a `Select`'s children are the one
   * place a wrapper changes what the child *is*.
   */
  const gate = useCan(projectId);
  const mayApprove = gate.can('case:approve');
  const approveReason = gate.reason('case:approve');

  // Changing the action or its argument invalidates any preview taken against the previous
  // one — committing a preview that describes a different operation is the bug this prevents.
  useEffect(() => {
    setPreview(null);
    setFieldErrors({});
    setRootError(null);
  }, [
    action,
    lifecycleValue,
    automationValue,
    notAutomatableReason,
    ownerId,
    suiteId,
    tags,
    conditionId,
  ]);

  const payload: BulkPayload = useMemo(() => {
    switch (action) {
      case 'SET_STATUS':
        return { lifecycleStatus: lifecycleValue };
      case 'SET_AUTOMATION_STATUS':
        return {
          automationStatus: automationValue,
          // Sent only where the column allows it, matching the server: a reason stored beside
          // AUTOMATED is residue explaining an impossibility nobody claimed.
          notAutomatableReason:
            automationValue === 'NOT_AUTOMATABLE' ? notAutomatableReason.trim() : undefined,
        };
      case 'SET_OWNER':
        // Empty means unassign, and `null` is how the contract spells that — `undefined` would be
        // an absent member and the server would read it as "no argument".
        return { ownerId: ownerId === '' ? null : ownerId };
      case 'MOVE_TO_SUITE':
        return { suiteId };
      case 'ADD_TAGS':
        return { tags };
      case 'ATTACH_CONDITIONS':
        return { conditionId, conditionKind: 'PRE' };
      case 'DETACH_CONDITIONS':
        return { conditionId };
      case 'SUBMIT_FOR_REVIEW':
        // The verb carries the status. Sending one would be a contradiction the server refuses.
        return {};
    }
  }, [
    action,
    lifecycleValue,
    automationValue,
    notAutomatableReason,
    ownerId,
    suiteId,
    tags,
    conditionId,
  ]);

  /** Whether the argument is complete enough to be worth a round trip. */
  const hasArgument = (): boolean => {
    switch (action) {
      case 'SET_STATUS':
        return !!lifecycleValue;
      case 'SET_AUTOMATION_STATUS':
        return automationValue !== 'NOT_AUTOMATABLE' || notAutomatableReason.trim().length > 0;
      case 'MOVE_TO_SUITE':
        return !!suiteId;
      case 'ADD_TAGS':
        return tags.length > 0;
      case 'ATTACH_CONDITIONS':
      case 'DETACH_CONDITIONS':
        return !!conditionId;
      // Unassigning is a real choice, so an empty owner is a complete argument.
      case 'SET_OWNER':
      case 'SUBMIT_FOR_REVIEW':
        return true;
    }
  };

  const canRun = selectedIds.length > 0 && hasArgument();

  const run = async (dryRun: boolean) => {
    setFieldErrors({});
    setRootError(null);
    try {
      const result = await bulk.mutateAsync({
        caseIds: selectedIds,
        action,
        payload,
        dryRun,
      });
      if (dryRun) setPreview(result);
      else {
        onCommitted(result);
        handleClose();
      }
    } catch (error) {
      applyServerError(error);
    }
  };

  /** Lands a 400's per-field messages on their controls; anything else on the dialog alert. */
  const applyServerError = (error: unknown) => {
    if (error instanceof ApiError && error.fieldErrors) {
      const landed: Partial<Record<FieldName, string>> = {};
      for (const [field, messages] of Object.entries(error.fieldErrors)) {
        if ((FIELDS as readonly string[]).includes(field)) {
          landed[field as FieldName] = messages.join(' ');
        }
      }
      if (Object.keys(landed).length > 0) {
        setFieldErrors(landed);
        return;
      }
    }
    // A refusal with no field — a 403, or a key this form has no control for. Showing nothing at
    // all is worse than showing a vague message.
    setRootError(describeError(error).body);
  };

  const handleClose = () => {
    setPreview(null);
    setFieldErrors({});
    setRootError(null);
    onClose();
  };

  return (
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth data-testid={TestIdPrefix}>
      <DialogTitle>
        Edit {formatCount(selectedIds.length)} selected {selectedIds.length === 1 ? 'case' : 'cases'}
      </DialogTitle>

      <DialogContent dividers>
        <Stack sx={{ gap: 2 }}>
          <Field label="Operation" htmlFor="bulk-operation">
            <Select
              id="bulk-operation"
              size="small"
              fullWidth
              value={action}
              onChange={(event) => setAction(event.target.value as Action)}
              data-testid={`${TestIdPrefix}.Operation`}
            >
              {ACTIONS.map((op) => (
                <MenuItem key={op.value} value={op.value}>
                  {op.label}
                </MenuItem>
              ))}
            </Select>
          </Field>

          {action === 'SET_STATUS' && (
            <Field label="New status" htmlFor="bulk-status" error={fieldErrors.lifecycleStatus}>
              <Select
                id="bulk-status"
                size="small"
                fullWidth
                value={lifecycleValue}
                onChange={(event) => setLifecycleValue(event.target.value as LifecycleStatus)}
                data-testid={`${TestIdPrefix}.Field.LifecycleStatus`}
              >
                {(Object.entries(lifecycleLabels) as [LifecycleStatus, string][]).map(
                  ([key, label]) => (
                    <MenuItem
                      key={key}
                      value={key}
                      // Only APPROVED is gated: the server refuses that one value and accepts the
                      // other three from anyone holding `case:edit`.
                      disabled={key === 'APPROVED' && !mayApprove}
                      data-testid={`${TestIdPrefix}.Status.${key}`}
                    >
                      {label}
                      {key === 'APPROVED' && !mayApprove && (
                        <Typography variant="caption" sx={{ ml: 1, color: 'text.secondary' }}>
                          — {approveReason ?? 'not yours to grant'}
                        </Typography>
                      )}
                    </MenuItem>
                  ),
                )}
              </Select>
            </Field>
          )}

          {action === 'SET_AUTOMATION_STATUS' && (
            <>
              <Field
                label="Automation status"
                htmlFor="bulk-automation"
                error={fieldErrors.automationStatus}
              >
                <Select
                  id="bulk-automation"
                  size="small"
                  fullWidth
                  value={automationValue}
                  onChange={(event) => setAutomationValue(event.target.value as AutomationStatus)}
                  data-testid={`${TestIdPrefix}.Field.AutomationStatus`}
                >
                  {Object.entries(automationLabels).map(([key, label]) => (
                    <MenuItem key={key} value={key}>
                      {label}
                    </MenuItem>
                  ))}
                </Select>
              </Field>

              {/* Required by V1's check constraint, so the field appears exactly when it applies
                  rather than being always present and conditionally validated. */}
              {automationValue === 'NOT_AUTOMATABLE' && (
                <Field
                  label="Why these cases cannot be automated"
                  htmlFor="bulk-not-automatable"
                  error={fieldErrors.notAutomatableReason}
                >
                  <TextField
                    id="bulk-not-automatable"
                    size="small"
                    fullWidth
                    value={notAutomatableReason}
                    onChange={(event) => setNotAutomatableReason(event.target.value)}
                    data-testid={`${TestIdPrefix}.Field.NotAutomatableReason`}
                  />
                </Field>
              )}
            </>
          )}

          {action === 'SET_OWNER' && (
            <Field label="Owner" htmlFor="bulk-owner" error={fieldErrors.ownerId}>
              <Select
                id="bulk-owner"
                size="small"
                fullWidth
                displayEmpty
                value={ownerId}
                onChange={(event) => setOwnerId(event.target.value)}
                data-testid={`${TestIdPrefix}.Field.Owner`}
              >
                {/* The unassign option is offered explicitly rather than left as "pick nobody":
                    clearing an owner across 40 cases is a real intent, and a blank that means two
                    things is how it gets performed by accident. */}
                <MenuItem value="">No owner</MenuItem>
                {(members.data ?? []).map((member) => (
                  <MenuItem key={member.id} value={member.id}>
                    {member.displayName}
                  </MenuItem>
                ))}
              </Select>
            </Field>
          )}

          {action === 'MOVE_TO_SUITE' && (
            <Field label="Destination suite" htmlFor="bulk-suite" error={fieldErrors.suiteId}>
              <Select
                id="bulk-suite"
                size="small"
                fullWidth
                displayEmpty
                value={suiteId}
                onChange={(event) => setSuiteId(event.target.value)}
                data-testid={`${TestIdPrefix}.Field.Suite`}
              >
                <MenuItem value="">Choose a suite…</MenuItem>
                {(suites.data ?? []).map((suite) => (
                  <MenuItem key={suite.id} value={suite.id}>
                    {suite.name}
                  </MenuItem>
                ))}
              </Select>
            </Field>
          )}

          {action === 'ADD_TAGS' && (
            <Field label="Tags" htmlFor="bulk-tags" error={fieldErrors.tags}>
              <Autocomplete
                multiple
                freeSolo
                options={[]}
                value={tags}
                onChange={(_event, value) => setTags(value as string[])}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    id="bulk-tags"
                    size="small"
                    placeholder="Type a tag and press Enter"
                    data-testid={`${TestIdPrefix}.Field.Tags`}
                  />
                )}
              />
            </Field>
          )}

          {(action === 'ATTACH_CONDITIONS' || action === 'DETACH_CONDITIONS') && (
            <Field label="Precondition" htmlFor="bulk-condition" error={fieldErrors.conditionId}>
              <Select
                id="bulk-condition"
                size="small"
                fullWidth
                displayEmpty
                value={conditionId}
                onChange={(event) => setConditionId(event.target.value)}
                data-testid={`${TestIdPrefix}.Field.Condition`}
              >
                <MenuItem value="">Choose a condition…</MenuItem>
                {(conditions.data ?? []).map((condition: Condition) => (
                  <MenuItem key={condition.id} value={condition.id}>
                    {condition.title}
                    {/* Omitted at zero rather than rendered as "used by 0". Nothing maintains
                        usage_count yet, so on a real instance every row would carry it — a label
                        stating a fact the data never made, and one that invites reading the
                        order as a ranking when it is currently just the title. */}
                    {condition.usageCount > 0 && (
                      <Box component="span" sx={{ ml: 1, color: 'text.secondary', fontSize: '0.75rem' }}>
                        used by {formatCount(condition.usageCount)}
                      </Box>
                    )}
                  </MenuItem>
                ))}
              </Select>
            </Field>
          )}

          {action === 'SUBMIT_FOR_REVIEW' && (
            <Alert severity="info" icon={false} data-testid={`${TestIdPrefix}.SubmitNote`}>
              Moves the selected cases to <strong>In review</strong>. Approving them is a separate
              step, and a test lead's.
            </Alert>
          )}

          {preview && <BulkPreview result={preview} />}

          {(rootError || fieldErrors.caseIds) && (
            <Alert severity="error" role="alert" data-testid={`${TestIdPrefix}.Error`}>
              {rootError ?? fieldErrors.caseIds}
            </Alert>
          )}
        </Stack>
      </DialogContent>

      <DialogActions>
        <Button onClick={handleClose} data-testid={`${TestIdPrefix}.Cancel`}>
          Cancel
        </Button>
        <Button
          onClick={() => void run(true)}
          disabled={!canRun || bulk.isPending}
          data-testid={`${TestIdPrefix}.Preview`}
        >
          {bulk.isPending && !preview ? 'Checking…' : 'Preview changes'}
        </Button>
        <Button
          variant="contained"
          onClick={() => void run(false)}
          // Commit is gated on having seen a preview — the point of preview-then-commit is
          // lost if the user can skip straight past it.
          disabled={!preview || preview.changed.length === 0 || bulk.isPending}
          title={!preview ? 'Preview the changes first' : undefined}
          data-testid={`${TestIdPrefix}.Confirm`}
        >
          {preview ? `Apply to ${formatCount(preview.changed.length)} cases` : 'Apply'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * One labelled control, with its server-side error underneath.
 *
 * Extracted because the dialog now has seven of these and the label/error pairing is exactly the
 * kind of two-line duplication that ends up inconsistent — one field showing its error in red
 * caption, another in a helper text, a third silently swallowing it.
 */
function Field({
  label,
  htmlFor,
  error,
  children,
}: {
  label: string;
  htmlFor: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <Box>
      <Typography
        variant="caption"
        component="label"
        htmlFor={htmlFor}
        sx={{ display: 'block', mb: 0.5 }}
      >
        {label}
      </Typography>
      {children}
      {error && (
        <Typography variant="caption" role="alert" sx={{ color: 'error.main', mt: 0.5, display: 'block' }}>
          {error}
        </Typography>
      )}
    </Box>
  );
}

function BulkPreview({ result }: { result: BulkResult }) {
  return (
    <Box data-testid={`${TestIdPrefix}.Preview.Result`}>
      <Alert
        severity={result.skipped.length > 0 ? 'warning' : 'info'}
        icon={false}
        sx={{ '& .MuiAlert-message': { width: '100%' } }}
      >
        <AlertTitle sx={{ fontSize: '0.875rem' }}>Preview</AlertTitle>
        <Stack sx={{ gap: 1 }}>
          <Typography variant="body2">
            <strong>{formatCount(result.changed.length)}</strong> will change ·{' '}
            <strong>{formatCount(result.unchanged.length)}</strong> already match ·{' '}
            <strong>{formatCount(result.skipped.length)}</strong> will be skipped
          </Typography>

          {result.changed.length > 0 && (
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                Changing
              </Typography>
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, mt: 0.5 }}>
                {result.changed.slice(0, 12).map((key) => (
                  <Identifier key={key} variant="muted">
                    {key}
                  </Identifier>
                ))}
                {result.changed.length > 12 && (
                  <Typography variant="caption">+{result.changed.length - 12} more</Typography>
                )}
              </Box>
            </Box>
          )}

          {/* Skipped items are named with their reason — "2 skipped" alone leaves the user
              with no way to find out which two, or what to do about them. */}
          {result.skipped.length > 0 && (
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                Skipped
              </Typography>
              <Stack component="ul" sx={{ m: 0, mt: 0.5, pl: 2, gap: 0.25 }}>
                {result.skipped.map((item) => (
                  <Box component="li" key={item.key}>
                    <Typography variant="caption">
                      <Identifier variant="muted">{item.key}</Identifier> — {item.reason}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </Box>
          )}
        </Stack>
      </Alert>
    </Box>
  );
}
