import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { useState } from 'react';
import {
  useCreateClassificationValue,
  useUpdateClassificationValue,
} from '../../api/queries';
import type { ClassificationDimension, ClassificationValue } from '../../api/types';
import { ApiError } from '../../api/client';
import { describeError } from '../../lib/describeError';

/**
 * Add a value to a dimension, or rename and recolour one that exists.
 *
 * One dialog for both, because the server takes one body for both — and the *shape* of that body
 * is the decision worth reading here. `ClassificationValueInput` carries every editable field on
 * both writes, so this form always sends `dimensionId` and `parentId` even though an edit may not
 * change either. That is not padding: the server **refuses** a differing value rather than dropping
 * it, so sending what we hold is what turns a client bug into a message on a field instead of a
 * save that silently does something else.
 *
 * **The parent is fixed at creation and cannot be edited**, which the copy says out loud rather
 * than expressing as a disabled select nobody can explain. Moving a value would rewrite the
 * subtree its descendants' paths record, and — the reason that matters more here than in the suite
 * tree — the values under it are what authored cases are *classified by*.
 */
export function ClassificationValueDialog({
  projectId,
  dimension,
  parent,
  value,
  onClose,
}: {
  projectId: string;
  dimension: ClassificationDimension;
  /** The value the new one hangs under, or null for a root. Ignored when editing. */
  parent: ClassificationValue | null;
  /** The value being edited, or null when adding. */
  value: ClassificationValue | null;
  onClose: () => void;
}) {
  const [name, setName] = useState(value?.name ?? '');
  const [color, setColor] = useState(value?.color ?? '');

  const create = useCreateClassificationValue(projectId);
  const update = useUpdateClassificationValue(projectId);
  const mutation = value ? update : create;

  /*
    Field errors from the server, not from a second client-side copy of its rules. The three that
    can land here — a blank name, a name over 150, a name another sibling already uses — are all
    decided against data this form does not have (the sibling list is per parent, and the retired
    rows are excluded), so a local re-implementation would be a second answer that disagrees on
    exactly the input nobody tests with.
  */
  const fieldErrors =
    mutation.error instanceof ApiError ? (mutation.error.fieldErrors ?? undefined) : undefined;
  const forbidden = mutation.error instanceof ApiError && mutation.error.status === 403;

  const submit = () => {
    const input = {
      dimensionId: dimension.id,
      name,
      parentId: value ? value.parentId : (parent?.id ?? null),
      color: color.trim() || null,
    };
    const done = { onSuccess: () => onClose() };
    if (value) update.mutate({ valueId: value.id, input }, done);
    else create.mutate(input, done);
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>
        {value ? `Rename ${value.name}` : `Add to ${dimension.name}`}
      </DialogTitle>
      <DialogContent>
        <Stack sx={{ gap: 2, pt: 1 }}>
          {mutation.error && !fieldErrors && (
            <Alert severity="error" data-testid="ClassificationValueDialog.Error">
              {forbidden
                ? 'You do not have permission to edit this vocabulary.'
                : describeError(mutation.error).body}
            </Alert>
          )}

          {!value && parent && (
            <Alert severity="info" variant="outlined">
              Added under <strong>{parent.name}</strong>. A value cannot be moved afterwards — the
              cases classified by everything below it depend on where it sits.
            </Alert>
          )}

          <TextField
            label="Name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            error={!!fieldErrors?.name}
            helperText={fieldErrors?.name?.join(' ')}
            autoFocus
            fullWidth
            slotProps={{ htmlInput: { 'data-testid': 'ClassificationValueDialog.Name' } }}
          />

          <TextField
            label="Colour"
            placeholder="#b7791f"
            value={color}
            onChange={(event) => setColor(event.target.value)}
            error={!!fieldErrors?.color}
            helperText={fieldErrors?.color?.join(' ') ?? 'Optional. Six hex digits after a hash.'}
            fullWidth
            slotProps={{ htmlInput: { 'data-testid': 'ClassificationValueDialog.Color' } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid="ClassificationValueDialog.Cancel">
          Cancel
        </Button>
        <Button
          variant="contained"
          onClick={submit}
          disabled={mutation.isPending}
          data-testid="ClassificationValueDialog.Submit"
        >
          {value ? 'Save' : 'Add'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
