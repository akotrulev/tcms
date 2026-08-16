import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormHelperText,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import type { Artifact, DocPageSummary, TestPlanSummary } from '../../api/types';
import { ApiError } from '../../api/client';
import {
  useArtifacts,
  useCreateArtifact,
  useDeleteArtifact,
  useRegisterArtifactFile,
  useUpdateArtifact,
} from '../../api/queries';
import { useCan } from '../../auth/useCan';
import { MAX_UPLOAD_BYTES, uploadRefusal } from '../../lib/attachments';
import { describeError } from '../../lib/describeError';
import { formatBytes } from '../../lib/format';

/**
 * Registering an artifact.
 *
 * One dialog, and the whole of its difficulty is that **two fields decide what the other fields
 * mean**. `scope` decides whether a plan is required or forbidden; `kind` decides whether the
 * target is a page or a URL. Both are enforced server-side and both are mirrored here, so the
 * common mistakes are caught without a round trip while the server stays the authority.
 *
 * ~~There is deliberately no upload control. `FILE` needs the storage port (open question #28,
 * resolved as a filesystem adapter and not built)…~~ → **superseded 2026-08-07 (#49 phase 3).**
 * `ArtifactKind.FILE` has a writer, and it is a **different endpoint**:
 * `POST /projects/{projectId}/artifacts/files`, one multipart request carrying an
 * `ArtifactFileInput` and the bytes together.
 *
 * ## One dialog offering three kinds, over two endpoints
 *
 * The obvious alternative is a second **Upload** button beside Register with its own dialog, and it
 * is worse in the way `ui-rules` §8 keeps naming: scope, the plan requirement, the category list,
 * the title limit, the description limit, the `403`-as-a-field-error mapping and the plan-scope
 * qualifier are all shared, so a second form would be a second implementation of every one of them,
 * agreeing until the first change. It would also make the user choose an endpoint before choosing a
 * kind — "Register" and "Upload" are the same act from where they are standing, and only the wire
 * knows otherwise.
 *
 * What the split does cost is one honest asymmetry, kept visible rather than smoothed over: the
 * form's `kind` is **wider than `ArtifactInput.kind`**, so `FILE` is a value the JSON create can
 * never receive. The submit branches on it before it builds a payload, and the two payloads are
 * built from two different contract types — `ArtifactInput` and `ArtifactFileInput` — so a field
 * that exists on only one of them is a compile error rather than a silently-dropped property.
 *
 * `GENERATED_REPORT` is offered by neither: nothing in `reporting` writes one, and a kind with no
 * writer in a picker is `ui-rules`' control-the-write-path-ignores.
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
    /*
      Wider than `ArtifactInput.kind` on purpose — see the module header. `FILE` is a form value
      that routes to a different endpoint, never a value sent to the JSON create (which answers a
      `kind: FILE` with a 400 naming the upload path).
    */
    kind: z.enum(['DOC_PAGE_REF', 'EXTERNAL_URL', 'FILE', 'GENERATED_REPORT']),
    /*
      A sign-off record (C7). No Zod rule: every value is legal, and what it costs is stated in the
      control's own helper text rather than refused here — the whole of its behaviour lives on the
      server, where the row is frozen against a later edit or delete.
    */
    writeOnce: z.boolean(),
    title: z
      .string()
      .min(1, 'Give the artifact a title.')
      .max(MAX_TITLE, `Keep the title to ${MAX_TITLE} characters.`),
    description: z.string().max(MAX_DESCRIPTION, `Keep it to ${MAX_DESCRIPTION} characters.`),
    docPageId: z.string(),
    url: z.string(),
    /*
      The artifact this one replaces, or '' for an ordinary registration.

      No Zod rule of its own: every constraint on it is about ANOTHER row (its scope, its plan, its
      status), and the picker only offers rows that satisfy all three. What cannot be offered
      cannot be chosen, so a client-side refusal here would be a message for a state the control
      does not produce — and the server refuses it regardless.
    */
    supersedes: z.string(),
    /*
      The chosen file's NAME, not the file.

      A `File` is an opaque handle from a native input and is not form state a resolver can
      usefully validate; the handle itself lives in component state. Keeping its name here is what
      gives the picker a real field — so the "choose one" rule is a Zod issue like every other rule
      in this form, and so the server's own `errors.file` (a zero-byte part is a 400 naming exactly
      that key) lands on the control instead of falling through to the form-level banner.
    */
    file: z.string(),
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
    if (values.kind === 'FILE' && !values.file) {
      ctx.addIssue({ code: 'custom', path: ['file'], message: 'Choose a file to register.' });
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
  | 'url' | 'file' | 'supersedes' | 'writeOnce' | 'root';

/*
  `file` is on this list and NOT on the Zod schema, and both halves are deliberate.

  A `File` is not form state react-hook-form can usefully hold — it is an opaque handle picked from
  a native input, and registering it would mean either storing it in the resolver's value or
  carrying a `FileList` through Zod for one field. It lives in component state instead. But the
  SERVER answers a zero-byte part with `errors.file` (`ArtifactFileController.register`), so the key
  has to be here or that refusal falls through to the form-level banner and the message about the
  file is rendered nowhere near the file picker.
*/
const FIELDS: readonly FieldName[] = [
  'scope', 'planId', 'category', 'kind', 'title', 'description', 'docPageId', 'url', 'file',
  'supersedes', 'writeOnce',
];

/*
  `reportJobId` is deliberately NOT on that list, and it is the mirror of `file`'s reason rather
  than an omission of the same kind.

  The server can answer `errors.reportJobId` — a job that rendered nothing, or one from another
  project — but this form has no control for it: the job is fixed by the button that opened the
  dialog (see `pinnedReport`). So the refusal falls through to the form-level banner, which is
  where a message about something the user cannot change here belongs. Listing the key would land
  it on a field that is not on screen, which is the register's own "rejected save showing nothing
  at all" failure with an extra step.
*/

const TestIdPrefix = 'Artifacts.Dialog';

/**
 * Maps a rejected write onto the form.
 *
 * The server answers RFC-7807 with an `errors` map keyed by the input schema's field names, so
 * every message lands on the control it is about.
 *
 * **The 403 is the interesting one and it is deliberately treated like a 400.** ~~A Test Engineer
 * holds `artifact:edit` — just not at project scope — so `PermissionGate` cannot disable the button
 * on the strength of the key alone.~~ → **`V52` (§210): the key is unqualified and nothing carries
 * `PLAN_SCOPE` any more**, so the button *can* be decided from the key. §61 shipped the handler as
 * the *only* protection and recorded why:
 * the permission model was keys-only, so §31's disable-don't-hide was structurally unavailable and
 * the server answered that one refusal with a *body* naming `scope`.
 *
 * **§65 put the qualifier on the wire, so the scope choice is now disabled before the submit** —
 * see the `Belongs to` select below. This handler stays exactly as it was, and that is the point:
 * the client is an affordance layer, the gate is the server, and a form that can no longer produce
 * the refusal must still render it correctly if it arrives. It still can — a grant revoked between
 * opening the dialog and pressing Register lands here.
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
  pinnedReport,
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
  /**
   * A finished report to pin, when this dialog is opened from the report itself (C6).
   *
   * **Reuse rather than a second dialog, which is this file's own rule stated one feature over.**
   * The header above refuses a separate Upload dialog because scope, the plan requirement, the
   * category list, both length limits and the `403`-as-a-field-error mapping are shared, and a
   * second form would be a second implementation of every one of them. Pinning a report shares
   * exactly the same set — it differs only in *what the payload is*, which is the one thing the
   * caller already knows and the user therefore never chooses.
   *
   * When it is set the kind is fixed, the kind picker and the three payload controls are not
   * rendered, and the title starts as the report's own.
   */
  pinnedReport?: { jobId: string; defaultTitle: string };
  onRegistered: (artifact: Artifact) => void;
}) {
  const create = useCreateArtifact(projectId);
  const upload = useRegisterArtifactFile(projectId);
  const gate = useCan(projectId);

  /*
    The picked file, beside the form rather than in it. `fileInput` is the hidden native control;
    `picked` is what the submit sends and what the Zod field mirrors by name.
  */
  const fileInput = useRef<HTMLInputElement>(null);
  const [picked, setPicked] = useState<File | null>(null);

  /**
   * May this caller register at PROJECT scope?
   *
   * ~~A Test Engineer's `artifact:edit` is `PLAN_SCOPE` (roles spec's Artifacts table, V19): they
   * curate their own cycle and may not rewrite the document every cycle inherits read-only.~~ →
   * **`V52` (§210) de-qualified the grant**, so today this answers true for every writer — and it
   * is what narrows the form again the day a migration qualifies the key. Asked
   * of the gate rather than decided here — what the qualifier means belongs on one side of the wire.
   */
  const canProjectScope = gate.canOnScope('artifact:edit', false);
  const projectScopeReason = gate.reasonOnScope('artifact:edit', false);

  const {
    register,
    handleSubmit,
    setError,
    setFocus,
    setValue,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ArtifactFormInput>({
    resolver: zodResolver(ArtifactSchema),
    defaultValues: {
      scope: defaultPlanId ? 'PLAN' : 'PROJECT',
      planId: defaultPlanId ?? '',
      category: 'Strategy',
      kind: pinnedReport ? 'GENERATED_REPORT' : 'EXTERNAL_URL',
      // The report's own title, which is the one the exported document carries and the one the
      // reader was just looking at — an empty field here would make somebody re-type what is on
      // the screen behind the dialog.
      title: pinnedReport?.defaultTitle ?? '',
      description: '',
      docPageId: '',
      url: '',
      file: '',
      supersedes: '',
      writeOnce: false,
    },
    mode: 'onBlur',
  });

  const scope = watch('scope');
  const kind = watch('kind');
  const planId = watch('planId');

  /*
    The rows this registration could replace, read at the scope the FORM is on rather than the one
    the register is filtered to — the two differ the moment somebody viewing a plan registers at
    project scope.

    `inherited` rows are excluded and that is the same-scope rule, not tidiness: inside a plan query
    those are the project's documents, and a plan-scope artifact may not replace one. The server
    refuses it with `errors.supersedes`; offering it here would be an affordance for a 400.
  */
  const candidates = useArtifacts(
    projectId,
    scope,
    scope === 'PLAN' ? planId || undefined : undefined,
  );
  const replaceable = (candidates.data ?? []).filter(
    (artifact) => artifact.status === 'ACTIVE' && !artifact.inherited,
  );

  /*
    A selection made under one scope cannot survive a change of scope — it names a row that is now
    in the wrong place, and the server would answer 400. Cleared rather than left for the submit to
    discover.
  */
  useEffect(() => {
    setValue('supersedes', '');
  }, [scope, planId, setValue]);

  /*
    Focus only — no reset. `DocPageDialogs` and `SuiteDialogs` both record why: a `reset(defaults)`
    keyed on an object literal re-runs every render and wipes a failed submit's field errors before
    anyone reads them. The dialog is conditionally rendered, so it mounts fresh.
  */
  useEffect(() => {
    if (open) setFocus('title');
  }, [open, setFocus]);

  /*
    Move off a scope this caller cannot use.

    The default is computed at mount, when the permission query may still be in flight — and the
    gate is fail-closed while pending, so a Test Lead would otherwise be pinned to PLAN by a
    verdict that had not arrived yet. Guarded on the current value, so it corrects the disabled
    selection once and never fights a choice the user made: PLAN is always available to anybody
    holding artifact:edit at all, so there is no mirror case to handle.
  */
  useEffect(() => {
    if (open && scope === 'PROJECT' && !gate.pending && !canProjectScope) {
      setValue('scope', 'PLAN');
    }
  }, [open, scope, gate.pending, canProjectScope, setValue]);

  const submit = handleSubmit(async (values) => {
    try {
      /*
        Two endpoints, two contract types, one set of shared fields.

        The branch is on `kind` and it happens BEFORE either payload is built, so neither payload
        can carry a field the other's schema declares — `ArtifactFileInput` has no `kind`, no `url`
        and no `docPageId`, and TypeScript refuses a payload that tries. That is the contract's own
        narrowing (its description: "a client generating types from this contract cannot express
        the contradiction at all") doing work on this side of the wire.
      */
      const artifact =
        values.kind === 'FILE'
          ? await upload.mutateAsync({
              artifact: {
                scope: values.scope,
                planId: values.scope === 'PLAN' ? values.planId : null,
                category: values.category,
                title: values.title,
                description: values.description.trim() || null,
                writeOnce: values.writeOnce,
                supersedes: values.supersedes || null,
              },
              // Non-null by the schema's own rule: `kind === 'FILE'` with no `file` is a Zod issue
              // and `handleSubmit` never reaches here. The assertion is the narrowing Zod cannot
              // express, because the field it validates is the NAME and this is the handle.
              file: picked as File,
            })
          : await create.mutateAsync({
              scope: values.scope,
              // The server refuses a plan id at PROJECT scope rather than ignoring it, so the form
              // must not send one — this is the client half of that agreement, not a defensive trim.
              planId: values.scope === 'PLAN' ? values.planId : null,
              category: values.category,
              kind: values.kind,
              title: values.title,
              description: values.description.trim() || null,
              docPageId: values.kind === 'DOC_PAGE_REF' ? values.docPageId : null,
              url: values.kind === 'EXTERNAL_URL' ? values.url.trim() : null,
              // The server refuses a job id on any other kind rather than ignoring it — the same
              // agreement `planId` above is half of, over a fourth field.
              reportJobId:
                values.kind === 'GENERATED_REPORT' ? (pinnedReport?.jobId ?? null) : null,
              writeOnce: values.writeOnce,
              supersedes: values.supersedes || null,
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
        <DialogTitle data-testid={`${TestIdPrefix}.Title`}>
          {pinnedReport ? 'Save this report to the register' : 'Register an artifact'}
        </DialogTitle>
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
                projectScopeReason ??
                'A project artifact is inherited by every plan. A plan artifact belongs to that cycle only.'
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Scope` } }}
            >
              {/*
                Disabled on the item, NOT wrapped in a PermissionGate. §62 cost a whole live pass to
                learn this: `Select` clones its DIRECT children to inject `role="option"` and
                `data-value`, so a wrapped item renders as an inert `role="menuitem"` that is
                impossible to choose AND carries no `aria-disabled` — indistinguishable, to a test,
                from an item that is merely not disabled.
              */}
              <MenuItem value="PROJECT" disabled={!canProjectScope}>
                The project — every plan inherits it
              </MenuItem>
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

            {/*
              Not rendered when a report is being pinned: the kind is the one thing the caller
              already decided by pressing Save on a report, and a picker whose other options would
              all invalidate the payload is a control that can only be used wrongly (`ui-rules`
              §4). The fixed value still travels — it is the form's `kind`, not a literal at the
              submit — so there is one place the payload is decided either way.
            */}
            {!pinnedReport && (
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
              {/*
                Last, and that ordering is the same steering as the first item being first. Plan
                §6n calls a register of uploaded copies "a graveyard of outdated copies"; the upload
                exists because some documents genuinely are files (a signed PDF, a data export) and
                not because it is the recommended shape.
              */}
              <MenuItem value="FILE">A file to upload and keep here</MenuItem>
            </TextField>
            )}

            {pinnedReport && (
              <Alert severity="info" data-testid={`${TestIdPrefix}.PinnedReport`}>
                The register keeps the report exactly as it was generated. Regenerating it later
                makes a new report and leaves this entry untouched.
              </Alert>
            )}

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

            {kind === 'FILE' && (
              <Box>
                <input
                  ref={fileInput}
                  type="file"
                  hidden
                  data-testid={`${TestIdPrefix}.FileInput`}
                  onChange={(event) => {
                    const chosen = event.target.files?.[0];
                    // Cleared so re-picking the SAME file fires `change` again — §67's finding,
                    // and the first thing anyone does after a refused upload.
                    event.target.value = '';
                    if (!chosen) return;
                    const refusal = uploadRefusal(chosen);
                    if (refusal) {
                      // Client-side copy, not security: the servlet container answers a file over
                      // the limit with a 413 refused before any handler runs, so there is no
                      // problem document and nothing to land on a field. This exists so the user
                      // finds out before pushing 10MB over a slow link.
                      setPicked(null);
                      setValue('file', '', { shouldValidate: false });
                      setError('file', { type: 'client', message: refusal });
                      return;
                    }
                    setPicked(chosen);
                    // The name is the form's value; validating it here clears the "choose a file"
                    // issue in the same tick the file lands, rather than at the next submit.
                    setValue('file', chosen.name, { shouldValidate: true });
                  }}
                />
                <Button
                  variant="outlined"
                  startIcon={<UploadFileIcon />}
                  onClick={() => fileInput.current?.click()}
                  data-testid={`${TestIdPrefix}.ChooseFile`}
                >
                  {picked ? 'Choose a different file' : 'Choose a file'}
                </Button>
                <FormHelperText error={!!errors.file} data-testid={`${TestIdPrefix}.FileHelp`}>
                  {errors.file?.message ??
                    (picked
                      ? `${picked.name} · ${formatBytes(picked.size)}`
                      : `Up to ${formatBytes(MAX_UPLOAD_BYTES)}. The bytes are fixed at registration — replacing the document means registering a new artifact that supersedes this one.`)}
                </FormHelperText>
              </Box>
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

            {/*
              The sign-off toggle (C7) — `artifact.write_once`'s first writer.

              A checkbox rather than a category, and the two are genuinely different things: the
              `SignOff` category says what the document is ABOUT and groups the register by it,
              while this decides whether the entry can ever be changed. Coupling them would freeze
              every row somebody filed under SignOff, including the draft they are still writing.

              **The helper text states the cost before the choice, because there is no way back.**
              The flag is decided at registration and the column is not updatable, so a control
              that explained itself only afterwards would be explaining an act nobody can undo.
            */}
            <FormControlLabel
              control={
                <Checkbox
                  {...register('writeOnce')}
                  data-testid={`${TestIdPrefix}.WriteOnce`}
                />
              }
              label="This is a sign-off record"
            />
            <FormHelperText sx={{ mt: -1.5 }}>
              Sign-off records are kept as they were signed: they cannot be edited or removed, and
              a correction is registered as a replacement. This cannot be changed later.
            </FormHelperText>

            {/*
              The affordance for the sentence three refusals already give.

              The delete's 409 says "Supersede it with a correction instead", the upload helper
              text says replacing a document means registering one that supersedes it, and until
              §209 neither named anything the product could do — `ui-rules` §4's mandated-follow-up
              rule, unmet in the one module where plan §6n makes the trail the point.

              Titles, never ids: the value is a uuid and what the reader picks from is the name of
              the document they are replacing.
            */}
            <TextField
              select
              label="Replaces"
              {...register('supersedes')}
              value={watch('supersedes')}
              error={!!errors.supersedes}
              helperText={
                errors.supersedes?.message ??
                (replaceable.length === 0
                  ? 'Nothing here to replace yet. The old artifact stays in the register, marked superseded.'
                  : 'Optional. The artifact you choose is kept and marked superseded, not deleted.')
              }
              slotProps={{ htmlInput: { 'data-testid': `${TestIdPrefix}.Supersedes` } }}
            >
              <MenuItem value="">Nothing — this is a new artifact</MenuItem>
              {replaceable.map((artifact) => (
                <MenuItem key={artifact.id} value={artifact.id}>
                  {artifact.category} — {artifact.title}
                </MenuItem>
              ))}
            </TextField>
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

/* ------------------------------------------------------------------ A6 --- */

const EditSchema = z.object({
  title: z
    .string()
    .min(1, 'Give the artifact a title.')
    .max(MAX_TITLE, `Keep the title to ${MAX_TITLE} characters.`),
  category: z.string().min(1, 'Choose a category.').max(MAX_CATEGORY),
  description: z.string().max(MAX_DESCRIPTION, `Keep it to ${MAX_DESCRIPTION} characters.`),
});

type EditFormInput = z.infer<typeof EditSchema>;

type EditFieldName = 'title' | 'category' | 'description' | 'root';

const EDIT_FIELDS: readonly EditFieldName[] = ['title', 'category', 'description'];

const EditTestId = 'Artifacts.Edit';

/**
 * Correcting an artifact — **three fields, and the absences are the design.**
 *
 * `scope`, `planId`, `kind` and every payload column are `updatable = false` on the row, so this
 * dialog offers none of them. That is not a narrower form than the registration for convenience: a
 * control here for a value the server cannot accept is `ui-rules`' write-path-ignores defect, and
 * the honest answer to *"this points at the wrong thing"* is Register a replacement, which the
 * register already has as **supersede**.
 *
 * **Prefilled, unlike `RunCloneDialog`'s release**, and the difference is what an absent field
 * means on each wire: there, absent keeps the source's value, so a prefilled box a user empties
 * would be silently ignored; here the body states the metadata whole (§68), so what is on screen
 * is what will be saved and prefilling is the only honest starting point.
 *
 * The category picker is `CATEGORIES` — the same list the registration offers, for the same reason
 * (the register **groups** by this, and "Environment" beside "environments" is two groups that
 * should be one). A row whose category came from somewhere else keeps it as an extra option rather
 * than being silently retyped.
 */
export function EditArtifactDialog({
  open,
  onClose,
  projectId,
  artifact,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  artifact: Artifact;
}) {
  const update = useUpdateArtifact(projectId);

  const {
    register,
    handleSubmit,
    reset,
    setError,
    setFocus,
    formState: { errors, isSubmitting },
  } = useForm<EditFormInput>({
    resolver: zodResolver(EditSchema),
    defaultValues: {
      title: artifact.title,
      category: artifact.category,
      description: artifact.description ?? '',
    },
    mode: 'onBlur',
  });

  /*
    Re-seeded whenever the dialog opens on a row. One dialog instance serves every row of the
    table, so without this the second artifact somebody edits opens showing the first one's title —
    the defect that looks like a caching bug and is a defaultValues one.
  */
  useEffect(() => {
    if (!open) return;
    reset({
      title: artifact.title,
      category: artifact.category,
      description: artifact.description ?? '',
    });
    setFocus('title');
  }, [open, artifact, reset, setFocus]);

  const submit = handleSubmit(async (values) => {
    try {
      await update.mutateAsync({
        artifactId: artifact.id,
        input: {
          title: values.title.trim(),
          category: values.category,
          // '' is the empty textarea and `null` is the wire's "clear it". Sending the empty string
          // would store a description that is present and blank, which renders as an empty caption
          // line under the title.
          description: values.description.trim() || null,
        },
      });
      onClose();
    } catch (error) {
      applyEditError(error, (field, message) => setError(field, { type: 'server', message }));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={EditTestId}>
      <form onSubmit={submit} noValidate aria-busy={isSubmitting}>
        <DialogTitle>Correct this artifact</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {errors.root && (
              <Alert severity="error" role="alert" data-testid={`${EditTestId}.Error`}>
                {errors.root.message}
              </Alert>
            )}
            {/*
              What this dialog cannot change, said once rather than implied by four missing
              controls. Somebody who opened it to fix a wrong URL should leave knowing the register
              has an answer for that, not concluding the feature is broken.
            */}
            <Alert severity="info" data-testid={`${EditTestId}.Scope`}>
              Where this artifact sits and what it points at cannot be changed here — register a
              replacement instead, and the register will record that it superseded this one.
            </Alert>
            <TextField
              label="Title"
              required
              autoComplete="off"
              error={!!errors.title}
              helperText={errors.title?.message}
              slotProps={{ htmlInput: { 'data-testid': `${EditTestId}.Title`, maxLength: MAX_TITLE } }}
              {...register('title')}
            />
            <TextField
              label="Category"
              select
              defaultValue={artifact.category}
              error={!!errors.category}
              helperText={errors.category?.message ?? 'The register groups by this.'}
              slotProps={{ htmlInput: { 'data-testid': `${EditTestId}.Category` } }}
              {...register('category')}
            >
              {/*
                The row's own category first when it is not one of the defaults — plan §6n calls the
                list project-configurable and the server takes free text, so a value from an API
                caller or an older list must not be silently rewritten by opening this dialog.
              */}
              {!(CATEGORIES as readonly string[]).includes(artifact.category) && (
                <MenuItem value={artifact.category}>{artifact.category}</MenuItem>
              )}
              {CATEGORIES.map((category) => (
                <MenuItem key={category} value={category}>
                  {category}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Description"
              multiline
              minRows={2}
              error={!!errors.description}
              helperText={errors.description?.message ?? 'Optional. Emptying it removes it.'}
              slotProps={{
                htmlInput: { 'data-testid': `${EditTestId}.Description`, maxLength: MAX_DESCRIPTION },
              }}
              {...register('description')}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} data-testid={`${EditTestId}.Cancel`}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="contained"
            disabled={isSubmitting}
            data-testid={`${EditTestId}.Submit`}
          >
            Save
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/** `applyServerError`'s rule over this form's three keys — including the write-once 409. */
function applyEditError(
  error: unknown,
  setError: (field: EditFieldName, message: string) => void,
) {
  if (error instanceof ApiError && error.fieldErrors) {
    let landed = false;
    for (const [field, messages] of Object.entries(error.fieldErrors)) {
      if ((EDIT_FIELDS as readonly string[]).includes(field)) {
        setError(field as EditFieldName, messages.join(' '));
        landed = true;
      }
    }
    if (landed) return;
  }
  // The 409 lands here, and it is the one refusal with no field: a sign-off record is kept as it
  // was signed, and the way forward is superseding it. `describeError` renders the server's own
  // sentence, which names that.
  setError('root', describeError(error).body);
}

const DeleteTestId = 'Artifacts.Delete';

/**
 * Unregistering an artifact — **`DELETE` shipped in §126 with both its 409 guards and no caller.**
 *
 * ## This one confirms, and the archive beside it does not
 *
 * `ArtifactRowActions` records why archiving skips the dialog: it destroys nothing and the way back
 * is the item beneath the one just pressed. Neither is true here — the row is gone (there is no
 * `deleted_at` on `artifact`), and **for a `FILE` the bytes go with it**, which is the register's
 * only act that destroys something rather than removing a pointer to a document that goes on
 * existing elsewhere. So the dialog names the kind's consequence rather than asking a generic
 * "are you sure".
 *
 * ## The two refusals arrive here, in place
 *
 * A sign-off record and a row another artifact supersedes are both `409`s the client cannot
 * predict — `write_once` is not on the wire at all, and the superseding row may be outside the
 * page's own scope. So they are rendered beside the button that raised them rather than as a
 * corner toast, which is why `useDeleteArtifact` marks itself handled.
 */
export function DeleteArtifactDialog({
  open,
  onClose,
  projectId,
  artifact,
}: {
  open: boolean;
  onClose: () => void;
  projectId: string;
  artifact: Artifact;
}) {
  const remove = useDeleteArtifact(projectId);

  const confirm = async () => {
    try {
      await remove.mutateAsync(artifact.id);
      onClose();
    } catch {
      // Rendered below from the mutation's own error — rethrowing would reach the global surface
      // this mutation opts out of.
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={DeleteTestId}>
      <DialogTitle>Remove this artifact from the register?</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Box>
            <Box sx={{ fontWeight: 500 }}>{artifact.title}</Box>
            <FormHelperText>
              {artifact.kind === 'FILE'
                ? 'This deletes the uploaded file as well. The register keeps no copy.'
                : 'This removes the register entry. Whatever it points at is not affected.'}
            </FormHelperText>
          </Box>
          {/*
            The distinction the register is built on, stated where somebody is about to get it
            wrong: removal answers "that should never have been in here", and an artifact that has
            merely served its purpose is archived — which is the item directly above this one.
          */}
          <Alert severity="info">
            If the document is simply out of date, <strong>Archive</strong> it instead — an archived
            artifact stays readable and the register keeps the record.
          </Alert>
          {remove.isError && (
            <Alert severity="error" role="alert" data-testid={`${DeleteTestId}.Error`}>
              {describeError(remove.error).body}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} data-testid={`${DeleteTestId}.Cancel`}>
          Cancel
        </Button>
        <Button
          color="error"
          variant="contained"
          disabled={remove.isPending}
          onClick={() => void confirm()}
          data-testid={`${DeleteTestId}.Confirm`}
        >
          {remove.isPending ? 'Removing…' : 'Remove'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
