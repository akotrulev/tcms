import { useState } from 'react';
import { FolderGit2 } from 'lucide-react';
import { useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import {
  useAddRepository,
  useDeleteRepository,
  useProject,
  useProjectRepositories,
  useUpdateRepository,
} from '../../api/queries';
import type { ProjectRepository, ProjectRepositoryInput } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { SettingsSubnav } from './SettingsSubnav';
import { Badge, Banner } from '../../components/kit';
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
import { useCan } from '../../auth/useCan';
import { describeError } from '../../lib/describeError';

/**
 * `settings-repositories.html` — **reference data, and the screen says so first** (015 D16).
 *
 * The panel a reader arrives at answers the question the screen otherwise invites: this product
 * never holds a repository, never clones one and never runs a build. What is listed is what a new
 * joiner — or somebody's assistant — is told to check out, and **a project with none is not
 * degraded in any way.** That sentence is on the empty state rather than only in this comment,
 * because an empty list on a settings page reads as something unfinished unless it says otherwise.
 *
 * ## `primary` is a badge on position 0, not a control
 *
 * The contract computes it from `position` and stores no flag, so there is no "make primary"
 * button here and there could not be one: the only way to change which repository is primary is to
 * change the order, and no reorder operation exists on this surface. The badge is therefore a
 * statement about the list, not a property of the row — which is why removing the first row moves
 * it to the second, and why all four writes invalidate the whole list.
 *
 * ## Read by everyone, written by `project:settings`
 *
 * The list costs `project:read` and the writes cost `project:settings`, so this renders for
 * anybody who can see the project and disables its controls for the rest — §31's
 * disable-don't-hide. A reader who cannot edit still needs to learn where the code is, which is the
 * whole purpose of the page.
 *
 * The verdict is read with `useCan` and passed down as a prop rather than wrapped in a
 * `PermissionGate`, for `BacklogView`'s reason: the gate's denied branch renders its child inside a
 * tooltip-triggering `<span role="none">`, which is right around one control and wrong around a
 * page — invalid markup, and a tooltip over every row in the list.
 */
const TEST_ID = 'Repositories';

export function RepositoriesSettingsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const repositories = useProjectRepositories(projectId);
  const canEdit = useCan(projectId).can('project:settings');

  /** The row being edited, `'new'` while adding, `null` when the dialog is closed. */
  const [editing, setEditing] = useState<ProjectRepository | 'new' | null>(null);

  return (
    <div>
      {/*
        ~~`Repositories`~~ → **`Code repositories`** (021 §8.1). The bare word is the one this
        product uses for the *test* repository — the suites and cases — so a settings row called
        `Repositories` named the one thing on the screen it was not about. The route is unchanged.
      */}
      <PageHeader
        views={<SettingsSubnav projectId={projectId} />}
        testId={`${TEST_ID}.Header`}
        title="Code repositories"
        documentTitle={
          project.data ? `Code repositories · ${project.data.name}` : 'Code repositories'
        }
        subtitle="Where this project's code lives, and how it is built. Reference information — nothing here is cloned, fetched or run."
        actions={
          canEdit ?
            <Button variant="primary" onClick={() => setEditing('new')} data-testid={`${TEST_ID}.Add`}>
              Add repository
            </Button>
          : undefined
        }
      />

      <Banner tone="neutral" className="mb-4" data-testid={`${TEST_ID}.Note`}>
        <span>
          <strong>Reference information, nothing more.</strong> This product never holds a repository, never
          clones one and never runs a build. What is listed here is what a new joiner — or somebody&rsquo;s
          assistant — is told to check out.
        </span>
      </Banner>

      <div className="max-w-[960px]">
        <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...repositories, refetch: () => void repositories.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            icon: FolderGit2,
            title: 'No repositories listed',
            body: 'Nothing else in this project changes. Issues, boards, releases and test runs all work exactly the same — a new joiner just has to ask where the code is, and an assistant is told by the person running it.',
            action:
              canEdit ?
                <Button size="sm" onClick={() => setEditing('new')}>
                  Add one
                </Button>
              : undefined,
            testId: `${TEST_ID}.Empty`,
          }}
        >
          {(rows) => (
            <Panel
              title="Code repositories"
              testId={`${TEST_ID}.List`}
              actions={<span className="text-[11.5px] text-ink-4">{rows.length}</span>}
            >
              <div className="flex flex-col gap-3">
                {rows.map((repository) => (
                  <RepositoryRow
                    key={repository.id}
                    projectId={projectId}
                    repository={repository}
                    canEdit={canEdit}
                    onEdit={() => setEditing(repository)}
                  />
                ))}
              </div>

              <p className="m-0 mt-3 border-t border-line pt-2.5 text-[11.5px] leading-[1.55] text-ink-4">
                These four values are what an assistant receives with an AI-eligible task, alongside its
                story, the epic goal and the linked spec.
              </p>
            </Panel>
          )}
        </DataRegion>
      </div>

      {editing && (
        <RepositoryDialog
          projectId={projectId}
          repository={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}

function RepositoryRow({
  projectId,
  repository,
  canEdit,
  onEdit,
}: {
  projectId: string;
  repository: ProjectRepository;
  canEdit: boolean;
  onEdit: () => void;
}) {
  const remove = useDeleteRepository(projectId);

  return (
    <div
      data-testid={`${TEST_ID}.Row`}
      className="flex flex-col gap-2.5 rounded-lg border border-line p-3"
    >
      <div className="flex flex-wrap items-center gap-2">
        <strong className="text-[13px] text-ink" data-testid={`${TEST_ID}.Row.Name`}>
          {repository.name}
        </strong>
        {repository.primary && (
          <Badge tone="steel" data-testid={`${TEST_ID}.Row.Primary`}>
            primary
          </Badge>
        )}
        <span className="flex-1" />
        {canEdit && (
          <>
            <Button size="sm" onClick={onEdit} data-testid={`${TEST_ID}.Row.Edit`}>
              Edit
            </Button>
            <Button
              size="sm"
              variant="danger"
              disabled={remove.isPending}
              onClick={() => remove.mutate(repository.id)}
              data-testid={`${TEST_ID}.Row.Remove`}
            >
              Remove
            </Button>
          </>
        )}
      </div>

      <dl className="m-0 grid grid-cols-[minmax(0,7rem)_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[12.5px]">
        <Term label="Address">
          <Identifier variant="muted" testId={`${TEST_ID}.Row.Url`}>
            {repository.url}
          </Identifier>
        </Term>
        <Term label="Default branch">
          <Identifier variant="muted">{repository.defaultBranch}</Identifier>
        </Term>
        <Term label="Folder">
          <Identifier variant="muted">{repository.subdirectory}</Identifier>
        </Term>
        <Term label="Build">
          <Identifier variant="muted">{repository.buildCommand}</Identifier>
        </Term>
      </dl>

      {remove.isError && (
        <Banner tone="bad" data-testid={`${TEST_ID}.Row.RemoveFailed`}>
          <span role="alert">{describeError(remove.error).body}</span>
        </Banner>
      )}
    </div>
  );
}

/**
 * Add one, or replace the five values of one that exists.
 *
 * One dialog for both, because the server takes one body for both: `PATCH` is declared as a full
 * replacement, so an edit that omitted a field would blank it rather than leave it. The form
 * therefore always sends all five, seeded from the row being edited.
 */
function RepositoryDialog({
  projectId,
  repository,
  onClose,
}: {
  projectId: string;
  repository: ProjectRepository | null;
  onClose: () => void;
}) {
  const add = useAddRepository(projectId);
  const update = useUpdateRepository(projectId);
  const mutation = repository ? update : add;

  const [draft, setDraft] = useState<ProjectRepositoryInput>({
    name: repository?.name ?? '',
    url: repository?.url ?? '',
    defaultBranch: repository?.defaultBranch ?? 'main',
    // `/` is the repository root and the contract's own example. A blank would be refused, and a
    // reader who has never thought about a monorepo should not have to before they can save.
    subdirectory: repository?.subdirectory ?? '/',
    buildCommand: repository?.buildCommand ?? '',
  });

  const fieldErrors =
    mutation.error instanceof ApiError ? (mutation.error.fieldErrors ?? undefined) : undefined;

  const edit = (patch: Partial<ProjectRepositoryInput>) => setDraft((current) => ({ ...current, ...patch }));

  const submit = () => {
    const done = { onSuccess: () => onClose() };
    if (repository) update.mutate({ id: repository.id, input: draft }, done);
    else add.mutate(draft, done);
  };

  return (
    <Dialog open onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{repository ? `Edit ${repository.name}` : 'Add a repository'}</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex flex-col gap-4">
          {mutation.error && !fieldErrors && (
            <Banner tone="bad" data-testid={`${TEST_ID}.Dialog.Error`}>
              <span role="alert">{describeError(mutation.error).body}</span>
            </Banner>
          )}

          <Field label="Name" htmlFor="repository-name" error={fieldErrors?.name?.join(' ')}>
            <Input
              id="repository-name"
              value={draft.name}
              onChange={(event) => edit({ name: event.target.value })}
              autoFocus
              data-testid={`${TEST_ID}.Dialog.Name`}
            />
          </Field>

          <Field
            label="Address"
            htmlFor="repository-url"
            error={fieldErrors?.url?.join(' ')}
            help="Whatever a person would clone — an SSH or HTTPS address. Nothing here connects to it."
          >
            <Input
              id="repository-url"
              value={draft.url}
              onChange={(event) => edit({ url: event.target.value })}
              data-testid={`${TEST_ID}.Dialog.Url`}
            />
          </Field>

          <Field
            label="Default branch"
            htmlFor="repository-branch"
            error={fieldErrors?.defaultBranch?.join(' ')}
          >
            <Input
              id="repository-branch"
              value={draft.defaultBranch}
              onChange={(event) => edit({ defaultBranch: event.target.value })}
              data-testid={`${TEST_ID}.Dialog.Branch`}
            />
          </Field>

          <Field
            label="Folder"
            htmlFor="repository-subdirectory"
            error={fieldErrors?.subdirectory?.join(' ')}
            help="The working subdirectory within the repository. “/” for the root."
          >
            <Input
              id="repository-subdirectory"
              value={draft.subdirectory}
              onChange={(event) => edit({ subdirectory: event.target.value })}
              data-testid={`${TEST_ID}.Dialog.Subdirectory`}
            />
          </Field>

          <Field
            label="Build"
            htmlFor="repository-build"
            error={fieldErrors?.buildCommand?.join(' ')}
            help="What somebody runs to build and test it. Written down here, run on their machine."
          >
            <Input
              id="repository-build"
              value={draft.buildCommand}
              onChange={(event) => edit({ buildCommand: event.target.value })}
              data-testid={`${TEST_ID}.Dialog.Build`}
            />
          </Field>
        </div>

        <DialogFooter>
          <Button variant="quiet" onClick={onClose} data-testid={`${TEST_ID}.Dialog.Cancel`}>
            Cancel
          </Button>
          <Button
            variant="primary"
            disabled={mutation.isPending}
            onClick={submit}
            data-testid={`${TEST_ID}.Dialog.Save`}
          >
            {repository ? 'Save' : 'Add'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** One `dt`/`dd` pair, so the two halves cannot drift apart in the class list. */
function Term({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-ink-2">{children}</dd>
    </>
  );
}
