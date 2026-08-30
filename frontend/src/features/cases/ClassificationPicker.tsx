import { ChevronDown } from 'lucide-react';
import { useMemo, useRef, type ReactNode } from 'react';

import type { ClassificationDimension, ClassificationValue } from '../../api/types';
import { buildValueTree, type ClassificationValueNode } from '../../lib/classification';
import { cn } from '../../lib/cn';

/**
 * Choosing a case's classification — all four dimensions, one control shape per dimension.
 *
 * ## The shape is derived from the dimension, not hard-coded per key
 *
 * A project's dimensions are data: `cardinality` and `hierarchical` come off the wire, and a client
 * may add a fifth dimension tomorrow. So this renders from those two flags rather than from a
 * `switch` on `test_type` / `feature`, and a new dimension gets a sensible control without anyone
 * editing this file:
 *
 * | cardinality | hierarchical | control |
 * |---|---|---|
 * | SINGLE | either | a rail row that opens a listbox, with a "None" option — a case need not be classified |
 * | MULTI | either | a flat list of checkboxes, indented where the vocabulary has depth |
 *
 * ~~`SINGLE + hierarchical` is rendered as a tree of **radios**.~~ → superseded by 007 T13: the
 * two hierarchical shapes collapse into the flat ones, because **a classification vocabulary is a
 * flat list per dimension with one level of parentage**, not a tree — which is all the `SimpleTreeView`
 * this file used to import was ever carrying. Depth survives as an indent, so parentage is still
 * legible; what goes is a whole tree widget, its expansion state and its keyboard model, for one
 * level of nesting. The MUI tree-view package is imported by no file in the app as of that task.
 *
 * The hierarchical SINGLE case also loses its one genuinely awkward affordance with it: a radio
 * group cannot be un-chosen by clicking the chosen one, so it was wired as a toggle. The listbox
 * has a "None" option, which is the same escape hatch the flat SINGLE control always had.
 *
 * ## Cardinality is enforced by the control, not by validation
 *
 * A SINGLE dimension is a listbox that takes one value, so a second value cannot be chosen. That is
 * deliberate: the alternative is a checkbox list plus a Zod refinement, which lets someone tick two
 * boxes and then tells them off for it. The server still checks — `curl` runs no React — and V2's
 * trigger still checks under that; this is the layer that makes the rule feel like a shape rather
 * than a rejection.
 *
 * ## An empty vocabulary renders NO control (003 Phase 1, finding 3)
 *
 * ~~An empty Feature tree renders its name and *"Nothing to choose from yet — this vocabulary is
 * empty"*.~~ → **superseded by 003 Phase 1.** A freshly provisioned project has four dimensions and
 * no values in any of them, so the old treatment drew four labelled controls that could not be
 * used, one caption each, in a 320px rail — four copies of one fact, and four affordances for
 * nothing. A dimension with no values now renders nothing at all, and the page is told which ones
 * they were through {@link ClassificationPicker}'s `emptyNotice` slot, so **one** sentence and
 * **one** link to Classification stand for all of them together.
 *
 * The slot rather than the sentence: the picker is a control and does not know the route to
 * project settings, and a link rendered from here would be the second place this app decides where
 * Classification lives. It reports the fact; the page says what to do about it.
 *
 * ## Parents are selectable
 *
 * The Feature list offers a parent exactly as it offers a leaf. The editor previously rendered
 * features read-only on the stated grounds that a flat select "would let someone set a parent where
 * a leaf is required" — **no such rule exists** in the plan, the ERD, the roles spec or `V1`, and
 * §6q's rule that a feature filter includes descendants is about reading.
 */

