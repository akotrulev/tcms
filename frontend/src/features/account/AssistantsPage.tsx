import { Bot } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import { useMyAssistants, useRevokeAssistant } from '../../api/queries';
import type { Assistant } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Banner } from '../../components/kit';
import { Button } from '../../components/ui';
import { formatAge, formatDate } from '../../lib/format';

/**
 * `profile-assistants.html` — **the caller's own AI assistants**, which are `Agent`-role API
 * tokens they issued for themselves (015 D26).
 *
 * ## Why this is not the admin token screen with a filter on it
 *
 * `/admin/tokens` administers the company's credentials and costs `token:issue`; this costs
 * authentication alone, because an assistant grants nothing its owner does not already hold. The
 * two screens read the same rows and answer different questions, and merging them would put a
 * privileged surface behind a self-service one.
 *
 * ## Two of the mockup's panels are absent, and each names the contract that stopped it
 *
 * | Mockup panel | Why not |
 * |---|---|
 * | *"What mine did this week"* | It is the audit trail filtered to the caller's own tokens. The trail costs `audit:view` — which most people reading their own profile do not hold — and `AuditFilters` carries no token or target parameter, so the question cannot be asked even by somebody who does |
 * | *"My AI-eligible work"* | `GET /issues` requires `projectId`, so a cross-project list of one person's flagged work is one read per project the caller can see. A profile page that fans out over every project to fill a side panel is the wrong trade; the work table already answers it per project |
 *
 * Neither is rendered empty. What lands is the list, the limits, and the sentence the whole screen
 * exists to make true: the assistant is yours, on your machine, and this product never ran it.
 */
const TEST_ID = 'Assistants';

export function AssistantsPage() {
  const assistants = useMyAssistants();

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="AI assistants"
        documentTitle="My AI assistants"
        crumbs={[{ label: 'My profile' }, { label: 'AI assistants' }]}
        subtitle="An assistant connects with a credential of its own that carries your identity and your permissions — never more."
        actions={
          <Button variant="primary" asChild data-testid={`${TEST_ID}.Connect`}>
            <RouterLink to="/account/assistants/new">Connect an assistant</RouterLink>
          </Button>
        }
      />

      <Banner tone="neutral" className="mb-4" data-testid={`${TEST_ID}.Note`}>
        <span>
          <strong>The assistant is yours, on your machine.</strong> This product never runs code,
          never holds a repository and never calls a model. It hands over a task and records what
          comes back as <em>you, via that assistant</em>.
        </span>
      </Banner>

      <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <DataRegion
          testId={`${TEST_ID}.Region`}
          state={{ ...assistants, refetch: () => void assistants.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            icon: Bot,
            title: 'No assistants connected',
            body: 'Connect one and you get a setup snippet for your own tool. Nothing about this product changes until you do.',
            testId: `${TEST_ID}.Empty`,
          }}
        >
          {(rows) => (
            <Panel
              title="Connected assistants"
              testId={`${TEST_ID}.List`}
              actions={<span className="text-[11.5px] text-ink-4">{rows.length}</span>}
            >
              <div className="flex flex-col gap-3">
                {rows.map((assistant) => (
                  <AssistantRow key={assistant.id} assistant={assistant} />
                ))}
              </div>

              <p className="m-0 mt-3 border-t border-line pt-2.5 text-[11.5px] leading-[1.55] text-ink-4">
                Revoking is immediate and does not undo anything the assistant already did — its
                actions stay in each issue&rsquo;s history, attributed to you, via it.
              </p>
            </Panel>
          )}
        </DataRegion>

        <Panel title="Limits that are not yours to change" testId={`${TEST_ID}.Limits`}>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 text-[12.5px] text-ink-2">
            {[
              'Close an issue it worked on, where the project requires a review to close',
              'Act beyond your own permissions',
              'Reach a project you cannot reach',
            ].map((limit) => (
              <li key={limit} className="flex gap-2" data-testid={`${TEST_ID}.Limits.Item`}>
                <span aria-hidden className="text-[color:var(--color-failed)]">
                  ✕
                </span>
                <span>{limit}</span>
              </li>
            ))}
          </ul>
          <p className="m-0 mt-3 text-[11.5px] leading-[1.55] text-ink-4">
            If your own permissions are reduced tomorrow, its reach shrinks with yours the moment
            that happens — there is no second permission list to keep in step.
          </p>
        </Panel>
      </div>
    </div>
  );
}

function AssistantRow({ assistant }: { assistant: Assistant }) {
  const revoke = useRevokeAssistant();
  const revoked = assistant.revokedAt !== null;

  return (
    <div
      data-testid={`${TEST_ID}.Row`}
      className="flex flex-col gap-2.5 rounded-[8px] border border-line p-3"
    >
      <div className="flex flex-wrap items-center gap-2">
        <Identifier variant="strong" testId={`${TEST_ID}.Row.Name`}>
          {assistant.name}
        </Identifier>
        {/*
          Three states, not two. "Never used" is its own line because it is the one that tells
          somebody the setup did not finish — a credential that was issued and never presented is
          a snippet nobody pasted, and reading it as "idle" hides that.
        */}
        {revoked ? (
          <Badge tone="failed" data-testid={`${TEST_ID}.Row.Revoked`}>
            Revoked
          </Badge>
        ) : assistant.lastUsedAt === null ? (
          <Badge tone="plain">Never used</Badge>
        ) : (
          <Badge tone="passed">Active</Badge>
        )}
        <span className="flex-1" />
        {assistant.lastUsedAt && (
          <span className="text-[11.5px] text-ink-4">
            {`last acted ${formatAge(assistant.lastUsedAt)}`}
          </span>
        )}
        {!revoked && (
          <Button
            size="sm"
            variant="danger"
            disabled={revoke.isPending}
            onClick={() => revoke.mutate(assistant.id)}
            data-testid={`${TEST_ID}.Row.Revoke`}
          >
            Revoke
          </Button>
        )}
      </div>

      <dl className="m-0 grid grid-cols-[minmax(0,6rem)_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[12.5px]">
        <dt className="font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">Scope</dt>
        <dd className="m-0 text-ink-2" data-testid={`${TEST_ID}.Row.Scope`}>
          {assistant.projectKey ?? 'Every project I can see'}
        </dd>
        <dt className="font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">Reach</dt>
        <dd className="m-0 text-ink-2">Whatever you can do, minus closing work you are assigned</dd>
        <dt className="font-mono text-[11px] tracking-[.06em] text-ink-4 uppercase">Connected</dt>
        <dd className="m-0 text-ink-2">{formatDate(assistant.createdAt)}</dd>
      </dl>

      {revoke.isError && (
        <Banner tone="bad" data-testid={`${TEST_ID}.Row.RevokeFailed`}>
          Revoking did not go through. The credential is still live — try again.
        </Banner>
      )}
    </div>
  );
}
