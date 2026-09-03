import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';

import { useListSourceProjects, usePreviewImport } from '../../api/queries';
import type { ImportConnection, ImportPreview, ImportProvider, SourceProject } from '../../api/types';
import { Panel } from '../../components/PageHeader';
import { Banner } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { formatCount, plural } from '../../lib/format';
import { importProviderLabels } from '../../lib/labels';
import { describeImportError } from './sourceError';
import { needsAccountEmail } from './wizardState';

const TEST_ID = 'ImportConnect';

const PROVIDERS = Object.keys(importProviderLabels) as ImportProvider[];

/**
 * `import-connect.html` — the account, the connection, and which of its projects to read.
 *
 * ## The token help text is the mockup's, and D-a is what makes it true
 *
 * *"Read-only is enough. The token is used for this import and is not stored afterwards."* That
 * sentence was in the mockup before there was a decision behind it, and the decision — the
 * connection is entered per run, carried in the request body, and written to no column — is what
 * turned it from a hope into a fact. `import_run` has no secret column at all, deliberately.
 *
 * **One sentence is added that the mockup has no reason to carry:** *"Reloading this page means
 * entering it again."* It is the visible cost of the same decision, and a person who has typed a
 * 40-character token deserves to know that before the page eats it rather than after.
 *
 * ## The provider selector is a deviation from the mockup, recorded in the plan (D-k)
 *
 * `import-connect.html` is titled *"Import from Jira"* and its form is Jira-shaped. 009's backend
 * line names three providers and all three are built. Under 009's precedence rule the mockup wins
 * on what a screen *shows* — so the form keeps its shape, its copy and its help text — and the plan
 * wins on what the system *does*, so the selector sits above Site with Jira Cloud preselected.
 *
 * ## Two operations, and the second is only reachable through the first
 *
 * *Connect* lists what the credential can see; *Run the preview* reads one of those projects. They
 * are separate because the mockup's *"Connected · 4 projects visible to this account"* note is the
 * only feedback a person gets that the token they pasted is the right one — a single button that
 * did both would report a bad credential as a failed preview, three fields away from the field that
 * is wrong.
 */
