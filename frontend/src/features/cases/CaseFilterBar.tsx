import { ChevronDown, Search, X } from 'lucide-react';
import { useEffect, useState } from 'react';

import type { ClassificationDimension, Facet } from '../../api/types';
import { FilterStrip, type AppliedFilter } from '../../components/kit';
import { Button, Input } from '../../components/ui';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '../../components/ui/dropdown-menu';
import { NativeSelect } from '../../components/ui/native-select';
import { formatCount, plural } from '../../lib/format';
import {
  automationLabels,
  healthLabels,
  lifecycleLabels,
  priorityLabels,
} from '../../lib/labels';
import type { CaseFilterState } from './useCaseFilters';

/**
 * The filter bar sits **above** the data region, never inside it. That placement is the whole
 * reason a failed load leaves the user able to act: the rows can disappear, but the search box they
 * need in order to retry with a narrower query stays mounted and keeps its value.
 *
 * Every filter here has a matching column in the grid, and vice versa — a status badge you can't
 * filter by status is a recurring miss, and so is a filter for something the grid doesn't show.
 *
 * ## One row, and it shows what was chosen — not what exists
 *
 * ~~Toggle chips rather than dropdowns: these are the filters people combine, and a chip row shows
 * what's active without opening anything.~~ → **superseded, 2026-08-30 (renders audit 1.1).** The
 * argument was right about wanting the active set visible and wrong about the cost. Rendering every
 * *available* value as a toggle put four labelled chip groups on the screen — Health alone is
 * eleven chips over two rows, wider than the table's own Health column — under three full-width
 * selects, a tag field and an explanatory sentence: roughly **480px of chrome above the first data
 * row**, where design language rule 2 asks for one row.
 *
 * The strip could not be one row because it scaled with the *vocabulary* rather than with the
 * reader's choice. It also crossed §5's badge-vs-chip line in the other direction: the render's chip
 * means "this filter is applied, click × to remove it", and the build's meant "this value exists,
 * click to apply it".
 *
 * So choosing moved into a closed menu per facet, and the chips now show only the applied set —
 * which is what `CaseSearchPage` beside it was already doing (audit 1.2: two idioms in one family).
 *
 * ## Rule 4 — nothing configured, nothing shown
 *
 * On a project with no cases and no suites this rendered three empty selects, four full chip groups
 * with every value and no counts, a tag input and its sentence, and then `0 cases` and nothing else
 * (audit 9.5). The suites panel beside it got this right in the same view. A filter over an empty
 * corpus is not a narrower view, it is furniture.
 */

const TestIdPrefix = 'Cases.Filters';

interface MultiFilter {
  key: 'lifecycleStatus' | 'automationStatus' | 'priority' | 'healthSignal';
  label: string;
  labels: Record<string, string>;
}

const MULTI_FILTERS: MultiFilter[] = [
  { key: 'lifecycleStatus', label: 'Status', labels: lifecycleLabels },
  { key: 'automationStatus', label: 'Automation', labels: automationLabels },
  { key: 'priority', label: 'Priority', labels: priorityLabels },
  { key: 'healthSignal', label: 'Health', labels: healthLabels },
];

