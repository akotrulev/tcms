import { CornerDownRight, Pencil, Plus, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useClassification, useProject, useRetireClassificationValue } from '../../api/queries';
import { ApiError } from '../../api/client';
import type { ClassificationDimension, ClassificationValue, PermissionKey } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { SettingsSubnav } from './SettingsSubnav';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { buildValueTree, type ClassificationValueNode } from '../../lib/classification';
import { ClassificationValueDialog } from './ClassificationValueDialog';

/**
 * The classification vocabulary editor — **the screen that makes a provisioned project's Feature
 * tree fillable.**
 *
 * `tcms_seed_project_defaults` creates the Feature dimension with no values in it on purpose
 * ("inventing a client's feature taxonomy for them is how a controlled vocabulary gets worked
 * around"), and until this page existed there was no way to put any in: handoff §68 built the case
 * editor's tree picker against a dimension nothing could fill, and said so on its way out.
 *
 * ## One page, two permissions, and the flag that decides which
 *
 * `GET /classification` returns the company's dimensions and the project's own in one array, and
 * `locked` is what tells them apart. It is **not** "read-only" — that reading made
 * `company:vocabulary` a permission covering nothing — it means the dimension belongs to the
 * company, so its values are edited by a Company Admin at company scope while the project's own
 * Feature tree is edited by a Test Lead here. So every gated control on this page reads the
 * dimension's flag to choose which question to ask, and both branches are ordinary
 * disable-don't-hide (§31): a Test Lead sees the company's axes with their controls greyed and a
 * tooltip saying why, rather than not seeing that the axes exist.
 *
 * ## Why the whole vocabulary and not just Feature
 *
 * Feature is the only dimension anyone needs to fill today, and a page that showed only Feature
 * would be a page that quietly disagrees with the picker beside it — the case editor offers four
 * axes, and a settings screen listing one implies the other three are not configurable rather than
 * not *yours*. Showing all four with the right control state says the true thing.
 *
 * ## Why every dimension is on screen at once (007 T15)
 *
 * The render draws this as a left rail of dimensions and one pane (`07-settings-admin.html`, frame
 * B). It is not ported that way, and the reason is the paragraph above: the point of this screen is
 * that **one caller sees two different answers on one page** — a Test Lead's own Feature tree
 * editable beside the company's axes greyed. A rail that shows one dimension at a time hides the
 * comparison the screen exists to make. What the render's frame is really about is its empty
 * state, and that is ported exactly, typical values and all.
 */
export function ClassificationSettingsPage() {
  const { projectId = '' } = useParams();

  const project = useProject(projectId);
  const classification = useClassification(projectId);

  const [adding, setAdding] = useState<{
    dimension: ClassificationDimension;
    parent: ClassificationValue | null;
  } | null>(null);
  const [editing, setEditing] = useState<{
    dimension: ClassificationDimension;
    value: ClassificationValue;
  } | null>(null);
  const [retiring, setRetiring] = useState<ClassificationValue | null>(null);

  return (
    <div>
      <PageHeader
        views={<SettingsSubnav projectId={projectId} />}
        testId="Classification.Header"
        title="Classification"
        documentTitle={
          project.data ? `Classification · ${project.data.name}` : 'Classification'
        }
        subtitle="The axes a test case is classified on. Feature belongs to this project; Test Type, Test Level and Execution Phase are shared by every project in the company."
      />

      <DataRegion
        testId="Classification.Dimensions"
        state={{
          ...classification,
          refetch: () => void classification.refetch(),
        }}
        isEmpty={(dimensions) => dimensions.length === 0}
        empty={{
          kind: 'no-records',
          title: 'No classification dimensions',
          // Not a failure and not a state anyone can fix from here — which is why it says what
          // produced it. An empty array is a valid answer meaning "this company was provisioned
          // before the vocabulary existed", and a generic "no data" would read as a broken screen.
          body: 'This project has no classification axes. Re-running provision-company for this company adds them.',
        }}
      >
        {(dimensions) => (
          <div className="flex max-w-[960px] flex-col gap-7">
            {dimensions.map((dimension) => (
              <DimensionPanel
                key={dimension.id}
                projectId={projectId}
                dimension={dimension}
                onAdd={(parent) => setAdding({ dimension, parent })}
                onEdit={(value) => setEditing({ dimension, value })}
                onRetire={setRetiring}
              />
            ))}
          </div>
        )}
      </DataRegion>

      {/* Conditionally mounted, like every other form in this app: a dialog behind `open={false}`
          keeps one instance — and therefore one set of field state and one mutation result —
          across every use of it (§54). Here it would also mean the name box still holding the
          previous value's name. */}
      {adding && (
        <ClassificationValueDialog
          projectId={projectId}
          dimension={adding.dimension}
          parent={adding.parent}
          value={null}
          onClose={() => setAdding(null)}
        />
      )}
      {editing && (
        <ClassificationValueDialog
          projectId={projectId}
          dimension={editing.dimension}
          parent={null}
          value={editing.value}
          onClose={() => setEditing(null)}
        />
      )}
      {retiring && (
        <RetireValueDialog
          projectId={projectId}
          value={retiring}
          onClose={() => setRetiring(null)}
        />
      )}
    </div>
  );
}

