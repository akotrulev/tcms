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
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { Artifact, DocPageSummary, TestPlanSummary } from '../../api/types';
import { ApiError } from '../../api/client';
import { useCreateArtifact } from '../../api/queries';
import { describeError } from '../../lib/describeError';

/**
 * Registering an artifact.
 *
 * One dialog, and the whole of its difficulty is that **two fields decide what the other fields
 * mean**. `scope` decides whether a plan is required or forbidden; `kind` decides whether the
 * target is a page or a URL. Both are enforced server-side and both are mirrored here, so the
 * common mistakes are caught without a round trip while the server stays the authority.
 *
 * There is deliberately no upload control. `FILE` needs the storage port (open question #28,
 * resolved as a filesystem adapter and not built), so the contract's `ArtifactInput.kind` is a
 * narrower enum than `Artifact.kind` and an upload is not expressible here at all — rather than
 * being a control that renders and then fails.
 */

const MAX_TITLE = 200;
const MAX_CATEGORY = 60;
const MAX_DESCRIPTION = 2000;

/**
 * Plan §6n's list.
 *
 * The section calls categories "project-configurable" and nothing configures them yet, so these
 * are offered as the defaults rather than enforced — the server takes free text. A select rather
 * than a text field because the register **groups by this**, and two people typing "Environment"
 * and "environments" produce two groups that should be one.
 */
const CATEGORIES = [
  'Strategy',
  'TestData',
  'Environment',
  'SignOff',
  'Compliance',
  'Tooling',
  'Template',
  'Other',
] as const;

const ArtifactSchema = z
  .object({
    scope: z.enum(['PROJECT', 'PLAN']),
    planId: z.string(),
    category: z.string().min(1, 'Choose a category.').max(MAX_CATEGORY),
    kind: z.enum(['DOC_PAGE_REF', 'EXTERNAL_URL']),
    title: z
      .string()
      .min(1, 'Give the artifact a title.')
      .max(MAX_TITLE, `Keep the title to ${MAX_TITLE} characters.`),
    description: z.string().max(MAX_DESCRIPTION, `Keep it to ${MAX_DESCRIPTION} characters.`),
    docPageId: z.string(),
    url: z.string(),
  })
  /*
    The cross-field rules, mirroring the server's. `superRefine` rather than a discriminated union
    on two independent discriminants: `scope` and `kind` vary independently, so a union would have
    four members enumerating a matrix that is really two separate rules.
  */
  .superRefine((values, ctx) => {
    if (values.scope === 'PLAN' && !values.planId) {
      ctx.addIssue({
        code: 'custom',
        path: ['planId'],
        message: 'Choose the plan this artifact belongs to.',
      });
    }
    if (values.kind === 'DOC_PAGE_REF' && !values.docPageId) {
      ctx.addIssue({
        code: 'custom',
        path: ['docPageId'],
        message: 'Choose the page this artifact points to.',
      });
    }
    if (values.kind === 'EXTERNAL_URL') {
      if (!values.url.trim()) {
        ctx.addIssue({ code: 'custom', path: ['url'], message: 'Give the link.' });
      } else if (!/^https?:\/\//i.test(values.url.trim())) {
        // Mirrors the server's allow-list. The register renders this inside an anchor, so a
        // `javascript:` value would be stored XSS in the product's own markup — the server refuses
        // it regardless; this is only so the user finds out before submitting.
        ctx.addIssue({
          code: 'custom',
          path: ['url'],
          message: 'Links must start with http:// or https://.',
        });
      }
    }
  });

type ArtifactFormInput = z.infer<typeof ArtifactSchema>;

type FieldName = 'scope' | 'planId' | 'category' | 'kind' | 'title' | 'description' | 'docPageId'
  | 'url' | 'root';

const FIELDS: readonly FieldName[] = [
  'scope', 'planId', 'category', 'kind', 'title', 'description', 'docPageId', 'url',
];

const TestIdPrefix = 'Artifacts.Dialog';

/**
 * Maps a rejected write onto the form.
 *
 * The server answers RFC-7807 with an `errors` map keyed by the input schema's field names, so
 * every message lands on the control it is about.
 *
 * **The 403 is the interesting one and it is deliberately treated like a 400.** A Test Engineer
 * holds `artifact:edit` — just not at project scope — so `PermissionGate` cannot disable the
 * button, and §31's disable-don't-hide rule is structurally unavailable here: the SPA's permission
 * model is keys-only and has no qualifier to read. The server therefore answers that one refusal
 * with a *body* naming `scope`, which puts the sentence on the scope selector the user is one
 * choice away from getting right, instead of a bare "forbidden" on a form that is otherwise valid.
 */
function applyServerError(
  error: unknown,
  setError: (field: FieldName, message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((FIELDS as readonly string[]).includes(field)) {
        setError(field as FieldName, messages.join(' '));
        landed = true;
      }
    }
    // A key this form has no control for must not vanish — a rejected save showing nothing at all
    // is worse than a vague message.
    if (landed) return;
  }
  setError('root', describeError(error).body);
}