export function CaseFilterBar({
  state,
  facets,
  dimensions,
  total,
  ownerName,
}: {
  state: CaseFilterState;
  facets: Facet[] | undefined;
  dimensions: ClassificationDimension[] | undefined;
  total: number | undefined;
  /** Display name behind an active `ownerId`, when the loaded rows can supply one. */
  ownerName?: string;
}) {
  const { filters, hasActiveFilters, setFilter, clearFilters } = state;

  // The search box is local while typing and pushed to the URL on a debounce: writing every
  // keystroke into history would make the back button useless.
  const [search, setSearch] = useState(filters.q ?? '');

  /**
   * The tag box is local until Enter, and deliberately NOT debounced like the search box.
   *
   * A half-typed tag is not a narrower filter, it is a different one that matches nothing — tags
   * are matched exactly by both the grid and a suite's rules — so pushing keystrokes to the URL
   * would empty the grid on the way to every filter anybody applies.
   */
  const [tagDraft, setTagDraft] = useState('');

  // Keep the box in step when the URL changes from elsewhere — a dashboard tile link, or
  // the browser's back button.
  useEffect(() => {
    setSearch(filters.q ?? '');
  }, [filters.q]);

  useEffect(() => {
    if (search === (filters.q ?? '')) return;
    const id = setTimeout(() => setFilter('q', search || undefined), 300);
    return () => clearTimeout(id);
  }, [search, filters.q, setFilter]);

  const featureDimension = dimensions?.find((d) => d.key === 'feature');
  const typeDimension = dimensions?.find((d) => d.key === 'test_type');
  const levelDimension = dimensions?.find((d) => d.key === 'test_level');

  /** Facet counts come from the filtered set, so the number matches what selecting it yields. */
  const facetCount = (key: string, value: string) =>
    facets?.find((f) => f.key === key)?.values.find((v) => v.id === value)?.count;

  const toggleMulti = (key: MultiFilter['key'], value: string) => {
    const current = (filters[key] as string[] | undefined) ?? [];
    const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
    setFilter(key, next.length ? next : undefined);
  };

  const dimensionName = (dimension: ClassificationDimension | undefined, id: string) =>
    dimension?.values.find((value) => value.id === id)?.name ?? id;

  /**
   * The applied set, derived from the filter state rather than from any vocabulary — which is the
   * property that keeps this a strip. It grows with what the reader chose and nothing else.
   */
  const applied: AppliedFilter[] = [
    ...MULTI_FILTERS.flatMap(({ key, label, labels }) =>
      ((filters[key] as string[] | undefined) ?? []).map((value) => ({
        key: `${key}:${value}`,
        label: `${label} · ${labels[value] ?? value}`,
        onRemove: () => toggleMulti(key, value),
      })),
    ),
    ...(filters.featureId
      ? [
          {
            key: `featureId:${filters.featureId}`,
            label: `Feature · ${dimensionName(featureDimension, filters.featureId)}`,
            onRemove: () => setFilter('featureId', undefined),
          },
        ]
      : []),
    ...(filters.testTypeId
      ? [
          {
            key: `testTypeId:${filters.testTypeId}`,
            label: `Type · ${dimensionName(typeDimension, filters.testTypeId)}`,
            onRemove: () => setFilter('testTypeId', undefined),
          },
        ]
      : []),
    ...(filters.testLevelId
      ? [
          {
            key: `testLevelId:${filters.testLevelId}`,
            label: `Level · ${dimensionName(levelDimension, filters.testLevelId)}`,
            onRemove: () => setFilter('testLevelId', undefined),
          },
        ]
      : []),
    ...(filters.tag ?? []).map((tag) => ({
      key: `tag:${tag}`,
      label: `Tag · ${tag}`,
      onRemove: () =>
        setFilter(
          'tag',
          (filters.tag ?? []).filter((value) => value !== tag),
        ),
    })),
    ...(filters.unfiled
      ? [
          {
            key: 'unfiled',
            label: 'Not filed',
            onRemove: () => setFilter('unfiled', undefined),
          },
        ]
      : []),
    /*
      The owner filter has no control of its own — it arrives only from a dashboard by-owner
      segment — so without this the user lands on a narrowed list with nothing saying whose cases
      they are looking at, and "Clear filters" as the only clue that it is narrowed at all. A
      filter the UI applies but never states is the same defect class as one it drops: both render
      a plausible page that answers a different question than the heading claims.
    */
    ...(filters.ownerId
      ? [
          {
            key: `ownerId:${filters.ownerId}`,
            label: `Owner · ${ownerName ?? 'one owner'}`,
            onRemove: () => setFilter('ownerId', undefined),
          },
        ]
      : []),
  ];

  /*
    Rule 4. `facets` empty AND no cases means there is nothing to narrow — not "the facets have not
    loaded", which is `undefined` and falls through to the strip.
  */
  if (total === 0 && facets?.length === 0 && !hasActiveFilters) {
    return (
      <p className="mb-3 text-[12.5px] text-ink-4" data-testid={`${TestIdPrefix}.Nothing`}>
        Nothing to filter yet — this project has no cases.
      </p>
    );
  }

  return (
    <FilterStrip
      data-testid={TestIdPrefix}
      applied={applied}
      onClear={hasActiveFilters ? clearFilters : undefined}
      summary={
        total === undefined
          ? undefined
          : hasActiveFilters
            ? `Showing ${formatCount(total)}`
            : plural(total, 'case')
      }
      search={
        <div className="relative w-[240px]">
          <Search
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-ink-4"
          />
          <Input
            placeholder="Search titles, or a key like TC-42"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            data-testid={`${TestIdPrefix}.Search`}
            aria-label="Search test cases"
            className="h-8 pr-9 pl-8"
          />
          {search && (
            <Button
              variant="quiet"
              size="icon"
              onClick={() => setSearch('')}
              data-testid={`${TestIdPrefix}.ClearSearch`}
              aria-label="Clear search"
              className="absolute top-1/2 right-1 size-6 -translate-y-1/2"
            >
              <X aria-hidden className="size-3.5" />
            </Button>
          )}
        </div>
      }
      pickers={
        <>
          {MULTI_FILTERS.map((filter) => (
            <FacetMenu
              key={filter.key}
              label={filter.label}
              labels={filter.labels}
              selected={(filters[filter.key] as string[] | undefined) ?? []}
              onToggle={(value) => toggleMulti(filter.key, value)}
              count={(value) => facetCount(filter.key, value)}
              testId={`${TestIdPrefix}.${filter.label}`}
            />
          ))}
          <SelectFilter
            label="Feature"
            value={filters.featureId}
            dimension={featureDimension}
            onChange={(value) => setFilter('featureId', value)}
            testId={`${TestIdPrefix}.Feature`}
          />
          <SelectFilter
            label="Type"
            value={filters.testTypeId}
            dimension={typeDimension}
            onChange={(value) => setFilter('testTypeId', value)}
            testId={`${TestIdPrefix}.TestType`}
          />
          <SelectFilter
            label="Level"
            value={filters.testLevelId}
            dimension={levelDimension}
            onChange={(value) => setFilter('testLevelId', value)}
            testId={`${TestIdPrefix}.TestLevel`}
          />
          <Input
            id="tag"
            value={tagDraft}
            onChange={(event) => setTagDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key !== 'Enter') return;
              event.preventDefault();
              const tag = tagDraft.trim();
              if (!tag) return;
              const current = filters.tag ?? [];
              // Adding one already applied is a no-op rather than a duplicate parameter: the
              // server ANDs, so `?tag=smoke&tag=smoke` narrows to the same rows twice.
              if (!current.includes(tag)) setFilter('tag', [...current, tag]);
              setTagDraft('');
            }}
            placeholder="Tag, then Enter"
            aria-label="Filter by tag"
            data-testid={`${TestIdPrefix}.TagInput`}
            className="h-8 w-[150px]"
          />
          <Button
            size="sm"
            onClick={() => setFilter('unfiled', filters.unfiled ? undefined : true)}
            aria-pressed={!!filters.unfiled}
            // Disabled beside a suite filter rather than hidden (§31): the server ignores the
            // pair, so a control that looked active and changed nothing would be the worse half
            // of render-then-reject. The reason is on the tooltip, not inferred from the greying.
            disabled={!!filters.suiteId}
            title={
              filters.suiteId
                ? 'Clear the suite filter first — a case cannot be both in a folder and in none.'
                : 'Only cases filed in no suite'
            }
            data-testid={`${TestIdPrefix}.Unfiled`}
          >
            Not filed
          </Button>
        </>
      }
    />
  );
}

