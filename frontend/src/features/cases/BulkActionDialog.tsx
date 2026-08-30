import { Banner, MultiPicker } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
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
  useProjectTags,
  useSuites,
} from '../../api/queries';
import { describeError } from '../../lib/describeError';
import { formatCount, plural } from '../../lib/format';

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
 * knowing: `case:approve` is a real permission key, so `GET /me/permissions` carries it. ~~§61's
 * refusal turned on a *qualifier*, which is not on the wire at all — there the button had to enable
 * and the server had to answer with a field error.~~ → superseded in §65: qualifiers are on the
 * wire, so that gap is closed and `Gate.canOnScope` / `canOnAuthored` ask those questions directly.
 *
 * **No action offered here is qualified**, which is why this dialog reads none of them. ~~There is
 * no bulk delete, and `case:delete` is the only `OWN`-qualified key in the catalogue.~~ →
 * **superseded by `V51` (§207): NO key in the catalogue carries `OWN` any more**, so the claim is
 * true for a stronger reason than the one written here. The conditional below it stands unchanged
 * and is the reason to keep the paragraph: an action added later that *is* qualified needs the
 * row's author per selected case — which this dialog does not hold, since it works from a list of
 * ids — so it would be a design question, not a prop.
 *
 * ## Which status options are gated, and which are not
 *
 * Only `APPROVED` is disabled here, and since §208 that is the whole of what this dialog can know:
 * the other approve-keyed edge is *moving a case that is currently approved*, which depends on each
 * selected row rather than on the form. Those rows come back in `skipped` with a reason, which is
 * this dialog's standing shape for a partial failure.
 *
 * `DEPRECATED` is **not** gated, and it never was — which until §208 was a §31 gap in the direction
 * that is hardest to notice: the server refused it for every contributor while this select offered
 * it enabled, so a Test Engineer's Deprecate submitted and came back with every row skipped. §208
 * closed it from the server's side rather than by disabling a second option, because the option was
 * the half that was right: retiring a case nobody signed off costs `case:edit`.
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
  const projectTags = useProjectTags(projectId);
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
    <Dialog open={open} onOpenChange={(next: boolean) => !next && handleClose()}>
      <DialogContent className="max-w-lg" data-testid={TestIdPrefix}>
      <DialogHeader>
          <DialogTitle>
        Edit {formatCount(selectedIds.length)} selected {selectedIds.length === 1 ? 'case' : 'cases'}
      </DialogTitle>
        </DialogHeader>

      <div className="mt-4 flex flex-col gap-4">
        <div className="flex flex-col gap-4">
          <Field label="Operation" htmlFor="bulk-operation">
            <NativeSelect
              id="bulk-operation"
              value={action}
              onChange={(event) => setAction(event.target.value as Action)}
              data-testid={`${TestIdPrefix}.Operation`}
            >
              {ACTIONS.map((op) => (
                <option key={op.value} value={op.value}>
                  {op.label}
                </option>
              ))}
            </NativeSelect>
          </Field>

          {action === 'SET_STATUS' && (
            <Field label="New status" htmlFor="bulk-status" error={fieldErrors.lifecycleStatus}>
              <NativeSelect
                id="bulk-status"
                value={lifecycleValue}
                onChange={(event) => setLifecycleValue(event.target.value as LifecycleStatus)}
                data-testid={`${TestIdPrefix}.Field.LifecycleStatus`}
              >
                {(Object.entries(lifecycleLabels) as [LifecycleStatus, string][]).map(
                  ([key, label]) => (
                    <option
                      key={key}
                      value={key}
                      // Only APPROVED is gated: the server refuses that one value and accepts the
                      // other three from anyone holding `case:edit`.
                      disabled={key === 'APPROVED' && !mayApprove}
                      data-testid={`${TestIdPrefix}.Status.${key}`}
                    >
                      {label}
                      {key === 'APPROVED' && !mayApprove
                        ? ` — ${approveReason ?? 'not yours to grant'}`
                        : ''}
                    </option>
                  ),
                )}
              </NativeSelect>
            </Field>
          )}

          {action === 'SET_AUTOMATION_STATUS' && (
            <>
              <Field
                label="Automation status"
                htmlFor="bulk-automation"
                error={fieldErrors.automationStatus}
              >
                <NativeSelect
                  id="bulk-automation"
                  value={automationValue}
                  onChange={(event) => setAutomationValue(event.target.value as AutomationStatus)}
                  data-testid={`${TestIdPrefix}.Field.AutomationStatus`}
                >
                  {Object.entries(automationLabels).map(([key, label]) => (
                    <option key={key} value={key}>
                      {label}
                    </option>
                  ))}
                </NativeSelect>
              </Field>

              {/* Required by V1's check constraint, so the field appears exactly when it applies
                  rather than being always present and conditionally validated. */}
              {automationValue === 'NOT_AUTOMATABLE' && (
                <Field
                  label="Why these cases cannot be automated"
                  htmlFor="bulk-not-automatable"
                  error={fieldErrors.notAutomatableReason}
                >
                  <Input
                    id="bulk-not-automatable"
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
              <NativeSelect
                id="bulk-owner"
                value={ownerId}
                onChange={(event) => setOwnerId(event.target.value)}
                data-testid={`${TestIdPrefix}.Field.Owner`}
              >
                {/* The unassign option is offered explicitly rather than left as "pick nobody":
                    clearing an owner across 40 cases is a real intent, and a blank that means two
                    things is how it gets performed by accident. */}
                <option value="">No owner</option>
                {(members.data ?? []).map((member) => (
                  <option key={member.id} value={member.id}>
                    {member.displayName}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}

          {action === 'MOVE_TO_SUITE' && (
            <Field label="Destination suite" htmlFor="bulk-suite" error={fieldErrors.suiteId}>
              <NativeSelect
                id="bulk-suite"
                value={suiteId}
                onChange={(event) => setSuiteId(event.target.value)}
                data-testid={`${TestIdPrefix}.Field.Suite`}
              >
                <option value="">Choose a suite…</option>
                {(suites.data ?? []).map((suite) => (
                  <option key={suite.id} value={suite.id}>
                    {suite.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}

          {action === 'ADD_TAGS' && (
            <Field label="Tags" htmlFor="bulk-tags" error={fieldErrors.tags}>
              {/*
                The project's tags in use, most-used first — the third of the three controls §96
                shipped with an empty list. A bulk tag is the one most worth suggesting: it is
                applied to every selected case at once, so a typo here is a typo on all of them,
                and the fix is a second bulk action nobody knows they need.

                ~~§96's slot trap: an inline `data-testid` on a `TextField` lands on the ROOT div,
                not the input, so the locator resolved to an element `userEvent.type` cannot type
                into — and `...params.slotProps` had to come FIRST because MUI kept the selected
                chips in `slotProps.input.startAdornment`.~~ → superseded in T16. `MultiPicker`
                puts `data-testid` on the input itself and renders the chips as its own siblings,
                so there is no slot to get wrong and nothing to spread back in.
              */}
              <MultiPicker
                id="bulk-tags"
                options={(projectTags.data ?? []).map((usage) => usage.tag)}
                optionKey={(tag) => tag}
                optionLabel={(tag) => tag}
                optionText={(tag) => tag}
                selected={tags}
                onChange={setTags}
                onCreate={(typed) => setTags([...new Set([...tags, typed])])}
                placeholder="Type a tag and press Enter"
                data-testid={`${TestIdPrefix}.Field.Tags`}
              />
            </Field>
          )}

          {(action === 'ATTACH_CONDITIONS' || action === 'DETACH_CONDITIONS') && (
            <Field label="Precondition" htmlFor="bulk-condition" error={fieldErrors.conditionId}>
              <NativeSelect
                id="bulk-condition"
                value={conditionId}
                onChange={(event) => setConditionId(event.target.value)}
                data-testid={`${TestIdPrefix}.Field.Condition`}
              >
                <option value="">Choose a condition…</option>
                {(conditions.data ?? []).map((condition: Condition) => (
                  <option key={condition.id} value={condition.id}>
                    {condition.title}
                    {/* Omitted at zero rather than rendered as "used by 0". Nothing maintains
                        usage_count yet, so on a real instance every row would carry it — a label
                        stating a fact the data never made, and one that invites reading the
                        order as a ranking when it is currently just the title. */}
                    {condition.usageCount > 0 ? ` — used by ${formatCount(condition.usageCount)}` : ''}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}

          {action === 'SUBMIT_FOR_REVIEW' && (
            <div data-testid={`${TestIdPrefix}.SubmitNote`}>
            <Banner tone="steel">
              Moves the selected cases to <strong>In review</strong>. Approving them is a separate
              step, and a test lead's.
            </Banner>
          </div>
          )}

          {preview && <BulkPreview result={preview} />}

          {(rootError || fieldErrors.caseIds) && (
            <div data-testid={`${TestIdPrefix}.Error`}>
            <Banner tone="bad"><span role="alert">
              {rootError ?? fieldErrors.caseIds}
            </span></Banner>
          </div>
          )}
        </div>

      </div>
      <DialogFooter>
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
          variant="primary"
          onClick={() => void run(false)}
          // Commit is gated on having seen a preview — the point of preview-then-commit is
          // lost if the user can skip straight past it.
          disabled={!preview || preview.changed.length === 0 || bulk.isPending}
          title={!preview ? 'Preview the changes first' : undefined}
          data-testid={`${TestIdPrefix}.Confirm`}
        >
          {preview ? `Apply to ${plural(preview.changed.length, 'case')}` : 'Apply'}
        </Button>
      </DialogFooter>
    </DialogContent>
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
function BulkPreview({ result }: { result: BulkResult }) {
  return (
    <div data-testid={`${TestIdPrefix}.Preview.Result`}>
      <Banner
        // A lost decline warns as loudly as a skip, and it is the opposite kind of event: a skip
        // is something the operation will NOT do, this is something it will do that nobody asked
        // for (§193).
        tone={result.skipped.length > 0 || result.declinesEnded.length > 0 ? 'warn' : 'steel'}
        className="[&>div]:w-full"
      >
        <p className="m-0 mb-1 text-[13px] font-semibold text-ink">Preview</p>
        <div className="flex flex-col gap-2">
          <p className="m-0 text-[13px]">
            <strong>{formatCount(result.changed.length)}</strong> will change ·{' '}
            <strong>{formatCount(result.unchanged.length)}</strong> already match ·{' '}
            <strong>{formatCount(result.skipped.length)}</strong> will be skipped
          </p>

          {result.changed.length > 0 && (
            <div>
              <span className="m-0 text-[12px] font-semibold">
                Changing
              </span>
              <div className="flex flex-wrap gap-1.5 mt-1">
                {result.changed.slice(0, 12).map((key) => (
                  <Identifier key={key} variant="muted">
                    {key}
                  </Identifier>
                ))}
                {result.changed.length > 12 && (
                  <span className="m-0 text-[12px]">+{result.changed.length - 12} more</span>
                )}
              </div>
            </div>
          )}

          {/* The one consequence on this screen that the user did not ask for (§193). A move
              ends the declines the destination folder does not impose, and the server deletes
              them — so this is the only moment the person can decline to proceed. Named, not
              counted, for the same reason `skipped` is: "3 will lose an exception" leaves them
              no way to find out which three. */}
          {result.declinesEnded.length > 0 && (
            <div>
              <span className="m-0 text-[12px] font-semibold">
                Losing a condition exception
              </span>
              <div className="flex flex-wrap gap-1.5 mt-1">
                {result.declinesEnded.slice(0, 12).map((key) => (
                  <Identifier key={key} variant="muted">
                    {key}
                  </Identifier>
                ))}
                {result.declinesEnded.length > 12 && (
                  <span className="m-0 text-[12px]">
                    +{result.declinesEnded.length - 12} more
                  </span>
                )}
              </div>
              <span className="m-0 text-[12px] block mt-1">
                These cases turn an inherited condition off. The folder they are moving to does not
                impose it, so the exception ends — and moving them back will not bring it back.
              </span>
            </div>
          )}

          {/* Skipped items are named with their reason — "2 skipped" alone leaves the user
              with no way to find out which two, or what to do about them. */}
          {result.skipped.length > 0 && (
            <div>
              <span className="m-0 text-[12px] font-semibold">
                Skipped
              </span>
              <div className="flex flex-col m-0 mt-1 pl-4 gap-0.5">
                {result.skipped.map((item) => (
                  <li key={item.key}>
                    <span className="m-0 text-[12px]">
                      <Identifier variant="muted">{item.key}</Identifier> — {item.reason}
                    </span>
                  </li>
                ))}
              </div>
            </div>
          )}
        </div>
      </Banner>
    </div>
  );
}
