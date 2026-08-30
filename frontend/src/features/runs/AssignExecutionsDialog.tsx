import { Badge, Banner, MultiPicker } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../../api/client'
import type {
  ExecutionBulkOperation,
  ExecutionBulkResult,
  UserRef,
} from '../../api/types'
import { useBulkAssignExecutions, useProjectMembers } from '../../api/queries'
import { Identifier } from '../../components/Identifier'
import { describeError } from '../../lib/describeError'
import { formatCount } from '../../lib/format'
import { tallyByAssignee } from './executionSelection'

/**
 * Handing a selection of a run's checklist to people — **preview, then commit** (§244).
 *
 * `BulkActionDialog`'s structure, deliberately: mode state, an argument, an effect that drops a
 * stale preview whenever the argument changes, `run(dryRun)`, and a 400's field errors landed on
 * their own controls. A second spelling of "operate on a selection" is how one product starts
 * reading like two.
 *
 * ## Spreading is the mode this dialog exists for, and the order is visible because it decides
 *
 * The i-th row of the run-ordered selection goes to the i-th person in this list, wrapping — so
 * the people at the **top** take the remainder. A multi-select that hid its order would be a
 * control that lies about what the button will do, which is why the chosen people are rendered as
 * a numbered list and a caption says what the numbers mean.
 *
 * ## The preview names who receives nothing
 *
 * `tallyByAssignee` is seeded from the chosen people rather than derived from the result, so
 * somebody who ends up with zero rows still appears with a zero. A coordinator handing three
 * hundred cases to five colleagues needs to see that the fifth got none; a `groupBy` over what
 * changed drops exactly that row.
 *
 * ## Nobody is a value
 *
 * One-person mode offers an explicit **Nobody (unassign)** option, because the contract's `null`
 * assignee is a statement and not an omission — it is how "take these off everybody's plate" is
 * expressible at all.
 */

type Mode = 'ONE' | 'SPREAD'

/** The fields the server names in a 400, mapped to the control each belongs under. */
const FIELDS = ['executionIds', 'action', 'payload.assigneeId', 'payload.assigneeIds'] as const

type FieldName = (typeof FIELDS)[number]

const TestIdPrefix = 'RunDetail.AssignDialog'

