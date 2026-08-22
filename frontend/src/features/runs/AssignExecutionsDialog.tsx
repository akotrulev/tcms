import {
  Alert,
  AlertTitle,
  Autocomplete,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Select,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
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
    <Dialog open={open} onClose={handleClose} maxWidth="sm" fullWidth data-testid={TestIdPrefix}>
      <DialogTitle>
        Assign {formatCount(selectedIds.length)} selected{' '}
        {selectedIds.length === 1 ? 'row' : 'rows'}
      </DialogTitle>

      <DialogContent dividers>
        <Stack sx={{ gap: 2 }}>
          <ToggleButtonGroup
            exclusive
            size="small"
            value={mode}
            onChange={(_, next: Mode | null) => next && setMode(next)}
            data-testid={`${TestIdPrefix}.Mode`}
          >
            <ToggleButton value="ONE" data-testid={`${TestIdPrefix}.Mode.ONE`}>
              Give them all to one person
            </ToggleButton>
            <ToggleButton value="SPREAD" data-testid={`${TestIdPrefix}.Mode.SPREAD`}>
              Spread them across several
            </ToggleButton>
          </ToggleButtonGroup>

          {mode === 'ONE' ? (
            <Field
              label="Assign to"
              htmlFor="assign-person"
              error={fieldErrors['payload.assigneeId']}
            >
              <Select
                id="assign-person"
                size="small"
                fullWidth
                displayEmpty
                value={assigneeId}
                onChange={(event) => setAssigneeId(event.target.value)}
                data-testid={`${TestIdPrefix}.Person`}
              >
                <MenuItem value="">Nobody (unassign)</MenuItem>
                {(members.data ?? []).map((person) => (
                  <MenuItem key={person.id} value={person.id}>
                    {person.displayName}
                  </MenuItem>
                ))}
              </Select>
            </Field>
          ) : (
            <Field
              label="Spread across"
              htmlFor="assign-people"
              error={fieldErrors['payload.assigneeIds']}
            >
              <Autocomplete
                multiple
                size="small"
                options={members.data ?? []}
                value={assignees}
                onChange={(_, next) => setAssignees(next)}
                getOptionLabel={(person) => person.displayName}
                isOptionEqualToValue={(a, b) => a.id === b.id}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    id="assign-people"
                    placeholder="Choose people"
                    // `params.slotProps.htmlInput`, not `params.inputProps` — MUI v7's spelling,
                    // and `params.slotProps` is spread back in so overriding one slot does not
                    // drop the ref and adornments Autocomplete puts on the others.
                    slotProps={{
                      ...params.slotProps,
                      htmlInput: {
                        ...params.slotProps.htmlInput,
                        'data-testid': `${TestIdPrefix}.People`,
                      },
                    }}
                  />
                )}
              />
              {assignees.length > 0 && (
                <Stack
                  direction="row"
                  sx={{ flexWrap: 'wrap', gap: 0.75, mt: 1 }}
                  data-testid={`${TestIdPrefix}.Order`}
                >
                  {assignees.map((person, index) => (
                    <Chip
                      key={person.id}
                      size="small"
                      label={`${index + 1}. ${person.displayName}`}
                      variant="outlined"
                    />
                  ))}
                </Stack>
              )}
              <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', mt: 0.5 }}>
                Rows are handed out in this order, wrapping round — so the people at the top take
                the remainder.
              </Typography>
            </Field>
          )}

          {fieldErrors.executionIds && (
            <Typography variant="caption" role="alert" sx={{ color: 'error.main' }}>
              {fieldErrors.executionIds}
            </Typography>
          )}

          {rootError && (
            <Alert severity="error" data-testid={`${TestIdPrefix}.Error`}>
              {rootError}
            </Alert>
          )}

          {preview && (
            <AssignPreview result={preview} order={assignees} />
          )}
        </Stack>
      </DialogContent>

      <DialogActions>
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
          variant="contained"
          onClick={() => void run(false)}
          // Commit is gated on having seen a preview — the point of preview-then-commit is lost if
          // the person can skip straight past it.
          disabled={!preview || preview.changed.length === 0 || bulk.isPending}
          title={!preview ? 'Preview the changes first' : undefined}
          data-testid={`${TestIdPrefix}.Confirm`}
        >
          {preview ? `Assign ${formatCount(preview.changed.length)} rows` : 'Assign'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/** One labelled control with its server-side error underneath — `BulkActionDialog`'s `Field`. */
function Field({
  label,
  htmlFor,
  error,
  children,
}: {
  label: string
  htmlFor: string
  error?: string
  children: React.ReactNode
}) {
  return (
    <Box>
      <Typography variant="caption" component="label" htmlFor={htmlFor} sx={{ display: 'block', mb: 0.5 }}>
        {label}
      </Typography>
      {children}
      {error && (
        <Typography variant="caption" role="alert" sx={{ color: 'error.main', mt: 0.5, display: 'block' }}>
          {error}
        </Typography>
      )}
    </Box>
  )
}

/**
 * Two readings of one result: who gets how many, and which case goes where.
 *
 * The per-row list is the answer a list of counts cannot give, and it is served in the run's own
 * order so it reads like the checklist behind the dialog.
 */
function AssignPreview({ result, order }: { result: ExecutionBulkResult; order: UserRef[] }) {
  const tally = tallyByAssignee(result.changed, order)

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
            <strong>{formatCount(result.changed.length)}</strong> will move ·{' '}
            <strong>{formatCount(result.unchanged.length)}</strong> already assigned to that person ·{' '}
            <strong>{formatCount(result.skipped.length)}</strong> will be skipped
          </Typography>

          {tally.length > 0 && (
            <Box data-testid={`${TestIdPrefix}.Preview.Tally`}>
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                The split
              </Typography>
              <Stack component="ul" sx={{ m: 0, mt: 0.5, pl: 2, gap: 0.25 }}>
                {tally.map((share) => (
                  <Box component="li" key={share.assigneeId} data-testid={`${TestIdPrefix}.Preview.Share`}>
                    <Typography variant="caption">
                      {share.assigneeName ?? 'Unknown'} — {formatCount(share.count)}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </Box>
          )}

          {result.changed.length > 0 && (
            <Box data-testid={`${TestIdPrefix}.Preview.Rows`}>
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                Moving
              </Typography>
              <Stack component="ul" sx={{ m: 0, mt: 0.5, pl: 2, gap: 0.25 }}>
                {result.changed.slice(0, 20).map((change) => (
                  <Box component="li" key={change.executionId}>
                    <Typography variant="caption">
                      <Identifier variant="muted">{change.caseKey}</Identifier> →{' '}
                      {change.assigneeName ?? 'nobody'}
                    </Typography>
                  </Box>
                ))}
                {result.changed.length > 20 && (
                  <Typography variant="caption">+{result.changed.length - 20} more</Typography>
                )}
              </Stack>
            </Box>
          )}

          {/* Named with their reason — "2 skipped" alone leaves the reader with no way to find
              out which two, or what to do about them. */}
          {result.skipped.length > 0 && (
            <Box>
              <Typography variant="caption" sx={{ fontWeight: 600 }}>
                Skipped
              </Typography>
              <Stack component="ul" sx={{ m: 0, mt: 0.5, pl: 2, gap: 0.25 }}>
                {result.skipped.map((item) => (
                  <Box component="li" key={item.executionId}>
                    <Typography variant="caption">
                      <Identifier variant="muted">{item.executionId}</Identifier> — {item.reason}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </Box>
          )}
        </Stack>
      </Alert>
    </Box>
  )
}