export function RegisterArtifactDialog({
  open,
  onClose,
  projectId,
  plans,
  pages,
  defaultPlanId,
  onRegistered,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  /** For the plan picker. Names, never ids — `ui-rules`' names-not-ids rule. */
  plans: TestPlanSummary[];
  /** For the documentation picker, which is the kind plan §6n steers people toward. */
  pages: DocPageSummary[];
  /**
   * The plan the register is currently filtered to, if any.
   *
   * Pre-selecting it — and pre-selecting `PLAN` scope with it — is the difference between a form
   * that continues what the user was doing and one that makes them re-state it. Somebody viewing
   * Release 1's register and pressing Register means Release 1.
   */
  defaultPlanId?: string;
  onRegistered: (artifact: Artifact) => void;
}) {
  const create = useCreateArtifact(projectId);

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ArtifactFormInput>({
    resolver: zodResolver(ArtifactSchema),
    defaultValues: {
      scope: defaultPlanId ? 'PLAN' : 'PROJECT',
      planId: defaultPlanId ?? '',
      category: 'Strategy',
      kind: 'EXTERNAL_URL',
      title: '',
      description: '',
      docPageId: '',
      url: '',
    },
    mode: 'onBlur',
  });

  const scope = watch('scope');
  const kind = watch('kind');

  /*
    Focus only — no reset. `DocPageDialogs` and `SuiteDialogs` both record why: a `reset(defaults)`
    keyed on an object literal re-runs every render and wipes a failed submit's field errors before
    anyone reads them. The dialog is conditionally rendered, so it mounts fresh.
  */
  useEffect(() => {
    if (open) setFocus('title');
  }, [open, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      const artifact = await create.mutateAsync({
        scope: values.scope,
        // The server refuses a plan id at PROJECT scope rather than ignoring it, so the form must
        // not send one — this is the client half of that agreement, not a defensive trim.
        planId: values.scope === 'PLAN' ? values.planId : null,
        category: values.category,
        kind: values.kind,
        title: values.title,
        description: values.description.trim() || null,
        docPageId: values.kind === 'DOC_PAGE_REF' ? values.docPageId : null,
        url: values.kind === 'EXTERNAL_URL' ? values.url.trim() : null,
      });
      onRegistered(artifact);
      onClose();
    } catch (error) {
      applyServerError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={submit} noValidate>
        <DialogTitle data-testid={`${TestIdPrefix}.Title`}>Register an artifact</DialogTitle>
        <DialogContent>
          <Stack sx={{ gap: 2, pt: 1 }}>
            {errors.root && (
              <Alert severity="error" data-testid={`${TestIdPrefix}.Error`}>
                {errors.root.message}
              </Alert>
            )}

            <TextField
              select
              label="Belongs to"
              {...register('scope')}
              value={scope}
              error={!!errors.scope}
              helperText={
                errors.scope?.message ??
                'A project artifact is inherited by every plan. A plan artifact belongs to that cycle only.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Scope` } }}
            >
              <MenuItem value="PROJECT">The project — every plan inherits it</MenuItem>
              <MenuItem value="PLAN">One plan</MenuItem>
            </TextField>

            {scope === 'PLAN' && (
              <TextField
                select
                label="Plan"
                {...register('planId')}
                defaultValue={defaultPlanId ?? ''}
                error={!!errors.planId}
                helperText={errors.planId?.message}
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Plan` } }}
              >
                {plans.map((plan) => (
                  <MenuItem key={plan.id} value={plan.id}>
                    {plan.key} — {plan.name}
                  </MenuItem>
                ))}
              </TextField>
            )}

            <TextField
              select
              label="Category"
              {...register('category')}
              defaultValue="Strategy"
              error={!!errors.category}
              helperText={errors.category?.message ?? 'The register groups by this.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Category` } }}
            >
              {CATEGORIES.map((category) => (
                <MenuItem key={category} value={category}>
                  {category}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              label="Title"
              {...register('title')}
              error={!!errors.title}
              helperText={errors.title?.message}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.TitleField` } }}
            />

            <TextField
              select
              label="What is it?"
              {...register('kind')}
              value={kind}
              error={!!errors.kind}
              helperText={errors.kind?.message}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Kind` } }}
            >
              {/*
                The documentation option is listed first deliberately. Plan §6n calls a register of
                uploaded copies "a graveyard of outdated copies" and names the authored-page route
                as the one to steer people toward; ordering is the cheapest way to steer.
              */}
              <MenuItem value="DOC_PAGE_REF">A page in this project's documentation</MenuItem>
              <MenuItem value="EXTERNAL_URL">A link to somewhere else</MenuItem>
            </TextField>

            {kind === 'DOC_PAGE_REF' && (
              <TextField
                select
                label="Page"
                {...register('docPageId')}
                defaultValue=""
                error={!!errors.docPageId}
                helperText={
                  errors.docPageId?.message ??
                  (pages.length === 0
                    ? 'This project has no documentation pages yet.'
                    : undefined)
                }
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Page` } }}
              >
                {pages.map((page) => (
                  <MenuItem key={page.id} value={page.id}>
                    {page.key} — {page.title}
                  </MenuItem>
                ))}
              </TextField>
            )}

            {kind === 'EXTERNAL_URL' && (
              <TextField
                label="Link"
                placeholder="https://"
                {...register('url')}
                error={!!errors.url}
                helperText={errors.url?.message}
                slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Url` } }}
              />
            )}

            <TextField
              label="Description"
              multiline
              minRows={2}
              {...register('description')}
              error={!!errors.description}
              helperText={errors.description?.message ?? 'Optional.'}
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Description` } }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${TestIdPrefix}.Cancel`}>
            Cancel
          </Button>
          {/*
            Disabled while in flight — the double-submit guard `ui-rules` requires. There is no
            idempotency key here: the contract declares none for this endpoint, and inventing one
            client-side would be a header no server reads.
          */}
          <Button
            type="submit"
            variant="contained"
            disabled={isSubmitting}
            data-testid={`${TestIdPrefix}.Submit`}
          >
            Register
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