export function ClassificationPicker({
  dimensions,
  selected,
  onChange,
  disabled,
  testIdPrefix,
  emptyNotice,
}: {
  dimensions: ClassificationDimension[];
  /** Every selected value id across all dimensions — the flat shape the contract takes. */
  selected: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
  testIdPrefix: string;
  /**
   * What to say about the dimensions this project has configured **no values for**, rendered once
   * under whatever controls remain.
   *
   * A render prop taking the empty dimensions, not a boolean and not a node: the page composes the
   * sentence — it is the side that knows where Classification lives and may name the dimensions —
   * while the picker keeps the one thing it knows, which is *which* of them are empty. Called only
   * when there is at least one, so a caller need not check.
   */
  emptyNotice?: (empty: ClassificationDimension[]) => ReactNode;
}) {
  const chosen = useMemo(() => new Set(selected), [selected]);
  /*
    Split rather than filtered in place, because both halves are rendered: the usable dimensions
    become controls and the empty ones become the caller's one notice. Deriving the second list at
    the render site instead would put the same `values.length === 0` test in two files, and the
    copy that drifts is whichever is edited second.
  */
  const usable = dimensions.filter((dimension) => dimension.values.length > 0);
  const empty = dimensions.filter((dimension) => dimension.values.length === 0);

  /**
   * Replaces this dimension's selection while leaving every other dimension's alone.
   *
   * The array is flat and shared across dimensions, so a control that simply set its own values
   * would drop the rest. Rebuilding as "everything not from this dimension, plus this dimension's
   * new choice" is what keeps four independent controls editing one field.
   */
  const replaceWithin = (dimension: ClassificationDimension, next: string[]) => {
    const mine = new Set(dimension.values.map((value) => value.id));
    onChange([...selected.filter((id) => !mine.has(id)), ...next]);
  };

  const toggle = (dimension: ClassificationDimension, valueId: string, on: boolean) => {
    const mine = dimension.values.map((value) => value.id).filter((id) => chosen.has(id));
    replaceWithin(dimension, on ? [...mine, valueId] : mine.filter((id) => id !== valueId));
  };

  if (dimensions.length === 0) {
    return <p className="m-0 text-[13px] text-ink-3">This project has no classification dimensions yet.</p>;
  }

  return (
    <div className="flex flex-col gap-5" data-testid={`${testIdPrefix}.Classification`}>
      {usable.map((dimension) => (
        <DimensionControl
          key={dimension.id}
          dimension={dimension}
          chosen={chosen}
          disabled={disabled}
          testIdPrefix={testIdPrefix}
          onSelectOne={(valueId) => replaceWithin(dimension, valueId ? [valueId] : [])}
          onToggle={(valueId, on) => toggle(dimension, valueId, on)}
        />
      ))}
      {empty.length > 0 && emptyNotice ? (
        <div data-testid={`${testIdPrefix}.Classification.EmptyNotice`}>{emptyNotice(empty)}</div>
      ) : null}
    </div>
  );
}

/** One dimension's values, depth-first, carrying the indent a hierarchy earns. */
interface FlatValue {
  value: ClassificationValue;
  depth: number;
}

function flatten(nodes: ClassificationValueNode[], depth = 0): FlatValue[] {
  return nodes.flatMap((node) => [{ value: node.value, depth }, ...flatten(node.children, depth + 1)]);
}