export function ConnectStep({
  projectName,
  projectId,
  connection,
  sourceProjects,
  sourceProjectKey,
  onConnectionChange,
  onSourceProjects,
  onSourceProjectKey,
  onPreview,
}: {
  projectName: string;
  projectId: string;
  connection: ImportConnection;
  sourceProjects: SourceProject[] | null;
  sourceProjectKey: string;
  onConnectionChange: (next: ImportConnection) => void;
  onSourceProjects: (projects: SourceProject[] | null) => void;
  onSourceProjectKey: (key: string) => void;
  onPreview: (preview: ImportPreview) => void;
}) {
  const list = useListSourceProjects();
  const preview = usePreviewImport();
  const [refusedField, setRefusedField] = useState<Record<string, string>>({});

  const emailRequired = needsAccountEmail(connection.provider);

  /*
    Validated here rather than through `react-hook-form`, and the reason is the wizard rather than a
    preference: this state is owned one level up because it has to survive the trip to the workflow
    screen and back, and a form library holding a second copy of the same four values is two sources
    of truth for one credential. The refusals below are the only three this form can produce on its
    own; everything else is the source system's own answer.
  */
  const refusals = (): Record<string, string> => {
    const found: Record<string, string> = {};
    if (connection.siteUrl.trim() === '') found.siteUrl = 'Give the address of the site to read.';
    else if (!/^https:\/\//i.test(connection.siteUrl.trim())) {
      found.siteUrl = 'HTTPS only — a token sent over plain HTTP is a token somebody else has.';
    }
    if (emailRequired && (connection.accountEmail ?? '').trim() === '') {
      found.accountEmail = 'Jira Cloud authenticates with the account email beside the token.';
    }
    if (connection.apiToken === '') found.apiToken = 'Paste the API token.';
    return found;
  };

  const connect = () => {
    const found = refusals();
    setRefusedField(found);
    if (Object.keys(found).length > 0) return;
    list.mutate(normalized(connection), {
      onSuccess: (answer) => {
        onSourceProjects(answer.projects);
        // Preselect nothing when there is a choice to make; one project is not a choice.
        onSourceProjectKey(answer.projects.length === 1 ? (answer.projects[0]?.key ?? '') : '');
      },
    });
  };

  const runPreview = () => {
    preview.mutate(
      { projectId, connection: normalized(connection), sourceProjectKey },
      { onSuccess: onPreview },
    );
  };

  const connected = sourceProjects !== null;

  return (
    <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
      <Panel title="Your Jira account" testId={`${TEST_ID}.Form`}>
        <div className="flex max-w-[560px] flex-col gap-3.5">
          <Field
            label="Source system"
            htmlFor={`${TEST_ID}-provider`}
            help="Jira Cloud and Jira Data Center read the same way; Azure DevOps reads work items through the same steps."
          >
            <NativeSelect
              id={`${TEST_ID}-provider`}
              value={connection.provider}
              data-testid={`${TEST_ID}.Provider`}
              onChange={(event) => {
                const provider = event.target.value as ImportProvider;
                /*
                  The project list belongs to the credential that produced it, so changing the
                  provider drops it. Keeping it would offer a picker of projects from a system this
                  form is no longer pointed at. `null` and not `[]` — the second is "connected, and
                  this account can see nothing", which is a real state with its own banner.
                */
                onSourceProjects(null);
                onSourceProjectKey('');
                onConnectionChange({ ...connection, provider });
              }}
            >
              {PROVIDERS.map((provider) => (
                <option key={provider} value={provider}>
                  {importProviderLabels[provider]}
                </option>
              ))}
            </NativeSelect>
          </Field>

          <Field label="Site" htmlFor={`${TEST_ID}-site`} error={refusedField.siteUrl}>
            <Input
              id={`${TEST_ID}-site`}
              className="font-mono"
              placeholder="https://northwind.atlassian.net"
              value={connection.siteUrl}
              data-testid={`${TEST_ID}.Site`}
              onChange={(event) => onConnectionChange({ ...connection, siteUrl: event.target.value })}
            />
          </Field>

          <Field
            label="Account email"
            htmlFor={`${TEST_ID}-email`}
            help={
              emailRequired
                ? undefined
                : `${importProviderLabels[connection.provider]} authenticates with the token alone — this is ignored.`
            }
            error={refusedField.accountEmail}
          >
            <Input
              id={`${TEST_ID}-email`}
              type="email"
              value={connection.accountEmail ?? ''}
              data-testid={`${TEST_ID}.Email`}
              onChange={(event) =>
                onConnectionChange({ ...connection, accountEmail: event.target.value })
              }
            />
          </Field>

          <Field
            label="API token"
            htmlFor={`${TEST_ID}-token`}
            help={
              <>
                Read-only is enough. The token is used for this import and is not stored afterwards.{' '}
                <strong>Reloading this page means entering it again.</strong>
              </>
            }
            error={refusedField.apiToken}
          >
            <Input
              id={`${TEST_ID}-token`}
              type="password"
              className="font-mono"
              autoComplete="off"
              value={connection.apiToken}
              data-testid={`${TEST_ID}.Token`}
              onChange={(event) => onConnectionChange({ ...connection, apiToken: event.target.value })}
            />
          </Field>

          <div className="flex items-center gap-2.5">
            <Button
              size="sm"
              onClick={connect}
              disabled={list.isPending}
              data-testid={`${TEST_ID}.Connect`}
            >
              {list.isPending ? 'Connecting…' : connected ? 'Reconnect' : 'Connect'}
            </Button>
          </div>

          {/*
            The source system's own message, under the fields it is about. `meta.handled` keeps the
            global notification off it: a 502 whose `detail` names a revoked token is strictly more
            useful here than "couldn't load" in a corner of the screen.
          */}
          {list.isError && (
            <Banner tone="bad" data-testid={`${TEST_ID}.ConnectError`}>
              <div className="min-w-0">
                <p className="m-0 text-[13px] font-semibold text-ink">
                  {describeImportError(list.error).title}
                </p>
                <p className="mt-1 mb-0">{describeImportError(list.error).body}</p>
              </div>
            </Banner>
          )}

          {connected && !list.isError && (
            <Banner tone="neutral" data-testid={`${TEST_ID}.Connected`}>
              <span>
                Connected · {plural(sourceProjects.length, 'project')} visible to this account
              </span>
            </Banner>
          )}

          {connected && sourceProjects.length > 0 && (
            <Field
              label={`${importProviderLabels[connection.provider]} project to import`}
              htmlFor={`${TEST_ID}-source`}
              help={
                <>
                  Its issues arrive in <strong>{projectName}</strong>. One source project per import.
                </>
              }
            >
              <NativeSelect
                id={`${TEST_ID}-source`}
                value={sourceProjectKey}
                data-testid={`${TEST_ID}.SourceProject`}
                onChange={(event) => onSourceProjectKey(event.target.value)}
              >
                <option value="">— pick a project —</option>
                {sourceProjects.map((source) => (
                  <option key={source.key} value={source.key}>
                    {`${source.key} · ${source.name} (${formatCount(source.issueCount)} issues)`}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}

          {connected && sourceProjects.length === 0 && (
            <Banner tone="warn" data-testid={`${TEST_ID}.NoProjects`}>
              <span>
                This account can sign in but cannot see any projects. Someone with administrator
                rights in {importProviderLabels[connection.provider]} has to grant it browse access
                before there is anything to import.
              </span>
            </Banner>
          )}

          {preview.isError && (
            <Banner tone="bad" data-testid={`${TEST_ID}.PreviewError`}>
              <div className="min-w-0">
                <p className="m-0 text-[13px] font-semibold text-ink">
                  {describeImportError(preview.error).title}
                </p>
                <p className="mt-1 mb-0">{describeImportError(preview.error).body}</p>
              </div>
            </Banner>
          )}

          <div className="flex items-center gap-2 border-t border-line pt-3">
            <Button size="sm" asChild>
              <RouterLink to={`/projects/${projectId}/imports`} data-testid={`${TEST_ID}.Cancel`}>
                Cancel
              </RouterLink>
            </Button>
            <span className="flex-1" />
            <Button
              size="sm"
              variant="primary"
              onClick={runPreview}
              disabled={sourceProjectKey === '' || preview.isPending}
              data-testid={`${TEST_ID}.RunPreview`}
            >
              {preview.isPending ? 'Reading the source…' : 'Run the preview →'}
            </Button>
          </div>
        </div>
      </Panel>

      {/* The mockup's second panel, verbatim: it is what makes the next click safe to make. */}
      <Panel title="What happens next" testId={`${TEST_ID}.WhatNext`}>
        <div className="flex flex-col gap-[11px] text-[12.5px] leading-[1.6] text-ink-2">
          <p className="m-0">
            The preview <strong>reads</strong>. It writes nothing, and cancelling after it leaves
            this project exactly as it is now.
          </p>
          <p className="m-0">
            You will see counts by type, and every field that will be dropped with how many values
            sit in it — before you are asked to trust anything.
          </p>
        </div>
      </Panel>
    </div>
  );
}

/**
 * The connection as the contract wants it on the wire.
 *
 * `accountEmail` is nullable and ignored by two of the three providers; sending `""` would be a
 * field the person never filled in arriving as an empty value rather than as an absent one, which
 * is `ReleaseNewPage`'s blank-date argument at a second door.
 */
function normalized(connection: ImportConnection): ImportConnection {
  return {
    ...connection,
    siteUrl: connection.siteUrl.trim(),
    accountEmail: (connection.accountEmail ?? '').trim() || null,
  };
}