export function AssignExecutionsDialog({
  open,
  onClose,
  projectId,
  runId,
  selectedIds,
  onCommitted,
}: {
  open: boolean
  onClose: () => void
  projectId: string
  runId: string
  selectedIds: string[]
  onCommitted: (result: ExecutionBulkResult) => void
}) {
  const [mode, setMode] = useState<Mode>('ONE')
  const [assigneeId, setAssigneeId] = useState('')
  const [assignees, setAssignees] = useState<UserRef[]>([])

  const [preview, setPreview] = useState<ExecutionBulkResult | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldName, string>>>({})
  const [rootError, setRootError] = useState<string | null>(null)

  const members = useProjectMembers(projectId, open)
  const bulk = useBulkAssignExecutions(projectId, runId)

  // Changing the mode or its argument invalidates a preview taken against the previous one —
  // committing a preview that describes a different act is the defect this prevents.
  useEffect(() => {
    setPreview(null)
    setFieldErrors({})
    setRootError(null)
  }, [mode, assigneeId, assignees])

  const body = useMemo<Omit<ExecutionBulkOperation, 'dryRun'>>(
    () =>
      mode === 'SPREAD'
        ? {
            action: 'DISTRIBUTE',
            executionIds: selectedIds,
            payload: { assigneeIds: assignees.map((person) => person.id) },
          }
        : {
            action: 'ASSIGN',
            executionIds: selectedIds,
            // Empty means nobody, and `null` is how the contract spells that — `undefined` would
            // be an absent argument and the server would refuse it as one.
            payload: { assigneeId: assigneeId === '' ? null : assigneeId },
          },
    [mode, assigneeId, assignees, selectedIds],
  )

  const canRun =
    selectedIds.length > 0 && (mode === 'ONE' || assignees.length > 0) && !bulk.isPending

  const run = async (dryRun: boolean) => {
    setFieldErrors({})
    setRootError(null)
    try {
      const result = await bulk.mutateAsync({ ...body, dryRun })
      if (dryRun) setPreview(result)
      else {
        onCommitted(result)
        handleClose()
      }
    } catch (error) {
      applyServerError(error)
    }
  }

  /** Lands a 400's per-field messages on their controls; anything else on the dialog alert. */
  const applyServerError = (error: unknown) => {
    if (error instanceof ApiError && error.fieldErrors) {
      const landed: Partial<Record<FieldName, string>> = {}
      for (const [field, messages] of Object.entries(error.fieldErrors)) {
        if ((FIELDS as readonly string[]).includes(field)) {
          landed[field as FieldName] = messages.join(' ')
        }
      }
      if (Object.keys(landed).length > 0) {
        setFieldErrors(landed)
        return
      }
    }
    // A 409 — the run ended while this dialog was open — and every other refusal with no field to
    // land on. Shown in place rather than as a corner toast, because it is a decision to hand back.
    setRootError(describeError(error).body)
  }

  const handleClose = () => {
    setPreview(null)
    setFieldErrors({})
    setRootError(null)
    onClose()
  }

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && handleClose()}>
      <DialogContent className="max-w-lg" data-testid={TestIdPrefix}>
      <DialogHeader>
          <DialogTitle>
        Assign {formatCount(selectedIds.length)} selected{' '}
        {selectedIds.length === 1 ? 'row' : 'rows'}
      </DialogTitle>
        </DialogHeader>

      <div className="mt-4 flex flex-col gap-4">
        <div className="flex flex-col gap-4">
          {/*
            ~~`ToggleButtonGroup exclusive`~~ → two `aria-pressed` buttons, T16. An exclusive group
            over two values is a two-state choice, not a set, so the port is the markup MUI was
            rendering underneath with the pressed state stated on each button rather than inferred
            from a class.
          */}
          <div
            role="group"
            aria-label="How to spread the work"
            className="flex gap-1"
            data-testid={`${TestIdPrefix}.Mode`}
          >
            {(
              [
                ['ONE', 'Give them all to one person'],
                ['SPREAD', 'Spread them across several'],
              ] as const
            ).map(([value, label]) => (
              <Button
                key={value}
                type="button"
                size="sm"
                variant={mode === value ? 'primary' : 'ghost'}
                aria-pressed={mode === value}
                onClick={() => setMode(value)}
                data-testid={`${TestIdPrefix}.Mode.${value}`}
              >
                {label}
              </Button>
            ))}
          </div>

          {mode === 'ONE' ? (
            <Field
              label="Assign to"
              htmlFor="assign-person"
              error={fieldErrors['payload.assigneeId']}
            >
              <NativeSelect
                id="assign-person"
                value={assigneeId}
                onChange={(event) => setAssigneeId(event.target.value)}
                data-testid={`${TestIdPrefix}.Person`}
              >
                <option value="">Nobody (unassign)</option>
                {(members.data ?? []).map((person) => (
                  <option key={person.id} value={person.id}>
                    {person.displayName}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          ) : (
            <Field
              label="Spread across"
              htmlFor="assign-people"
              error={fieldErrors['payload.assigneeIds']}
            >
              {/*
                ~~`params.slotProps.htmlInput`, not `params.inputProps` — MUI v7's spelling, and
                `params.slotProps` spread back in so overriding one slot does not drop the ref and
                adornments Autocomplete puts on the others.~~ → superseded in T16: there are no
                slots. The testid goes on the input because the input is what this renders.
              */}
              <MultiPicker
                id="assign-people"
                options={members.data ?? []}
                optionKey={(person) => person.id}
                optionLabel={(person) => person.displayName}
                optionText={(person) => person.displayName}
                selected={assignees}
                onChange={setAssignees}
                placeholder="Choose people"
                data-testid={`${TestIdPrefix}.People`}
              />
              {assignees.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mt-2" data-testid={`${TestIdPrefix}.Order`}>
                  {assignees.map((person, index) => (
                    <Badge tone="plain"
                      key={person.id}
                    >
{`${index + 1}. ${person.displayName}`}</Badge>
                  ))}
                </div>
              )}
              <span className="m-0 text-[12px] text-ink-2 block mt-1">
                Rows are handed out in this order, wrapping round — so the people at the top take
                the remainder.
              </span>
            </Field>
          )}

          {fieldErrors.executionIds && (
            <span className="m-0 text-[12px] text-[color:var(--color-failed)]" role="alert">
              {fieldErrors.executionIds}
            </span>
          )}

          {rootError && (
            <div data-testid={`${TestIdPrefix}.Error`}>
            <Banner tone="bad">
              {rootError}
            </Banner>
          </div>
          )}

          {preview && (
            <AssignPreview result={preview} order={assignees} />
          )}
        </div>

      </div>
      <DialogFooter>
        <Button onClick={handleClose} data-testid={`${TestIdPrefix}.Cancel`}>
          Cancel
        </Button>
        <Button
          onClick={() => void run(true)}
          disabled={!canRun}
          data-testid={`${TestIdPrefix}.Preview`}
        >
          {bulk.isPending && !preview ? 'Checking…' : 'Preview changes'}
        </Button>
        <Button
          variant="primary"
          onClick={() => void run(false)}
          // Commit is gated on having seen a preview — the point of preview-then-commit is lost if
          // the person can skip straight past it.
          disabled={!preview || preview.changed.length === 0 || bulk.isPending}
          title={!preview ? 'Preview the changes first' : undefined}
          data-testid={`${TestIdPrefix}.Confirm`}
        >
          {preview ? `Assign ${formatCount(preview.changed.length)} rows` : 'Assign'}
        </Button>
      </DialogFooter>
    </DialogContent>
    </Dialog>
  )
}

