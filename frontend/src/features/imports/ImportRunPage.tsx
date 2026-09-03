import { useParams } from 'react-router-dom';

import { useImportRun, useProject } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { Badge, Banner } from '../../components/kit';
import { importProviderLabels, importRunStatusLabels, importRunStatusTones } from '../../lib/labels';
import { ImportStepper } from './ImportStepper';

const TEST_ID = 'ImportRun';

/**
 * `/projects/:projectId/imports/:importRunId` — **the minimal polling page, and T8 replaces its
 * body.**
 *
 * Said plainly rather than left for a reader to infer: this is not `import-report.html`. T7 ends at
 * Confirm, and Confirm navigates here, so shipping T7 without this route would ship a flow whose
 * last click lands on the not-found page. What is here is the run's status, polled while it is
 * `RUNNING`, and nothing else — the dropped-field list, the applied mapping and the people panel
 * are T8's, and drawing an empty version of them now would be three panels claiming a report that
 * has not been built.
 *
 * The one thing it does say in full is the `FAILED` sentence, because that is not decoration: the
 * write is one all-or-nothing transaction after the read pass (D-b), so a run that failed left the
 * project untouched, and a status chip reading only *Failed* invites exactly the question — *what
 * did it do before it failed?* — whose answer is "nothing" and must not be guessed at.
 */
export function ImportRunPage() {
  const { projectId = '', importRunId = '' } = useParams();
  const project = useProject(projectId);
  const run = useImportRun(importRunId);

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Import"
        documentTitle="Import"
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Imports', to: `/projects/${projectId}/imports` },
          { label: 'Run' },
        ]}
      />

      <ImportStepper current={4} testId={`${TEST_ID}.Steps`} />

      <DataRegion testId={`${TEST_ID}.Region`} state={{ ...run, refetch: () => void run.refetch() }}>
        {(current) => (
          <div className="max-w-[720px]">
            <Panel title="Status" testId={`${TEST_ID}.Panel`}>
              <div className="flex flex-wrap items-center gap-2.5">
                <Badge tone={importRunStatusTones[current.status]} data-testid={`${TEST_ID}.Status`}>
                  {importRunStatusLabels[current.status]}
                </Badge>
                <Identifier variant="muted">
                  {`${importProviderLabels[current.provider]} · ${current.sourceProjectKey}`}
                </Identifier>
                {/*
                  `startedBy` is a NULLABLE UserRef — an account that no longer resolves is a run
                  nobody is named for, and the report has to outlive the people in it (D-g).
                */}
                {current.startedBy && (
                  <span className="text-[12px] text-ink-4">
                    started by {current.startedBy.displayName}
                  </span>
                )}
              </div>

              {current.status === 'RUNNING' && (
                <p className="m-0 mt-2.5 text-[12.5px] leading-[1.55] text-ink-2">
                  Reading {current.sourceProjectKey}. Nothing is written until the whole project has
                  been read, so this page is safe to leave and come back to.
                </p>
              )}

              {current.status === 'FAILED' && (
                <div className="mt-2.5" role="alert">
                  <Banner tone="bad" data-testid={`${TEST_ID}.Failure`}>
                    <span>
                      {current.failureReason
                        ?? 'The import did not finish. Nothing was written — this project is exactly as it was.'}
                    </span>
                  </Banner>
                </div>
              )}

              {current.status === 'COMPLETED' && (
                <p className="m-0 mt-2.5 text-[12.5px] leading-[1.55] text-ink-2">
                  {`${current.issuesImported.toLocaleString('en-US')} issues imported.`} The full
                  report — what was dropped, how the statuses were mapped, and who was matched — is
                  the next thing this screen gains.
                </p>
              )}
            </Panel>
          </div>
        )}
      </DataRegion>
    </div>
  );
}
