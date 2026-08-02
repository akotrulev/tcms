import {
  Checkbox,
  FormControl,
  FormControlLabel,
  FormLabel,
  MenuItem,
  Radio,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { SimpleTreeView } from '@mui/x-tree-view/SimpleTreeView';
import { TreeItem } from '@mui/x-tree-view/TreeItem';
import { useMemo } from 'react';

import type { ClassificationDimension } from '../../api/types';
import { buildValueTree, type ClassificationValueNode } from '../../lib/classification';

/**
 * MUI types a slot's props as that element's known props, which excludes `data-*`. The attribute
 * reaches the DOM correctly; only the type is too narrow, so it is asserted in one helper — the
 * same shape and the same reason as `CaseEditorPage.selectTestId`.
 *
 * It targets the INPUT slot rather than the root, deliberately. A bare `data-testid` on a MUI
 * Checkbox lands on the wrapper span, where the checked state is not readable: a test could click
 * it successfully and assert nothing at all about selection, which is precisely how §62's option
 * test passed while measuring the wrong thing.
 */
const inputTestId = (id: string) =>
  ({ input: { 'data-testid': id } }) as never;

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
 * | SINGLE | no | a select, with a "None" option — a case need not be classified |
 * | MULTI | no | checkboxes |
 * | MULTI | yes | a tree of checkboxes (the Feature dimension) |
 *
 * `SINGLE + hierarchical` is not a combination the seed creates and is rendered as a tree of
 * **radios**, which is the honest reading of both flags rather than a thrown error: refusing to
 * draw a dimension a client legitimately configured would hide their data behind a crash.
 *
 * ## Cardinality is enforced by the control, not by validation
 *
 * A SINGLE dimension is a radio group, so a second value cannot be chosen. That is deliberate:
 * the alternative is a checkbox list plus a Zod refinement, which lets someone tick two boxes and
 * then tells them off for it. The server still checks — `curl` runs no React — and V2's trigger
 * still checks under that; this is the layer that makes the rule feel like a shape rather than a
 * rejection.
 *
 * ## Parents are selectable
 *
 * The Feature tree offers a parent exactly as it offers a leaf. The editor previously rendered
 * features read-only on the stated grounds that a flat select "would let someone set a parent where
 * a leaf is required" — **no such rule exists** in the plan, the ERD, the roles spec or `V1`, and
 * §6q's rule that a feature filter includes descendants is about reading. What a flat select really
 * loses is the parentage, which is why this is a tree.
 */

export function ClassificationPicker({
  dimensions,
  selected,
  onChange,
  disabled,
  testIdPrefix,
}: {
  dimensions: ClassificationDimension[];
  /** Every selected value id across all dimensions — the flat shape the contract takes. */
  selected: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
  testIdPrefix: string;
}) {
  const chosen = useMemo(() => new Set(selected), [selected]);

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
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        This project has no classification dimensions yet.
      </Typography>
    );
  }

  return (
    <Stack sx={{ gap: 2.5 }} data-testid={`${testIdPrefix}.Classification`}>
      {dimensions.map((dimension) => (
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
    </Stack>
  );
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
  const tree = useMemo(() => buildValueTree(dimension.values), [dimension.values]);
  const testId = `${testIdPrefix}.Dimension.${dimension.key}`;

  // An empty Feature tree is the NORMAL state of a freshly seeded project, not an error — the
  // contract says so where it documents `values`. Saying so beats an empty select that reads as a
  // failed load, and beats hiding the dimension, which would make the absence indistinguishable
  // from the project not having the dimension at all.
  if (dimension.values.length === 0) {
    return (
      <Stack sx={{ gap: 0.5 }} data-testid={testId}>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {dimension.name}
        </Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Nothing to choose from yet — this vocabulary is empty.
        </Typography>
      </Stack>
    );
  }

  // A flat SINGLE dimension is a select rather than a radio group: Test Type and Test Level have
  // eight and seven values, and a column of radios that tall drowns out the fields around it. The
  // cardinality is still enforced by the control — a select takes one value.
  if (dimension.cardinality === 'SINGLE' && !dimension.hierarchical) {
    const current = dimension.values.find((value) => chosen.has(value.id));
    return (
      <TextField
        select
        label={dimension.name}
        fullWidth
        disabled={disabled}
        value={current?.id ?? ''}
        onChange={(event) => onSelectOne(event.target.value || null)}
        slotProps={{
          select: {
            SelectDisplayProps: { 'data-testid': testId } as React.HTMLAttributes<HTMLDivElement>,
          },
        }}
      >
        {/* A case need not be classified — `test_case` has no NOT NULL for any of this, and a
            picker with no way back to "unset" makes the first accidental choice permanent. */}
        <MenuItem value="">
          <em>None</em>
        </MenuItem>
        {dimension.values.map((value) => (
          <MenuItem key={value.id} value={value.id}>
            {value.name}
          </MenuItem>
        ))}
      </TextField>
    );
  }

  if (dimension.hierarchical) {
    return (
      <FormControl component="fieldset" disabled={disabled} data-testid={testId}>
        <FormLabel component="legend" sx={{ typography: 'caption', mb: 0.5 }}>
          {dimension.name}
        </FormLabel>
        <SimpleTreeView
          // Every node expanded by default. A collapsed tree hides the selected value, and the
          // first thing a reader needs from this control is what the case is already classified as.
          defaultExpandedItems={dimension.values.map((value) => value.id)}
          sx={{ '& .MuiTreeItem-content': { py: 0 } }}
        >
          {tree.map((node) => (
            <ValueTreeItem
              key={node.value.id}
              node={node}
              chosen={chosen}
              disabled={disabled}
              single={dimension.cardinality === 'SINGLE'}
              onSelectOne={onSelectOne}
              onToggle={onToggle}
            />
          ))}
        </SimpleTreeView>
      </FormControl>
    );
  }

  // MULTI and flat — Execution Phase.
  return (
    <FormControl component="fieldset" disabled={disabled} data-testid={testId}>
      <FormLabel component="legend" sx={{ typography: 'caption', mb: 0.5 }}>
        {dimension.name}
      </FormLabel>
      <Stack direction="row" sx={{ flexWrap: 'wrap', columnGap: 2 }}>
        {dimension.values.map((value) => (
          <FormControlLabel
            key={value.id}
            control={
              <Checkbox
                size="small"
                checked={chosen.has(value.id)}
                onChange={(event) => onToggle(value.id, event.target.checked)}
                slotProps={inputTestId(`${testId}.${value.id}`)}
              />
            }
            label={value.name}
          />
        ))}
      </Stack>
    </FormControl>
  );
}

function ValueTreeItem({
  node,
  chosen,
  disabled,
  single,
  onSelectOne,
  onToggle,
}: {
  node: ClassificationValueNode;
  chosen: Set<string>;
  disabled?: boolean;
  single: boolean;
  onSelectOne: (valueId: string | null) => void;
  onToggle: (valueId: string, on: boolean) => void;
}) {
  const isChosen = chosen.has(node.value.id);

  return (
    <TreeItem
      itemId={node.value.id}
      label={
        <FormControlLabel
          // The label must not toggle the tree node: clicking the text of a parent should tick it,
          // not collapse its children, and TreeItem's own label handler would do the latter.
          onClick={(event) => event.stopPropagation()}
          control={
            single ? (
              <Radio
                size="small"
                checked={isChosen}
                disabled={disabled}
                // A radio group cannot be un-chosen by clicking the chosen one, so this is wired as
                // a toggle: choosing the current value clears it. Without that a hierarchical
                // SINGLE dimension would have no path back to "unclassified", which the flat
                // SINGLE control gets from its "None" option.
                onClick={() => onSelectOne(isChosen ? null : node.value.id)}
                slotProps={inputTestId(`Classification.Value.${node.value.id}`)}
              />
            ) : (
              <Checkbox
                size="small"
                checked={isChosen}
                disabled={disabled}
                onChange={(event) => onToggle(node.value.id, event.target.checked)}
                slotProps={inputTestId(`Classification.Value.${node.value.id}`)}
              />
            )
          }
          label={node.value.name}
        />
      }
    >
      {node.children.map((child) => (
        <ValueTreeItem
          key={child.value.id}
          node={child}
          chosen={chosen}
          disabled={disabled}
          single={single}
          onSelectOne={onSelectOne}
          onToggle={onToggle}
        />
      ))}
    </TreeItem>
  );
}
