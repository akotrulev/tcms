import { Plus } from 'lucide-react';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useArtifacts, useDocPages, usePlans, useProject } from '../../api/queries';
import { PermissionGate } from '../../components/PermissionGate';
import { RegisterArtifactDialog } from './ArtifactDialogs';
import { ArtifactTable } from './ArtifactTable';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { TabStrip } from '../../components/TabStrip';
import { TABS } from '../../app/nav';
import { Button } from '../../components/ui';

/**
 * The artifact register (plan §6n).
 *
 * Artifacts exist at two scopes — the project's (a test strategy, an environment guide) and a
 * plan's (a release sign-off, an environment snapshot). Plan scope **inherits** project
 * scope, and the inheritance has to be marked, because the alternative is that every release
 * re-uploads its own copy of the same environment guide and they immediately diverge.
 */
export function ArtifactsPage() {
  const { projectId = '' } = useParams();
  const [planId, setPlanId] = useState<string>('');
  const [registering, setRegistering] = useState(false);

  const project = useProject(projectId);
  const plans = usePlans(projectId);
  const artifacts = useArtifacts(projectId, planId ? 'PLAN' : 'PROJECT', planId || undefined);
  // For the register dialog's page picker. Cheap and already cached whenever the reader has
  // visited Documents; the register is where a doc page gets promoted to a project asset.
  const pages = useDocPages(projectId);

  return (
    <div>
      <PageHeader
        testId="Artifacts.Header"
        title="Artifacts"
        documentTitle={project.data ? `Artifacts · ${project.data.name}` : 'Artifacts'}
        subtitle="Strategy, environment, test data and sign-off documents. Plan artifacts sit alongside the project's, which they inherit."
        actions={
          <div className="flex items-center gap-2">
            <select
              value={planId}
              onChange={(event) => setPlanId(event.target.value)}
              aria-label="Which scope to show"
              className="min-w-[260px] rounded-lg border border-line bg-field px-3 py-1.5 text-[13px] text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]"
              data-testid="Artifacts.ScopeSelect"
            >
              <option value="">Project scope only</option>
              {(plans.data ?? []).map((plan) => (
                <option key={plan.id} value={plan.id}>
                  {plan.key} — {plan.name}
                </option>
              ))}
            </select>
            {/*
              Disabled-with-a-reason rather than hidden (§31). ~~The gate is honest only about
              the half it can see: `artifact:edit` is keys-only on the wire, so a Test Engineer —
              who holds it for plan scope alone — gets an ENABLED button here and a refusal on the
              scope field if they choose the project.~~ → **`V52` (§210) de-qualified the key**, so
              the key IS the whole answer now and the button is honest on its own. The dialog still
              renders the scope-shaped 403 — see its header for why a form that can no longer
              produce a refusal must still handle one.
            */}
            <PermissionGate projectId={projectId} permission="artifact:edit">
              {({ allowed }) => (
                <Button
                  variant="primary"
                  disabled={!allowed}
                  onClick={() => setRegistering(true)}
                  data-testid="Artifacts.Register"
                >
                  <Plus aria-hidden className="size-4" />
                  Register
                </Button>
              )}
            </PermissionGate>
          </div>
        }
      />

      {/*
        Pages · Artifacts (021 §1). The register was a rail row under `Project`; it is a document
        the project keeps, so it belongs beside the pages rather than beside the settings screens.
      */}
      <TabStrip tabs={TABS.docs} projectId={projectId} label="Document views" testId="Docs.Tabs" />

      {registering && (
        <RegisterArtifactDialog
          open
          onClose={() => setRegistering(false)}
          projectId={projectId}
          plans={plans.data ?? []}
          pages={pages.data ?? []}
          // Continues what the reader was doing: viewing Release 1's register and pressing
          // Register means Release 1, not "start again and tell us which cycle".
          defaultPlanId={planId || undefined}
          onRegistered={() => undefined}
        />
      )}

      <section data-testid="Artifacts.Panel">
        <DataRegion
          testId="Artifacts.List"
          state={{ ...artifacts, refetch: () => void artifacts.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          /*
            The render's own note on this screen (`renders/01-index-tables.html` frame H) is that
            *empty is a sentence about the product*: the honest answer to "no artifacts" is that you
            cannot make one here — they arrive attached to an execution or an ingested report. The
            body below says that rather than apologising, and it is the whole reason this screen
            keeps an empty state with no upload control in it.
          */
          empty={{
            kind: 'no-records',
            title: 'No artifacts in this project',
            body: planId
              ? 'This plan has no artifacts of its own, and the project has none to inherit.'
              : 'Artifacts are the files a run leaves behind — screenshots, logs, HAR captures. They arrive attached to an execution or an ingested report; there is nothing to upload here.',
          }}
        >
          {(rows) => (
            <ArtifactTable rows={rows} showInheritance={!!planId} projectId={projectId} />
          )}
        </DataRegion>
      </section>
    </div>
  );
}