/**
 * One facet, chosen from a closed menu.
 *
 * A menu rather than `kit/MultiPicker`, which is the product's other multi-select: `MultiPicker`
 * renders its own chips for what is selected, and here the applied set is already the strip's job —
 * two chip rows for one choice is the thing this screen was being fixed for.
 */
function FacetMenu({
  label,
  labels,
  selected,
  onToggle,
  count,
  testId,
}: {
  label: string;
  labels: Record<string, string>;
  selected: string[];
  onToggle: (value: string) => void;
  count: (value: string) => number | undefined;
  testId: string;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="sm" data-testid={testId} aria-label={`Filter by ${label.toLowerCase()}`}>
          {label}
          {selected.length > 0 && (
            <span className="tabular-nums text-ink-3">{formatCount(selected.length)}</span>
          )}
          <ChevronDown aria-hidden className="size-3.5 text-ink-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        {Object.entries(labels).map(([value, text]) => {
          const n = count(value);
          return (
            <DropdownMenuCheckboxItem
              key={value}
              checked={selected.includes(value)}
              // The menu stays open: choosing two statuses is one gesture, and a menu that closes
              // on every pick turns it into two.
              onSelect={(event) => {
                event.preventDefault();
                onToggle(value);
              }}
              data-testid={`${testId}.${value}`}
            >
              {n === undefined ? text : `${text} ${formatCount(n)}`}
            </DropdownMenuCheckboxItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function SelectFilter({
  label,
  value,
  dimension,
  onChange,
  testId,
}: {
  label: string;
  value: string | undefined;
  dimension: ClassificationDimension | undefined;
  onChange: (value: string | undefined) => void;
  testId: string;
}) {
  // Rule 4 again, at the control's own scale: a project-scoped dimension nobody has configured
  // renders an empty select whose only option is "any", which is a control that cannot do
  // anything. That was one of the three dead selects on an empty project.
  if (!dimension || dimension.values.length === 0) return null;

  return (
    <NativeSelect
      value={value ?? ''}
      onChange={(event) => onChange(event.target.value || undefined)}
      data-testid={testId}
      aria-label={`Filter by ${label.toLowerCase()}`}
      className="h-8 w-[150px]"
    >
      <option value="">{label}: any</option>
      {dimension.values.map((option) => (
        <option key={option.id} value={option.id}>
          {/* Hierarchical dimensions are indented by depth so "Refunds" reads as a child
              of "Payments" rather than as a peer. An `<option>` may hold text only, so the
              indent is leading spaces rather than padding. */}
          {' '.repeat((option.path.split('.').length - 1) * 2)}
          {option.name}
        </option>
      ))}
    </NativeSelect>
  );
}
