import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import { useCreateRelease, useProject } from '../../api/queries';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input, Textarea } from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { toast } from '../../notifications/toast';

/**
 * `release-new.html` — a version, and optionally a date and a description.
 *
 * <h2>The "Not in this product" card is not decoration</h2>
 *
 * It is where a person looking for a sprint field finds out there is not one, and it is rendered
 * verbatim from the mockup for that reason. The contract makes the same argument on `ReleaseInput`:
 * *"there is no field for a sprint, a velocity or a second project: planning has a cadence because
 * releases have dates, not because anybody invented a two-week rhythm they do not work in."* A form
 * that simply omitted those fields would leave the reader hunting for them.
 *
 * <h2>A past target date is accepted, and the help text says so is optional</h2>
 *
 * The contract accepts one: *"a release that slipped is a real state, and refusing it would make it
 * unrecordable."* So there is no client-side minimum on the date, and no validation here that the
 * server does not have — the one refusal this form can produce on its own is an empty version.
 *
 * <h2>The 409 lands on the version field</h2>
 *
 * A duplicate version inside the project is the one failure a person can actually fix from here, so
 * it is rendered under the box they would fix it in rather than as a banner over the form.
 */

const TEST_ID = 'ReleaseNew';

const ReleaseSchema = z.object({
  version: z.string().trim().min(1, 'Give it a version.'),
  targetDate: z.string().optional(),
  description: z.string().optional(),
});

type ReleaseFormInput = z.infer<typeof ReleaseSchema>;

export function ReleaseNewPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const project = useProject(projectId);
  const create = useCreateRelease(projectId);

  const form = useForm<ReleaseFormInput>({
    resolver: zodResolver(ReleaseSchema),
    defaultValues: { version: '', targetDate: '', description: '' },
  });

  const submit = form.handleSubmit(async (values) => {
    try {
      const release = await create.mutateAsync({
        version: values.version.trim(),
        // Empty string is the browser's spelling of "the date input is blank", and the contract's
        // spelling is `null`. Sending `""` would be a 400 on a field the person deliberately left
        // alone.
        targetDate: values.targetDate || null,
        description: values.description || null,
      });
      toast('Release created', { variant: 'success' });
      navigate(`/projects/${projectId}/releases/${release.id}`);
    } catch (error) {
      /*
        The 409 is a version that already exists in this project (D13, case-insensitively), and it
        is the whole reason this handler reads `status` rather than only `fieldErrors`: the server
        answers it as a conflict with a sentence, not as a field-level 400, and the field is still
        where the reader can act on it.
      */
      if (error instanceof ApiError && error.status === 409) {
        form.setError('version', { message: describeError(error).body });
        return;
      }
      if (error instanceof ApiError && error.fieldErrors) {
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          if (field === 'version' || field === 'targetDate' || field === 'description') {
            form.setError(field, { message: messages.join(' ') });
          } else {
            form.setError('root', { message: messages.join(' ') });
          }
        }
        return;
      }
      form.setError('root', { message: describeError(error).body });
    }
  });

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="New release"
        documentTitle="New release"
        subtitle="A version, and optionally a date and a description. Nothing else is required."
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Releases', to: `/projects/${projectId}/releases` },
          { label: 'New' },
        ]}
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
        <Panel testId={`${TEST_ID}.Form`}>
          <form onSubmit={submit} noValidate className="flex max-w-[560px] flex-col gap-4">
            {form.formState.errors.root && (
              <Banner tone="bad" data-testid={`${TEST_ID}.Error`}>
                {form.formState.errors.root.message}
              </Banner>
            )}

            <Field
              label="Version"
              htmlFor={`${TEST_ID}-version`}
              help="Compared segment by segment — 1.10 comes after 1.9."
              error={form.formState.errors.version?.message}
            >
              <Input
                id={`${TEST_ID}-version`}
                autoFocus
                className="font-mono"
                placeholder="2.5"
                data-testid={`${TEST_ID}.Version`}
                {...form.register('version')}
              />
            </Field>

            <Field
              label="Target date"
              htmlFor={`${TEST_ID}-target`}
              help="Optional. Without one the release still exists, and shows on the timeline in the unscheduled lane."
              error={form.formState.errors.targetDate?.message}
            >
              <Input
                id={`${TEST_ID}-target`}
                type="date"
                data-testid={`${TEST_ID}.TargetDate`}
                {...form.register('targetDate')}
              />
            </Field>

            <Field
              label="Description"
              htmlFor={`${TEST_ID}-description`}
              error={form.formState.errors.description?.message}
            >
              <Textarea
                id={`${TEST_ID}-description`}
                rows={3}
                placeholder="What this version is for."
                data-testid={`${TEST_ID}.Description`}
                {...form.register('description')}
              />
            </Field>

            <div className="flex items-center gap-2 border-t border-line pt-3">
              <Button size="sm" asChild>
                <RouterLink to={`/projects/${projectId}/releases`} data-testid={`${TEST_ID}.Cancel`}>
                  Cancel
                </RouterLink>
              </Button>
              <span className="flex-1" />
              <PermissionGate projectId={projectId} permission="release:manage">
                {({ allowed }) => (
                  <Button
                    type="submit"
                    variant="primary"
                    size="sm"
                    disabled={!allowed || create.isPending}
                    data-testid={`${TEST_ID}.Submit`}
                  >
                    Create release
                  </Button>
                )}
              </PermissionGate>
            </div>
          </form>
        </Panel>

        {/*
          The mockup's card, verbatim. It answers the question this form provokes — "where do I put
          the sprint?" — and the answer is a product decision rather than a missing field.
        */}
        <Panel title="Not in this product" testId={`${TEST_ID}.NotHere`}>
          <ul className="m-0 flex list-none flex-col gap-[9px] p-0">
            {['Sprints', 'Velocity and sprint burndown', 'A release shared between two projects'].map(
              (absent) => (
                <li key={absent} className="flex items-start gap-2 text-[12.5px] text-ink-2">
                  <span aria-hidden className="text-ink-4">
                    &times;
                  </span>
                  {absent}
                </li>
              ),
            )}
          </ul>
          <p className="mt-2 mb-0 text-[11.5px] leading-[1.55] text-ink-4">
            Planning has a cadence because releases have dates. Nothing here asks you to invent a
            two-week rhythm you do not work in.
          </p>
        </Panel>
      </div>
    </div>
  );
}
