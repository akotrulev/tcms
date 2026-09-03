import { useAgentTaskContext, useUpdateIssue } from '../../api/queries';
import type { AgentTaskContext, Issue } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import { Button, Switch } from '../../components/ui';
import { Link as RouterLink } from 'react-router-dom';
import { LinkedSpecPanel } from './LinkedSpecPanel';
import { StatusBadge } from './StatusBadge';

/**
 * `task.html`'s body.
 *
 * ~~Almost everything on that mockup belongs to M5 — the "What the assistant received" panel, the
 * "Pull request" panel, the "Assistant" rail panel and the `AI-ELIGIBLE` chip are all absent.~~ →
 * **015 T5 landed them**, so what is here now is the mockup minus one panel: "What the assistant
 * was refused", which stays absent for the reason at the foot of this comment.
 *
 * **The assistant panels are conditional on the flag, not on the module.** A task nobody marked
 * AI-eligible renders exactly what it rendered before — no empty "Pull request: none" card, no
 * context panel with six dashes in it. A panel that exists only to say a feature was not used is
 * an advertisement, which is the rule the earlier absence was argued from and it has not changed.
 *
 * <h2>Why the context panel is a second read and not a slice of the issue</h2>
 *
 * `GET /issues/{key}/agent-context` is what the assistant itself fetches (D4), and rendering the
 * *same payload* is the point rather than an implementation convenience: the question a person
 * brings to this panel is "did it have the spec, or did it invent that", and only the thing
 * actually handed over can answer it. Reassembling an equivalent from the issue, the parent and
 * the repositories list would be a second, drifting copy of the composite — and would answer with
 * what the screen can see rather than with what the assistant was sent.
 *
 * <h2>"What the assistant was refused" is not built, and the contract is why</h2>
 *
 * The refusal is recorded as an `issue.close_refused` **audit event**. Two things stop that
 * becoming this panel: the trail costs `audit:view`, which most readers of a task do not hold, and
 * `AuditFilters` carries no target — so there is no way to ask for one issue's refusals, only for a
 * project's, paged. The live half of the mockup's copy does land, in `TransitionMenu`: the 409's
 * own sentence beside the picker at the moment somebody tries, and the DONE row captioned *needs a
 * second person* with the setting named under it.
 */
export function TaskPanels({ issue }: { issue: Issue }) {
  return (
    <>
      <Panel title="Description" testId="Task.Description">
        {issue.description ? (
          <p className="m-0 text-[13px] leading-[1.6] whitespace-pre-wrap text-ink-2">
            {issue.description}
          </p>
        ) : (
          <p className="m-0 text-[12.5px] text-ink-3" data-testid="Task.Description.Empty">
            No description. The acceptance criteria on{' '}
            {issue.parentKey ?? 'the parent story'} are what this is measured against.
          </p>
        )}
      </Panel>

      {issue.agentEligible && <AssistantContextPanel issue={issue} />}
      {issue.prUrl && <PullRequestPanel issue={issue} />}

      <LinkedSpecPanel issue={issue} />
    </>
  );
}

/**
 * The AI-eligible switch and where to manage the assistant that reads it — `task.html`'s rail
 * panel, mounted by `IssueDetailPage` beside "Where it can go".
 *
 * Exported from this file rather than one of its own because it is the same screen's other half,
 * and a module may export two components; what it may not do — oxlint's `react/only-export-components`
 * — is export a component and a function.
 *
 * **The write is `useUpdateIssue`, not a hook of its own.** `agentEligible` is a field on
 * `IssueUpdate` like every other, and a `useSetAgentEligible` wrapping one PATCH for one caller is
 * an abstraction with nothing on the other side of it.
 */
export function AssistantRailPanel({ issue }: { issue: Issue }) {
  const update = useUpdateIssue(issue.key);

  return (
    <Panel title="Assistant" testId="Task.Assistant">
      <div className="flex flex-col gap-3">
        <div className="flex items-start gap-3">
          <div className="min-w-0 flex-1">
            <p className="m-0 text-[13px] font-medium text-ink">AI-eligible</p>
            <p className="m-0 mt-0.5 text-[11.5px] leading-[1.5] text-ink-3">
              Only flagged tasks reach an assistant&rsquo;s queue. Nothing else about the task
              changes.
            </p>
          </div>
          <PermissionGate projectId={issue.projectId} permission="issue:edit">
            {({ allowed }) => (
              <Switch
                checked={issue.agentEligible}
                disabled={!allowed || update.isPending}
                onCheckedChange={(checked) => update.mutate({ agentEligible: checked })}
                aria-label="AI-eligible"
                data-testid="Task.Assistant.Toggle"
              />
            )}
          </PermissionGate>
        </div>

        {update.isError && (
          <Banner tone="bad" data-testid="Task.Assistant.Failed">
            The flag did not save. The switch shows what the server holds.
          </Banner>
        )}

        <p className="m-0 text-[11.5px] leading-[1.55] text-ink-4">
          An assistant is a credential <strong>you</strong> own, running on your own machine. This
          product never holds a repository, never runs a build and never calls a model.
        </p>

        <Button size="sm" asChild data-testid="Task.Assistant.Manage">
          <RouterLink to="/account/assistants">Manage my assistants</RouterLink>
        </Button>
      </div>
    </Panel>
  );
}

