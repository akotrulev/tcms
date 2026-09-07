import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';

import { keys, useProject, useProjectWorkflow, useStartImport } from '../../api/queries';
import type { ImportPreview, StatusMappingEntry } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { Banner } from '../../components/kit';
import { ConnectStep } from './ConnectStep';
import { ImportStepper } from './ImportStepper';
import { MappingStep } from './MappingStep';
import { PreviewStep } from './PreviewStep';
import { describeImportError } from './sourceError';
import {
  defaultPreserveBacklogOrder,
  initialWizardState,
  mappingFrom,
  type WizardState,
} from './wizardState';

const TEST_ID = 'ImportWizard';

/** Where the wizard's state rides while the person is over on the workflow screen. */
interface WizardLocationState {
  wizard?: WizardState;
}

/**
 * `/projects/:projectId/imports/new` — connect → preview → map → confirm.
 *
 * ## This component owns the state, including the token, and nothing else does
 *
 * The three steps are presentational: they take values and hand back changes. The reason is not
 * tidiness — it is that the state has to survive a navigation to `settings-workflow.html` and back,
 * and a value scattered across three components cannot be written into a history entry as one
 * thing. `wizardState.ts` says where it may and may not live; the short version is that the token
 * is in React state and in `history.state`, both of which the tab discards, and nowhere else.
 *
 * ## The trip to the workflow screen, and why it is two navigations
 *
 * `import-mapping.html` promises *"the mapping picks it up on return"*. Getting that right needs
 * the state to be attached to **the entry the browser's Back button returns to**, which is this
 * one — so the handler first `replace`s the current entry with the state on it, then pushes the
 * workflow route. Coming back restores from `location.state` and invalidates the workflow query, so
 * a status added over there is in every picker here.
 *
 * A reload is a different thing and is not recoverable: `history.state` survives it but the token
 * in it should not, so the restore is keyed on a live navigation rather than on the entry existing.
 * The connect step says so before the token field.
 *
 * ## Confirm lands on the report, which polls itself
 *
 * Confirm posts with an `Idempotency-Key` and goes to `/projects/:projectId/imports/:importRunId`
 * — `ImportReportPage`, which arrives on a `RUNNING` run, shows progress, and redraws itself as the
 * report when the poll comes back `COMPLETED`. The wizard does not wait for the run: the read pass
 * against the source takes minutes for a real project, and a fourth step that spun until it
 * finished would be a tab somebody has to keep open.
 */
export function ImportWizardPage() {
  const { projectId = '' } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const client = useQueryClient();

  const project = useProject(projectId);
  const workflow = useProjectWorkflow(projectId);
  const start = useStartImport(projectId);

  const restored = (location.state as WizardLocationState | null)?.wizard;
  const [state, setState] = useState<WizardState>(() => restored ?? initialWizardState());

  /*
    Returning from the workflow screen. The state arrives on the history entry, and the workflow the
    mapping step draws its pickers from is one status out of date — so it is invalidated here rather
    than being left to a window refocus that may never come. `useRef` guards the pair against
    re-running on every render while `location.state` keeps its value.
  */
  const restoredFrom = useRef<WizardState | undefined>(restored);
  useEffect(() => {
    if (restored === undefined || restored === restoredFrom.current) return;
    restoredFrom.current = restored;
    setState(restored);
    void client.invalidateQueries({ queryKey: keys.workflow(projectId) });
  }, [restored, client, projectId]);

  const projectName = project.data?.name ?? 'this project';

  const stepNumber = state.step === 'connect' ? 1 : state.step === 'preview' ? 2 : 3;

  const acceptPreview = (preview: ImportPreview) => {
    setState((current) => ({
      ...current,
      preview,
      mapping: mappingFrom(preview),
      options: {
        ...current.options,
        preserveBacklogOrder: defaultPreserveBacklogOrder(current.connection.provider),
      },
      step: 'preview',
    }));
  };

  const editWorkflow = () => {
    // Two navigations, in this order. See the header: the state must land on the entry Back
    // returns to, which is this one, and only then may the workflow route be pushed on top.
    navigate(location.pathname, { replace: true, state: { wizard: state } });
    navigate(`/projects/${projectId}/settings/workflow`);
  };

  const confirm = () => {
    const preview = state.preview;
    if (!preview) return;
    const statusMapping: StatusMappingEntry[] = preview.statuses.map((row) => ({
      sourceStatusName: row.sourceStatusName,
      targetStatusId: state.mapping[row.sourceStatusName] ?? '',
    }));
    start.mutate(
      {
        projectId,
        connection: state.connection,
        sourceProjectKey: state.sourceProjectKey,
        statusMapping,
        options: state.options,
      },
      {
        onSuccess: (run) => navigate(`/projects/${projectId}/imports/${run.id}`),
      },
    );
  };

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="Import from another tracker"
        documentTitle="Import"
        subtitle="Four steps, and nothing is written before the third one"
      />

      <ImportStepper current={stepNumber} testId={`${TEST_ID}.Steps`} />

      {start.isError && (
        <div role="alert" className="mb-4 max-w-[1180px]">
          <Banner tone="bad" data-testid={`${TEST_ID}.StartError`}>
            <div className="min-w-0">
              <p className="m-0 text-[13px] font-semibold text-ink">
                {describeImportError(start.error).title}
              </p>
              <p className="mt-1 mb-0">{describeImportError(start.error).body}</p>
            </div>
          </Banner>
        </div>
      )}

      {state.step === 'connect' && (
        <ConnectStep
          projectId={projectId}
          projectName={projectName}
          connection={state.connection}
          sourceProjects={state.sourceProjects}
          sourceProjectKey={state.sourceProjectKey}
          onConnectionChange={(connection) => setState((c) => ({ ...c, connection }))}
          onSourceProjects={(sourceProjects) => setState((c) => ({ ...c, sourceProjects }))}
          onSourceProjectKey={(sourceProjectKey) => setState((c) => ({ ...c, sourceProjectKey }))}
          onPreview={acceptPreview}
        />
      )}

      {state.step === 'preview' && state.preview && (
        <PreviewStep
          projectId={projectId}
          projectName={projectName}
          preview={state.preview}
          onBack={() => setState((c) => ({ ...c, step: 'connect' }))}
          onContinue={() => setState((c) => ({ ...c, step: 'mapping' }))}
        />
      )}

      {state.step === 'mapping' && state.preview && (
        /*
          The workflow is a read, so it gets a region of its own rather than being awaited by the
          page: a failure here must say the pickers could not load, not blank the mapping a person
          has already half-made.
        */
        <DataRegion
          testId={`${TEST_ID}.Workflow`}
          state={{ ...workflow, refetch: () => void workflow.refetch() }}
        >
          {(current) => (
            <MappingStep
              projectId={projectId}
              preview={state.preview as ImportPreview}
              workflow={current}
              mapping={state.mapping}
              options={state.options}
              confirming={start.isPending}
              onMap={(sourceStatusName, targetStatusId) =>
                setState((c) => ({
                  ...c,
                  mapping: { ...c.mapping, [sourceStatusName]: targetStatusId },
                }))
              }
              onOptions={(options) => setState((c) => ({ ...c, options }))}
              onBack={() => setState((c) => ({ ...c, step: 'preview' }))}
              onConfirm={confirm}
              onEditWorkflow={editWorkflow}
            />
          )}
        </DataRegion>
      )}
    </div>
  );
}
