import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ApiError } from '../../api/client';
import {
  useCreateIssue,
  useIssues,
  useProject,
  useProjectMembers,
  useProjectWorkflow,
} from '../../api/queries';
import type { IssueTypeRef } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Banner, Chip } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input, Textarea } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { describeError } from '../../lib/describeError';
import { toast } from '../../notifications/toast';
import { TypeIcon } from './TypeIcon';

/**
 * `issue-new.html` — **quick add is one field**, and the full form sits under it.
 *
 * <h2>The one field is the point (story H3, D29)</h2>
 *
 * A solo project should not need an epic and a story before "a thing to do". So the top panel is a
 * title box and a button, and everything the full form offers is optional and below the fold of
 * attention. The caption says exactly what pressing Add produces, in the project's own words —
 * *"Creates a Story at the story level with no parent. You can attach it to an epic later — or
 * never."* — because a create whose result the person has to go and look at is a create they will
 * stop trusting.
 *
 * <h2>What is ABSENT rather than empty (D-f)</h2>
 *
 * The mockup carries a **Target release** select (M3), a **Labels** input (M2) and an
 * **AI-eligible** toggle (M5), and a "Where this lands" panel with a release card and a backlog
 * card. None of them is rendered. The epic card in that panel **is** M1's, and it stays, because
 * completion recalculating on create is this module's own behaviour.
 *
 * <h2>The parent picker appears with the level, not after the refusal</h2>
 *
 * A `TASK`-level type is a breakdown *of* something and the server refuses one with no parent — a
 * 400 naming the level. The form must not let somebody type a title, press Create and be told no,
 * so the parent field appears the moment a task-level type is chosen and is required there. An
 * `EPIC`-level type is refused a parent for the mirror reason, so the field is not rendered at all
 * for one: there is no level above an epic to pick from.
 */

const TEST_ID = 'IssueNew';
const MAX_TITLE = 500;

const IssueSchema = z.object({
  typeId: z.string().min(1, 'Choose a type.'),
  title: z
    .string()
    .min(1, 'Give it a summary.')
    .max(MAX_TITLE, `Keep the summary under ${MAX_TITLE} characters.`),
  description: z.string().optional(),
  parentKey: z.string().optional(),
  assigneeId: z.string().optional(),
  estimateHours: z.string().optional(),
  dueDate: z.string().optional(),
  acceptanceCriteria: z.string().optional(),
});

type IssueFormInput = z.infer<typeof IssueSchema>;

export function IssueNewPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();

  const project = useProject(projectId);
  const workflow = useProjectWorkflow(projectId);
  const members = useProjectMembers(projectId, true);
  // Epic-level issues, for the optional parent of a story and the required one of nothing else.
  const epics = useIssues(projectId, { level: 'EPIC' }, 'key');

  return (
    <div>
      <title>{project.data ? `Add work · ${project.data.name} · TCMS` : 'Add work · TCMS'}</title>

      <header
        data-testid={`${TEST_ID}.Header`}
        className="mb-3 flex flex-wrap items-start gap-3 border-b border-line pb-3"
      >
        <div className="min-w-0 flex-1">
          <nav aria-label="Breadcrumb" className="mb-1 text-[12.5px] text-ink-3">
            <RouterLink
              to={`/projects/${projectId}/work`}
              className="text-ink-3 no-underline hover:text-ink-2"
              data-testid={`${TEST_ID}.Header.Breadcrumb`}
            >
              Work
            </RouterLink>
            <span className="px-1.5 text-ink-4">/</span>
            <b className="font-medium text-ink-2">New issue</b>
          </nav>
          <h1
            data-testid={`${TEST_ID}.Header.Title`}
            className="m-0 text-[19px] font-semibold tracking-[-.018em] text-ink"
          >
            Add work
          </h1>
          <p className="m-0 mt-1 max-w-[74ch] text-[13px] text-ink-3">
            Quick add is one field. Everything else is optional and stays out of the way.
          </p>
        </div>
        <Button size="sm" asChild>
          <RouterLink to={`/projects/${projectId}/work`}>Cancel</RouterLink>
        </Button>
      </header>

      {/*
        **The whole form is inside the workflow's region.** The types are the project's own rows,
        so a form rendered before they arrive would be a type picker with nothing in it — and one
        rendered after a FAILED read would be a form that cannot produce a valid create, which is
        exactly the "empty and broken look identical" failure `DataRegion` exists to separate.
      */}
      <DataRegion
        testId={`${TEST_ID}.Content`}
        state={{ ...workflow, refetch: () => void workflow.refetch() }}
        isEmpty={(data) => data.types.length === 0}
        empty={{
          kind: 'by-design',
          title: 'This project has no issue types',
          body:
            'A project is seeded with six. One with none cannot hold an issue at all — an '
            + 'administrator can add them on the workflow settings screen.',
        }}
      >
        {(data) => (
          <IssueForm
            projectId={projectId}
            types={data.types}
            members={members.data ?? []}
            epics={(epics.data ?? []).map((epic) => ({ key: epic.key, title: epic.title }))}
            onCreated={(key) => {
              toast('Created', { variant: 'success' });
              navigate(`/issues/${encodeURIComponent(key)}`);
            }}
          />
        )}
      </DataRegion>
    </div>
  );
}