/** The composite an assistant was handed, rendered as the same list it received. */
function AssistantContextPanel({ issue }: { issue: Issue }) {
  const context = useAgentTaskContext(issue.key, issue.agentEligible);

  return (
    <Panel
      title="What the assistant received"
      testId="Task.AgentContext"
      actions={<span className="text-[11.5px] text-ink-4">the same context a person would open</span>}
    >
      <DataRegion
        testId="Task.AgentContext.Region"
        state={{ ...context, refetch: () => void context.refetch() }}
      >
        {(data) => <AgentContextList issue={issue} context={data} />}
      </DataRegion>
    </Panel>
  );
}

function AgentContextList({ issue, context }: { issue: Issue; context: AgentTaskContext }) {
  const repository = context.repositories[0];

  return (
    <div className="flex flex-col gap-3">
      <dl className="m-0 grid grid-cols-[minmax(0,8rem)_minmax(0,1fr)] gap-x-3 gap-y-2 text-[12.5px]">
        {context.story && (
          <Row label="Parent story">
            <RouterLink
              to={`/issues/${encodeURIComponent(context.story.key)}`}
              className="text-steel no-underline hover:underline"
              data-testid="Task.AgentContext.Story"
            >
              {`${context.story.key} · ${context.story.title}`}
            </RouterLink>
            {context.story.acceptanceCriteria.length > 0 && (
              <ul className="m-0 mt-1.5 flex list-none flex-col gap-1 p-0 text-[12px] text-ink-3">
                {context.story.acceptanceCriteria.map((criterion) => (
                  <li key={criterion.text} data-testid="Task.AgentContext.Criterion">
                    <span aria-hidden className="mr-1.5 text-ink-4">
                      {criterion.done ? '✓' : '○'}
                    </span>
                    {criterion.text}
                  </li>
                ))}
              </ul>
            )}
          </Row>
        )}

        {context.epic && (
          <Row label="Epic goal">
            <span data-testid="Task.AgentContext.Epic" className="text-ink-2">
              {context.epic.goal ?? `${context.epic.key} — no goal written`}
            </span>
          </Row>
        )}

        {issue.release && (
          <Row label="Target release">
            <RouterLink
              to={`/releases/${encodeURIComponent(issue.release.id)}`}
              className="text-steel no-underline hover:underline"
              data-testid="Task.AgentContext.Release"
            >
              {issue.release.version}
            </RouterLink>
          </Row>
        )}

        {context.specExcerpts.map((excerpt) => (
          <Row key={excerpt.title} label="Linked spec">
            <span data-testid="Task.AgentContext.Excerpt">
              <span className="text-ink-2">{excerpt.title}</span>
              <span className="mt-1 block text-[12px] leading-[1.55] text-ink-3">
                {excerpt.excerpt}
              </span>
            </span>
          </Row>
        ))}

        {repository ? (
          <>
            <Row label="Repository">
              <span data-testid="Task.AgentContext.Repository">
                <Identifier variant="muted">{repository.url}</Identifier>{' '}
                <Identifier variant="muted">{repository.defaultBranch}</Identifier>
              </span>
            </Row>
            <Row label="Build">
              <Identifier variant="muted" testId="Task.AgentContext.Build">
                {repository.buildCommand}
              </Identifier>
            </Row>
          </>
        ) : (
          <Row label="Repository">
            {/*
              Named rather than omitted, and it is the one absence on this panel worth drawing: a
              project that lists none is not degraded, but an assistant working this task was told
              where the code is by the person running it rather than by the product. A reader
              wondering why it checked out the wrong thing needs to see that.
            */}
            <span className="text-ink-4" data-testid="Task.AgentContext.NoRepository">
              None listed for this project — whoever runs the assistant tells it where the code is.
            </span>
          </Row>
        )}

        <Row label="Moves allowed">
          <span className="flex flex-wrap gap-1.5" data-testid="Task.AgentContext.Moves">
            {context.issue.allowedTransitions.length === 0 ? (
              <span className="text-ink-4">None from here.</span>
            ) : (
              context.issue.allowedTransitions.map((status) => (
                <StatusBadge key={status.id} status={status} />
              ))
            )}
          </span>
        </Row>
      </dl>

      <p className="m-0 border-t border-line pt-2.5 text-[11.5px] leading-[1.55] text-ink-4">
        The assistant runs on its owner&rsquo;s machine, in their checkout, with their git. This
        product handed over a task and recorded what came back —{' '}
        <RouterLink
          to={`/projects/${issue.projectId}/settings/repositories`}
          className="text-steel no-underline hover:underline"
        >
          where the repository and build command come from
        </RouterLink>
        .
      </p>
    </div>
  );
}

/** The pull request an assistant reported back, from `issue.prUrl`. */
function PullRequestPanel({ issue }: { issue: Issue }) {
  return (
    <Panel title="Pull request" testId="Task.PullRequest">
      <div className="flex flex-wrap items-center gap-2.5">
        {/*
          `prUrl` leaves this product entirely, so it is an `<a>` and never a `RouterLink`, and it
          carries `rel="noreferrer"`: the value is typed by whoever ran the assistant and this
          product neither validates it nor visits it.
        */}
        <a
          href={issue.prUrl ?? undefined}
          target="_blank"
          rel="noreferrer"
          data-testid="Task.PullRequest.Link"
          className="min-w-0 text-[13px] break-all text-steel no-underline hover:underline"
        >
          {issue.prUrl}
        </a>
        <Badge tone="plain">reported by the assistant</Badge>
      </div>
      <p className="m-0 mt-2 text-[11.5px] leading-[1.55] text-ink-4">
        A link, not an integration. Nothing here reads the branch, the diff or the review state —
        the pull request lives with whoever opened it.
      </p>
    </Panel>
  );
}

/** One `dt`/`dd` pair, so the two halves cannot drift apart in the class list. */
function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt className="font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-ink-2">{children}</dd>
    </>
  );
}