function DimensionControl({
  dimension,
  chosen,
  disabled,
  testIdPrefix,
  onSelectOne,
  onToggle,
}: {
  dimension: ClassificationDimension;
  chosen: Set<string>;
  disabled?: boolean;
  testIdPrefix: string;
  onSelectOne: (valueId: string | null) => void;
  onToggle: (valueId: string, on: boolean) => void;
}) {
  const values = useMemo(() => flatten(buildValueTree(dimension.values)), [dimension.values]);

  /*
    The dimension is the named parent region, keyed on its stable slug; every value under it shares
    the one row locator `${testId}.Value`.

    ~~Values carried their database UUID, and in two spellings — hierarchical ones addressed
    globally as `Classification.Value.<id>`, flat ones under their dimension.~~ → both are gone.
    The two shapes only existed because a UUID is globally unique, and a UUID is precisely what a
    locator must not contain: it changes with every seed and re-provision, so no E2E suite can
    write it down. A test reaches the value it means by the checkbox's accessible name, or by
    index within this region.
  */
  const testId = `${testIdPrefix}.Dimension.${dimension.key}`;

  if (dimension.cardinality === 'SINGLE') {
    return (
      <SingleValue
        dimension={dimension}
        values={values}
        chosen={chosen}
        disabled={disabled}
        testId={testId}
        onSelectOne={onSelectOne}
      />
    );
  }

  return (
    <fieldset disabled={disabled} data-testid={testId} className="m-0 min-w-0 border-0 p-0">
      <legend className="mb-1.5 p-0 font-mono text-[11px] uppercase tracking-[.06em] text-ink-4">
        {dimension.name}
      </legend>
      <div className="flex flex-col">
        {values.map(({ value, depth }) => (
          <label
            key={value.id}
            style={depth > 0 ? { paddingLeft: depth * 16 } : undefined}
            className="flex cursor-pointer items-center gap-2 rounded-[5px] py-1 text-[13px] text-ink-2 hover:bg-wash-1"
          >
            {/*
              A native input rather than `components/ui/checkbox`, and it is the one place in the
              port that does not take the kit primitive. That primitive is a Radix `<button>`, and a
              button is only reached from a wrapping `<label>` through the label's activation
              behaviour — which is exactly the path a test driving the control directly also
              travels, so the row would fire twice or not at all depending on how faithfully the
              environment implements it. A native checkbox is the labeled control of its own label
              by construction, and `accent-color` carries the kit's steel with no widget to build.
            */}
            <input
              type="checkbox"
              checked={chosen.has(value.id)}
              onChange={(event) => onToggle(value.id, event.target.checked)}
              data-testid={`${testId}.Value`}
              className={cn(
                'size-4 shrink-0 cursor-pointer rounded-sm accent-[color:var(--color-steel)]',
                'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
                'disabled:cursor-not-allowed disabled:opacity-45',
              )}
            />
            <span>{value.name}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

/**
 * A SINGLE dimension: the rail row of design language rule 3, opening a listbox of the whole
 * vocabulary.
 *
 * **A `<details>` rather than `components/kit/RailRow` plus state**, for two reasons that pull the
 * same way. A native disclosure reports its own expanded state to a screen reader, and `RailRow`'s
 * props cannot carry the `aria-expanded` a hand-rolled one would need — it is a button whose
 * `onOpen` presumes the caller owns the popup. And a disclosure with no JavaScript state is a
 * control with no way to disagree with itself about whether it is open. The summary is styled to
 * the same rule the kit row carries: quiet at rest, bordered on hover, focus-ringed, value right.
 */
function SingleValue({
  dimension,
  values,
  chosen,
  disabled,
  testId,
  onSelectOne,
}: {
  dimension: ClassificationDimension;
  values: FlatValue[];
  chosen: Set<string>;
  disabled?: boolean;
  testId: string;
  onSelectOne: (valueId: string | null) => void;
}) {
  const details = useRef<HTMLDetailsElement>(null);
  const current = values.find(({ value }) => chosen.has(value.id));

  const choose = (valueId: string | null) => {
    onSelectOne(valueId);
    if (details.current) details.current.open = false;
  };

  const ROW =
    'flex w-full items-center justify-between gap-2.5 rounded-[5px] py-1 text-[13px] text-left min-h-7';

  if (disabled) {
    return (
      <div className={cn(ROW, 'opacity-[.72]')} data-testid={testId}>
        <span className="shrink-0 text-ink-3">{dimension.name}</span>
        <span className="text-right text-ink">{current?.value.name ?? 'None'}</span>
      </div>
    );
  }

  return (
    <details ref={details} className="min-w-0">
      <summary
        data-testid={testId}
        className={cn(
          ROW,
          '-mx-2 cursor-pointer list-none border border-transparent px-2',
          'hover:border-line-strong hover:bg-wash-1',
          'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]',
          '[&::-webkit-details-marker]:hidden',
        )}
      >
        <span className="shrink-0 text-ink-3">{dimension.name}</span>
        <span className="flex items-center gap-1.5 text-right text-ink">
          {current?.value.name ?? 'None'}
          <ChevronDown aria-hidden className="size-3 shrink-0 text-ink-4" />
        </span>
      </summary>

      <div
        role="listbox"
        aria-label={dimension.name}
        className="mt-1 flex max-h-56 flex-col overflow-auto rounded-[5px] border border-line bg-panel p-1"
      >
        {/* A case need not be classified — `test_case` has no NOT NULL for any of this, and a
            picker with no way back to "unset" makes the first accidental choice permanent. */}
        <Option selected={!current} onChoose={() => choose(null)} testId={`${testId}.None`}>
          <em className="not-italic text-ink-4">None</em>
        </Option>
        {values.map(({ value, depth }) => (
          <Option
            key={value.id}
            selected={chosen.has(value.id)}
            depth={depth}
            onChoose={() => choose(value.id)}
            testId={`${testId}.Value`}
          >
            {value.name}
          </Option>
        ))}
      </div>
    </details>
  );
}

function Option({
  selected,
  depth = 0,
  onChoose,
  testId,
  children,
}: {
  selected: boolean;
  depth?: number;
  onChoose: () => void;
  testId: string;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      onClick={onChoose}
      data-testid={testId}
      style={depth > 0 ? { paddingLeft: 8 + depth * 16 } : undefined}
      className={cn(
        'cursor-pointer rounded-sm border-0 bg-transparent px-2 py-1 text-left text-[13px]',
        'hover:bg-wash-2 focus-visible:outline-2 focus-visible:outline-offset-[-2px]',
        'focus-visible:outline-[color:var(--color-given)]',
        selected ? 'text-steel' : 'text-ink-2',
      )}
    >
      {children}
    </button>
  );
}