/**
 * Which permission edits this dimension's values, and at which scope.
 *
 * The client half of `ClassificationValueWriteService.permissionFor`, and it reads `locked`
 * because that is the only thing on the wire that says whose the dimension is — the server reads
 * `project_id`, which is deliberately not serialized (a consumer picking values has no use for it,
 * and it is the field a future author would try to *send*).
 */
function gateFor(dimension: ClassificationDimension): {
  projectId: string | 'company';
  permission: PermissionKey;
} {
  return dimension.locked
    ? { projectId: 'company', permission: 'company:vocabulary' }
    : { projectId: '', permission: 'project:settings' };
}

function DimensionPanel({
  projectId,
  dimension,
  onAdd,
  onEdit,
  onRetire,
}: {
  projectId: string;
  dimension: ClassificationDimension;
  onAdd: (parent: ClassificationValue | null) => void;
  onEdit: (value: ClassificationValue) => void;
  onRetire: (value: ClassificationValue) => void;
}) {
  const gate = gateFor(dimension);
  const gateProjectId = gate.projectId === 'company' ? 'company' : projectId;

  return (
    <section data-testid={`Classification.${dimension.key}`}>
      <div className="mb-2 flex flex-wrap items-center gap-2.5 border-b border-line pb-2">
        <h2 className="font-mono text-[11px] font-semibold uppercase tracking-[.08em] text-ink-3">
          {dimension.name}
        </h2>
        {/* Whose vocabulary this is — state the system owns, so a badge and not a chip. */}
        <Badge tone="plain" data-testid={`Classification.${dimension.key}.Scope`}>
          {dimension.locked ? 'Company-wide' : 'This project'}
        </Badge>
        <PermissionGate projectId={gateProjectId} permission={gate.permission}>
          {({ allowed }) => (
            <Button
              size="sm"
              className="ml-auto"
              disabled={!allowed}
              onClick={() => onAdd(null)}
              data-testid={`Classification.${dimension.key}.Add`}
            >
              <Plus aria-hidden className="size-3.5" />
              Add value
            </Button>
          )}
        </PermissionGate>
      </div>

      {dimension.values.length === 0 ? (
        <div className="max-w-[60ch] py-4" data-testid={`Classification.${dimension.key}.Empty`}>
          <p className="text-[13px] leading-[1.6] text-ink-3">
            {dimension.hierarchical
              ? 'No features yet. Add the top level of your product’s taxonomy, then add underneath it.'
              : 'No values yet.'}{' '}
            {/*
              The sentence the render's frame B is about: a dimension with no values is not offered
              anywhere — the case editor shows no control for it and the case detail shows no row.
              Nothing is broken; there is simply nothing to choose from yet. It is said here as
              well as in the editor deliberately, and it is the only sentence in the product that
              appears twice on purpose.
            */}
            A dimension with no values is not offered anywhere — the case editor shows no control
            for it and a case detail page shows no row. Nothing is broken; there is nothing to
            choose from yet.
          </p>
        </div>
      ) : (
        <ul className="m-0 flex list-none flex-col p-0">
          {buildValueTree(dimension.values).map((node) => (
            <ValueRow
              key={node.value.id}
              node={node}
              depth={0}
              dimension={dimension}
              gateProjectId={gateProjectId}
              permission={gate.permission}
              onAdd={onAdd}
              onEdit={onEdit}
              onRetire={onRetire}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

/**
 * The row locator, and why it holds no id.
 *
 * ~~A value's `data-testid` interpolated its database UUID (`Classification.Value.<uuid>`).~~
 * → a locator that changes with every seed, environment and re-provision is one an E2E suite
 * cannot write down, which is what the testid convention's static-row rule exists to prevent. The
 * row locator is now the same string for every row; a test picks the row it means through the
 * `aria-label` each control already carries (*"Retire Refunds"*), or by index over the set.
 */
const ValueRowTestId = 'Classification.Value';

/**
 * One value and everything under it.
 *
 * The tree comes from `buildValueTree`, which surfaces orphans **and the members of a `parentId`
 * cycle** at the root rather than dropping them — the same property the picker depends on, and it
 * matters here for the mirror-image reason: a value nothing renders is one nobody can retire.
 *
 * "Add underneath" appears only on a hierarchical dimension. On a flat one the server would accept
 * a parent — nothing in the schema forbids it — and the product has no way to show the result, so
 * the control that would create it does not exist.
 *
 * The three controls are quiet at rest and named for a screen reader, which is the pair of rules
 * an icon-only button has to satisfy at once: `aria-label` carries the value's name, so a reader
 * hears *"Retire Refunds"* rather than eleven buttons all called "Retire".
 */
function ValueRow({
  node,
  depth,
  dimension,
  gateProjectId,
  permission,
  onAdd,
  onEdit,
  onRetire,
}: {
  node: ClassificationValueNode;
  depth: number;
  dimension: ClassificationDimension;
  gateProjectId: string | 'company';
  permission: PermissionKey;
  onAdd: (parent: ClassificationValue | null) => void;
  onEdit: (value: ClassificationValue) => void;
  onRetire: (value: ClassificationValue) => void;
}) {
  const { value } = node;

  return (
    <li className="list-none">
      <div
        style={{ paddingLeft: depth * 22 }}
        className="group flex items-center gap-2 rounded-md py-1.5 pr-1 text-[13px] hover:bg-wash-1"
        data-testid={ValueRowTestId}
      >
        {depth > 0 && <CornerDownRight aria-hidden className="size-3.5 shrink-0 text-ink-4" />}
        {value.color && (
          <span
            aria-hidden
            style={{ backgroundColor: value.color }}
            className="block size-3 shrink-0 rounded-full border border-line-strong"
          />
        )}
        <span className="min-w-0 flex-1 truncate text-ink-2">{value.name}</span>

        <PermissionGate projectId={gateProjectId} permission={permission}>
          {({ allowed }) => (
            <span className="flex shrink-0 gap-0.5">
              {dimension.hierarchical && (
                <Button
                  size="icon"
                  variant="quiet"
                  disabled={!allowed}
                  onClick={() => onAdd(value)}
                  title="Add underneath"
                  aria-label={`Add a value under ${value.name}`}
                  data-testid={`${ValueRowTestId}.AddChild`}
                >
                  <Plus aria-hidden className="size-4" />
                </Button>
              )}
              <Button
                size="icon"
                variant="quiet"
                disabled={!allowed}
                onClick={() => onEdit(value)}
                title="Rename"
                aria-label={`Rename ${value.name}`}
                data-testid={`${ValueRowTestId}.Edit`}
              >
                <Pencil aria-hidden className="size-4" />
              </Button>
              <Button
                size="icon"
                variant="quiet"
                disabled={!allowed}
                onClick={() => onRetire(value)}
                title="Retire"
                aria-label={`Retire ${value.name}`}
                data-testid={`${ValueRowTestId}.Retire`}
              >
                <Trash2 aria-hidden className="size-4" />
              </Button>
            </span>
          )}
        </PermissionGate>
      </div>

      {node.children.length > 0 && (
        <ul className="m-0 flex list-none flex-col p-0">
          {node.children.map((child) => (
            <ValueRow
              key={child.value.id}
              node={child}
              depth={depth + 1}
              dimension={dimension}
              gateProjectId={gateProjectId}
              permission={permission}
              onAdd={onAdd}
              onEdit={onEdit}
              onRetire={onRetire}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

/**
 * Confirming a retire — and rendering the one refusal that is not a field error.
 *
 * A value with live children answers **409 with a sentence on `errors.children`**, rendered as it
 * arrives — the server counted, and a second phrasing here is a copy that drifts (`DeleteTeamDialog`,
 * same shape, same key). ~~The 409 carries the bare count and this dialog composes the sentence~~ →
 * **superseded §149**: an `errors` value is a message every client prints verbatim, and the CLI and
 * the MCP server were printing `children: 3` at a caller who cannot see this screen.
 *
 * This dialog stays open to show it. That is why there is no optimistic removal anywhere in this
 * flow: taking the row off screen and then putting it back with an error beside it is the worst
 * possible treatment of the one action whose failure the user most needs to read.
 *
 * The copy says what a retire *is*, because the word does not carry it: cases already classified
 * with the value keep it in their history and past runs still resolve it. The row survives; the
 * vocabulary simply stops offering it — which is rule 7, *disable, don't hide*, at the vocabulary.
 */
function RetireValueDialog({
  projectId,
  value,
  onClose,
}: {
  projectId: string;
  value: ClassificationValue;
  onClose: () => void;
}) {
  const retire = useRetireClassificationValue(projectId);
  const blocked =
    retire.error instanceof ApiError ? retire.error.fieldErrors?.children?.[0] : undefined;

  return (
    <Dialog open onOpenChange={(next) => !next && onClose()}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>Retire {value.name}?</DialogTitle>
          {!blocked && !retire.error && (
            <DialogDescription>
              It stops being offered when classifying a case. Cases already classified with it keep
              it, and past runs still resolve it — nothing is deleted.
            </DialogDescription>
          )}
        </DialogHeader>

        {blocked ? (
          <Banner tone="warn" className="mt-3" data-testid="Classification.Retire.Blocked">
            <span>{blocked}</span>
          </Banner>
        ) : retire.error ? (
          <Banner tone="bad" className="mt-3" data-testid="Classification.Retire.Error">
            <span>
              {retire.error instanceof ApiError && retire.error.status === 403
                ? 'You do not have permission to edit this vocabulary.'
                : 'That did not work. Try again.'}
            </span>
          </Banner>
        ) : null}

        <DialogFooter>
          <Button onClick={onClose} data-testid="Classification.Retire.Cancel">
            Cancel
          </Button>
          <Button
            variant="danger"
            disabled={retire.isPending}
            onClick={() => retire.mutate(value.id, { onSuccess: () => onClose() })}
            data-testid="Classification.Retire.Confirm"
          >
            Retire
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