/** One labelled control with its server-side error underneath — `BulkActionDialog`'s `Field`. */
/**
 * Two readings of one result: who gets how many, and which case goes where.
 *
 * The per-row list is the answer a list of counts cannot give, and it is served in the run's own
 * order so it reads like the checklist behind the dialog.
 */
function AssignPreview({ result, order }: { result: ExecutionBulkResult; order: UserRef[] }) {
  const tally = tallyByAssignee(result.changed, order)

  return (
    <div data-testid={`${TestIdPrefix}.Preview.Result`}>
      <Banner
        tone={result.skipped.length > 0 ? 'warn' : 'steel'}
        className="[&>div]:w-full"
      >
        <p className="m-0 mb-1 text-[13px] font-semibold text-ink">Preview</p>
        <div className="flex flex-col gap-2">
          <p className="m-0 text-[13px]">
            <strong>{formatCount(result.changed.length)}</strong> will move ·{' '}
            <strong>{formatCount(result.unchanged.length)}</strong> already assigned to that person ·{' '}
            <strong>{formatCount(result.skipped.length)}</strong> will be skipped
          </p>

          {tally.length > 0 && (
            <div data-testid={`${TestIdPrefix}.Preview.Tally`}>
              <span className="m-0 text-[12px] font-semibold">
                The split
              </span>
              <div className="flex flex-col m-0 mt-1 pl-4 gap-0.5">
                {tally.map((share) => (
                  <li key={share.assigneeId} data-testid={`${TestIdPrefix}.Preview.Share`}>
                    <span className="m-0 text-[12px]">
                      {share.assigneeName ?? 'Unknown'} — {formatCount(share.count)}
                    </span>
                  </li>
                ))}
              </div>
            </div>
          )}

          {result.changed.length > 0 && (
            <div data-testid={`${TestIdPrefix}.Preview.Rows`}>
              <span className="m-0 text-[12px] font-semibold">
                Moving
              </span>
              <div className="flex flex-col m-0 mt-1 pl-4 gap-0.5">
                {result.changed.slice(0, 20).map((change) => (
                  <li key={change.executionId}>
                    <span className="m-0 text-[12px]">
                      <Identifier variant="muted">{change.caseKey}</Identifier> →{' '}
                      {change.assigneeName ?? 'nobody'}
                    </span>
                  </li>
                ))}
                {result.changed.length > 20 && (
                  <span className="m-0 text-[12px]">+{result.changed.length - 20} more</span>
                )}
              </div>
            </div>
          )}

          {/* Named with their reason — "2 skipped" alone leaves the reader with no way to find
              out which two, or what to do about them. */}
          {result.skipped.length > 0 && (
            <div>
              <span className="m-0 text-[12px] font-semibold">
                Skipped
              </span>
              <div className="flex flex-col m-0 mt-1 pl-4 gap-0.5">
                {result.skipped.map((item) => (
                  <li key={item.executionId}>
                    <span className="m-0 text-[12px]">
                      <Identifier variant="muted">{item.executionId}</Identifier> — {item.reason}
                    </span>
                  </li>
                ))}
              </div>
            </div>
          )}
        </div>
      </Banner>
    </div>
  )
}