function IssueForm({
  projectId,
  types,
  members,
  epics,
  onCreated,
}: {
  projectId: string;
  types: IssueTypeRef[];
  members: { id: string; displayName: string }[];
  epics: { key: string; title: string }[];
  onCreated: (key: string) => void;
}) {
  /*
    What quick add makes, chosen from the project's own vocabulary rather than named.

    The mockup's chip says "Task", and the seed does ship one — but a type is a ROW, so a literal
    here would be a create that silently fails on any project that renamed or removed it. The
    first story-level type is the right pick for the same reason the caption exists: a quick add
    with no parent has to land at the level that permits one, and `TASK` level requires a parent
    it is not going to ask for.
  */
  const quickType = useMemo(
    () => types.find((type) => type.level === 'STORY') ?? types[0],
    [types],
  );

  const create = useCreateIssue(projectId);
  const [quickTitle, setQuickTitle] = useState('');
  const [quickError, setQuickError] = useState<string | null>(null);

  const form = useForm<IssueFormInput>({
    resolver: zodResolver(IssueSchema),
    defaultValues: { typeId: quickType?.id ?? '', title: '' },
  });

  const typeId = form.watch('typeId');
  const chosen = types.find((type) => type.id === typeId);

  const submitQuick = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!quickType) return;
    if (quickTitle.trim() === '') {
      setQuickError('Give it a summary.');
      return;
    }
    setQuickError(null);
    try {
      const issue = await create.mutateAsync({ typeId: quickType.id, title: quickTitle.trim() });
      onCreated(issue.key);
    } catch (error) {
      setQuickError(describeError(error).body);
    }
  };

  const submitFull = form.handleSubmit(async (values) => {
    // Mirrored here rather than left to the 400, so the refusal lands on the field: the server
    // refuses a task-level type with no parent, and a person who typed a title first should not
    // learn that from a banner.
    if (chosen?.level === 'TASK' && !values.parentKey) {
      form.setError('parentKey', {
        message: 'A sub-task belongs to a story. Choose the one it breaks down.',
      });
      return;
    }
    try {
      const issue = await create.mutateAsync({
        typeId: values.typeId,
        title: values.title,
        description: values.description || null,
        parentKey: values.parentKey || null,
        assigneeId: values.assigneeId || null,
        estimateHours: values.estimateHours ? Number(values.estimateHours) : null,
        dueDate: values.dueDate || null,
        acceptanceCriteria: values.acceptanceCriteria
          ? values.acceptanceCriteria.split('\n').map((line) => line.trim()).filter(Boolean)
          : undefined,
      });
      onCreated(issue.key);
    } catch (error) {
      if (error instanceof ApiError && error.fieldErrors) {
        for (const [field, messages] of Object.entries(error.fieldErrors)) {
          if (field === 'title' || field === 'parentKey' || field === 'typeId') {
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

  const parentChoices =
    chosen?.level === 'TASK'
      ? // A sub-task's parent is a story-level issue, not an epic — the depth rule is one level.
        // The list is not fetched here: the caller passes epics for the story case, and a task's
        // parent is chosen by key, which is what a person following a story into a breakdown has.
        []
      : epics;

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
      <div className="flex flex-col gap-4">
        <Panel
          title="Quick add"
          testId={`${TEST_ID}.Quick`}
          actions={<span className="text-[11.5px] text-ink-4">what a solo project needs</span>}
        >
          <form onSubmit={submitQuick} className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              {/*
                The type is STATED, not chosen — that is what makes this one field. It is a badge
                rather than a picker because a chip with nothing behind it reads as a control
                (`Badge`'s own rule), and the full form below is where a different type is picked.
              */}
              {quickType && (
                <span
                  data-testid={`${TEST_ID}.Quick.Type`}
                  className="inline-flex items-center gap-1.5 rounded-[5px] border border-line px-[9px] py-[3px] text-[12px] text-ink-2"
                >
                  <TypeIcon type={quickType} />
                  {quickType.name}
                </span>
              )}
              <Input
                aria-label="Summary"
                autoFocus
                className="min-w-0 flex-1"
                placeholder="Renew the staging certificate"
                value={quickTitle}
                data-testid={`${TEST_ID}.Quick.Title`}
                onChange={(event) => setQuickTitle(event.target.value)}
              />
              <PermissionGate projectId={projectId} permission="issue:create">
                {({ allowed }) => (
                  <Button
                    type="submit"
                    variant="primary"
                    size="sm"
                    disabled={!allowed || create.isPending}
                    data-testid={`${TEST_ID}.Quick.Add`}
                  >
                    Add
                  </Button>
                )}
              </PermissionGate>
            </div>
            {quickError ? (
              <p role="alert" data-testid={`${TEST_ID}.Quick.Error`} className="m-0 mt-2 text-[12px] text-[color:var(--color-failed)]">
                {quickError}
              </p>
            ) : (
              <p className="m-0 mt-2 text-[11.5px] text-ink-4">
                {quickType
                  ? `Creates a ${quickType.name} at the story level with no parent. You can attach `
                    + 'it to an epic later — or never.'
                  : 'This project has no story-level type to create.'}
              </p>
            )}
          </form>
        </Panel>

        <Panel title="Full form" testId={`${TEST_ID}.Full`}>
          <form onSubmit={submitFull} noValidate className="flex flex-col gap-4">
            {form.formState.errors.root && (
              <Banner tone="bad" data-testid={`${TEST_ID}.Error`}>
                {form.formState.errors.root.message}
              </Banner>
            )}

            <Field
              label="Type"
              htmlFor={`${TEST_ID}-type`}
              help="A bug counts as unfinished work in its epic's completion. A spike is excluded from the percentage, and the epic page says so."
              error={form.formState.errors.typeId?.message}
            >
              <div id={`${TEST_ID}-type`} className="flex flex-wrap gap-1.5">
                {types.map((type) => (
                  <Chip
                    key={type.id}
                    selected={type.id === typeId}
                    onClick={() => form.setValue('typeId', type.id, { shouldValidate: true })}
                    data-testid={`${TEST_ID}.Type.${type.id}`}
                  >
                    <TypeIcon type={type} />
                    {type.name}
                  </Chip>
                ))}
              </div>
            </Field>

            <Field
              label="Summary"
              htmlFor={`${TEST_ID}-title`}
              error={form.formState.errors.title?.message}
            >
              <Input
                id={`${TEST_ID}-title`}
                placeholder="Remember delivery address between visits"
                data-testid={`${TEST_ID}.Title`}
                {...form.register('title')}
              />
            </Field>

            <Field label="Description" htmlFor={`${TEST_ID}-description`}>
              <Textarea
                id={`${TEST_ID}-description`}
                placeholder="What this is for, in the words the team uses."
                data-testid={`${TEST_ID}.Description`}
                {...form.register('description')}
              />
            </Field>

            <Field
              label="Acceptance criteria"
              htmlFor={`${TEST_ID}-criteria`}
              help="One per line. Each becomes its own tick on the story, and the story counts them."
            >
              <Textarea
                id={`${TEST_ID}-criteria`}
                placeholder="Given … when … then …"
                data-testid={`${TEST_ID}.Criteria`}
                {...form.register('acceptanceCriteria')}
              />
            </Field>

            {/*
              **The parent field follows the LEVEL, and there are three cases rather than two.**

              A task-level type must have one, a story-level type may have one (requiring an epic
              would force a junk epic per stray bug — scope §4), and an epic-level type is refused
              one because there is no level above it. So the epic case renders nothing at all: a
              disabled "Epic — not applicable" select would be a control explaining a rule instead
              of the rule simply not applying.
            */}
            {chosen?.level === 'TASK' && (
              <Field
                label="Parent story"
                htmlFor={`${TEST_ID}-parent`}
                help="Required. A sub-task is a breakdown of something — it belongs to that story or it is deleted."
                error={form.formState.errors.parentKey?.message}
              >
                <Input
                  id={`${TEST_ID}-parent`}
                  placeholder="ACME-118"
                  data-testid={`${TEST_ID}.Parent`}
                  {...form.register('parentKey')}
                />
              </Field>
            )}

            {chosen?.level === 'STORY' && (
              <Field
                label="Epic"
                htmlFor={`${TEST_ID}-epic`}
                help="Optional. A story without an epic is a normal story and appears in the backlog."
              >
                <NativeSelect
                  id={`${TEST_ID}-epic`}
                  data-testid={`${TEST_ID}.Epic`}
                  {...form.register('parentKey')}
                >
                  <option value="">— none —</option>
                  {parentChoices.map((epic) => (
                    <option key={epic.key} value={epic.key}>
                      {`${epic.title} (${epic.key})`}
                    </option>
                  ))}
                </NativeSelect>
              </Field>
            )}

            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Assignee" htmlFor={`${TEST_ID}-assignee`}>
                <NativeSelect
                  id={`${TEST_ID}-assignee`}
                  data-testid={`${TEST_ID}.Assignee`}
                  {...form.register('assigneeId')}
                >
                  {/*
                    Unassigned, not "assigned to me". A board where every issue silently belongs to
                    whoever typed it cannot express the unclaimed pool §6l calls for.
                  */}
                  <option value="">— unassigned —</option>
                  {members.map((member) => (
                    <option key={member.id} value={member.id}>
                      {member.displayName}
                    </option>
                  ))}
                </NativeSelect>
              </Field>

              <Field
                label="Estimate"
                htmlFor={`${TEST_ID}-estimate`}
                help="In hours — the same unit the budget and any invoice use."
              >
                <Input
                  id={`${TEST_ID}-estimate`}
                  inputMode="numeric"
                  placeholder="8"
                  data-testid={`${TEST_ID}.Estimate`}
                  {...form.register('estimateHours')}
                />
              </Field>

              <Field label="Due date" htmlFor={`${TEST_ID}-due`}>
                <Input
                  id={`${TEST_ID}-due`}
                  type="date"
                  data-testid={`${TEST_ID}.DueDate`}
                  {...form.register('dueDate')}
                />
              </Field>
            </div>

            <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
              <Button size="sm" asChild>
                <RouterLink to={`/projects/${projectId}/work`}>Cancel</RouterLink>
              </Button>
              <span className="flex-1" />
              <PermissionGate projectId={projectId} permission="issue:create">
                {({ allowed }) => (
                  <Button
                    type="submit"
                    variant="primary"
                    size="sm"
                    disabled={!allowed || create.isPending}
                    data-testid={`${TEST_ID}.Create`}
                  >
                    Create
                  </Button>
                )}
              </PermissionGate>
            </div>
          </form>
        </Panel>
      </div>

      {/*
        "Where this lands", minus the two cards that belong to modules this build does not have
        (D-f): no `Release 2.4 · counted in readiness` (M3), no `Backlog, bottom of 2.4` (M2). The
        epic card stays, because completion recalculating on create is M1's own behaviour and is
        the thing a person choosing an epic is deciding about.
      */}
      <aside className="flex flex-col gap-4">
        <Panel title="Where this lands" testId={`${TEST_ID}.Lands`}>
          {chosen?.level === 'STORY' && form.watch('parentKey') ? (
            <div className="flex items-start gap-2 rounded-[6px] border border-line p-2.5">
              <span className="text-[13px] text-ink">
                {epics.find((epic) => epic.key === form.watch('parentKey'))?.title
                  ?? form.watch('parentKey')}
                <span className="mt-0.5 block text-[11px] text-ink-4">
                  completion recalculates when this is created
                </span>
              </span>
            </div>
          ) : (
            <p className="m-0 text-[12.5px] text-ink-3">
              Nothing yet. A story attached to an epic changes that epic&rsquo;s completion the
              moment it is created — pick one above and it will say which.
            </p>
          )}
        </Panel>
      </aside>
    </div>
  );
}
