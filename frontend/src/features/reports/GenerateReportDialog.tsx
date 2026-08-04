import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useGenerateReport, useRuns } from '../../api/queries';
import type { ReportFormat, ReportJob } from '../../api/types';
import { describeError } from '../../lib/describeError';
import { reportFormatHints, reportFormatLabels, runStatusLabels } from '../../lib/labels';

/**
 * Asking for a report (§6e, the request half of §87's pipeline).
 *
 * ## What this dialog is allowed to know, and what it must not claim
 *
 * Two of its three fields are open sets on the wire and closed here, and the reasons are opposite.
 *
 * **The report type is an open string in the contract** because §6e names ten and a deployment
 * grows them slice by slice; a client generated from a stale enum could not ask for a report that
 * exists. What this dialog offers is therefore a list of the ones it can *build a parameter form
 * for* — one, today — and not a claim about what the server can compute. A server that has more is
 * not misrepresented: it is simply not asked, and the day a second report lands, this list and its
 * parameter form move together, which is the only way they can stay consistent.
 *
 * **The format is a closed enum and is offered in full**, including the one that is refused on
 * every deployment today. That looks like `ui-rules`' render-then-reject and is the contract's
 * explicit design: *"the enum is not narrowed to what is installed: what a deployment can render
 * is a property of that deployment, and the refusal names it."* A client-side list of installed
 * renderers would be a second copy of somebody else's configuration — silently wrong in both
 * directions, offering nothing where PDF has been installed and claiming to know where it has not.
 * The refusal is a **400 at request time carrying a field error**, engineered for exactly this, so
 * it lands on the format field naming what the server *can* produce. That is a better answer than
 * a picker that quietly lacks an option, and it is why the hint under PDF says what it needs
 * rather than that it is unavailable.
 *
 * ## The run picker is this report's parameters, not a general one
 *
 * `run_summary` takes a required `runId`, checked **inside the worker** — a bad one is a FAILED
 * job carrying a sentence, not a 400. So a dialog that let it be omitted would produce a job that
 * spins and then fails, for a field the form could have required. A generic parameter form for
 * whatever a future report declares is not built, and is named as not built: nothing in the
 * contract describes a report's parameters, so there is nothing to generate one from.
 */

const ReportSchema = z.object({
  type: z.string().min(1),
  runId: z.string().min(1, 'Choose the run this report covers.'),
  format: z.enum(['MD', 'HTML', 'PDF']),
});

type ReportFormInput = z.infer<typeof ReportSchema>;

const TestId = 'Reports.Generate';

/**
 * The types this dialog can ask for, each with the parameter form it needs.
 *
 * A list rather than a single constant because the second entry is what R6 adds, and a hard-coded
 * `'run_summary'` in four places is what that slice would have to find.
 */
const TYPES = [
  {
    value: 'run_summary',
    label: 'Run summary',
    hint: 'One run: status breakdown, progress per tester, and results by suite.',
  },
] as const;

/** Ordered by what people ask for, not by the contract's declaration order. */
const FORMATS: readonly ReportFormat[] = ['HTML', 'MD', 'PDF'];

type FormField = keyof ReportFormInput;

const FIELDS: readonly FormField[] = ['type', 'runId', 'format'];

/**
 * Server field errors onto the fields that carry them.
 *
 * `parameters` is the one name the server may send that has no control here — it is the object
 * this form composes, not a field somebody filled in — so its sentence goes to the banner rather
 * than nowhere. `RunCreateDialog`'s rule, one form over.
 */
function applyServerError(
  error: unknown,
  setError: (field: FormField | 'root', message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    const stray: string[] = [];
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((FIELDS as readonly string[]).includes(field)) {
        setError(field as FormField, messages.join(' '));
        landed = true;
      } else {
        stray.push(messages.join(' '));
      }
    }
    if (stray.length > 0) {
      setError('root', stray.join(' '));
      return;
    }
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

export function GenerateReportDialog({
  projectId,
  open,
  onClose,
  onQueued,
}: {
  projectId: string;
  open: boolean;
  onClose: () => void;
  /** The 202's job, handed to the screen that will watch it. */
  onQueued: (job: ReportJob) => void;
}) {
  const generate = useGenerateReport(projectId);
  const runs = useRuns(projectId);
  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    watch,
    formState: { errors },
  } = useForm<ReportFormInput>({
    resolver: zodResolver(ReportSchema),
    defaultValues: { type: TYPES[0].value, runId: '', format: 'HTML' },
    mode: 'onBlur',
  });

  useEffect(() => {
    if (open) setFocus('runId');
  }, [open, setFocus]);

  const format = watch('format');

  const submit = handleSubmit(async (values) => {
    try {
      const job = await generate.mutateAsync({
        type: values.type,
        format: values.format,
        // Frozen onto the artifact verbatim (`V1`: "a report you cannot reproduce is a report
        // nobody trusts"), so only what the report actually takes is sent — an extra key here
        // would be printed in the header's selection block as a filter the reader believes
        // narrowed the numbers.
        parameters: { runId: values.runId },
      });
      onQueued(job);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  const available = runs.data ?? [];

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <form onSubmit={submit} noValidate aria-busy={generate.isPending}>
        <DialogTitle>Generate a report</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
                {errors.root.message}
              </Alert>
            )}
            {/*
              A run is this report's whole scope, so a project with none cannot produce one — said
              before the click rather than after it, as the run dialog says it about plans.
            */}
            {runs.isSuccess && available.length === 0 && (
              <Alert severity="info" data-testid={`${TestId}.NoRuns`}>
                This project has no test runs yet. A run summary reports on one run, so there is
                nothing to report on until a run has been started.
              </Alert>
            )}
            <TextField
              label="Report"
              required
              select
              defaultValue={TYPES[0].value}
              error={!!errors.type}
              helperText={errors.type?.message ?? TYPES[0].hint}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Type` } }}
              {...register('type')}
            >
              {TYPES.map((type) => (
                <MenuItem key={type.value} value={type.value} data-testid={`${TestId}.Type.${type.value}`}>
                  {type.label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Run"
              required
              select
              defaultValue=""
              error={!!errors.runId}
              helperText={
                errors.runId?.message ??
                'Which run the numbers come from. Frozen onto the report so it can be reproduced.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Run` } }}
              {...register('runId')}
            >
              {available.map((run) => (
                <MenuItem key={run.id} value={run.id} data-testid={`${TestId}.Run.${run.key}`}>
                  <Stack direction="row" sx={{ alignItems: 'baseline', gap: 1 }}>
                    <Typography variant="body2">
                      {run.key} · {run.name}
                    </Typography>
                    <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                      {runStatusLabels[run.status]}
                    </Typography>
                  </Stack>
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Format"
              required
              select
              defaultValue="HTML"
              error={!!errors.format}
              // The hint is the selected format's own, so the picker teaches what each one is for
              // rather than leaving three acronyms to be guessed at.
              helperText={errors.format?.message ?? reportFormatHints[format]}
              slotProps={{ htmlInput: { 'data-testid': `${TestId}.Format` } }}
              {...register('format')}
            >
              {FORMATS.map((value) => (
                <MenuItem key={value} value={value} data-testid={`${TestId}.Format.${value}`}>
                  {reportFormatLabels[value]}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={generate.isPending || available.length === 0}
            data-testid={`${TestId}.Submit`}
          >
            {generate.isPending ? 'Requesting…' : 'Generate'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
