import { Search, X } from 'lucide-react';
import { Chip } from '../../components/kit';
import { Field } from '../../components/kit/Field';
import { Button, Input } from '../../components/ui';
import { NativeSelect } from '../../components/ui/native-select';
import { useEffect, useState } from 'react';
import type { ClassificationDimension, Facet } from '../../api/types';
import {
  automationLabels,
  healthLabels,
  lifecycleLabels,
  priorityLabels,
} from '../../lib/labels';
import { EM_DASH, formatCount, plural } from '../../lib/format';
import type { CaseFilterState } from './useCaseFilters';

/**
 * The filter bar sits **above** the data region, never inside it. That placement is the
 * whole reason a failed load leaves the user able to act: the rows can disappear, but the
 * search box they need in order to retry with a narrower query stays mounted and keeps its
 * value.
 *
 * Every filter here has a matching column in the grid, and vice versa — a status badge you
 * can't filter by status is a recurring miss, and so is a filter for something the grid
 * doesn't show.
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

  return (
    <div className="flex flex-col gap-3 mb-4" data-testid={TestIdPrefix}>
      <div className="flex flex-col">
        {/*
          ~~`InputAdornment` slots~~ → the icons are positioned siblings, T16. MUI's adornment API
          reserved room inside its own input wrapper; here the wrapper is a `relative` div and the
          input carries the padding that keeps text clear of both icons.
        */}
        <div className="relative min-w-[240px] flex-1">
          <Search
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-ink-4"
          />
          <Input
            placeholder="Search titles, or paste a key like TC-42"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            data-testid={`${TestIdPrefix}.Search`}
            aria-label="Search test cases"
            className="pr-9 pl-8"
          />
          {search && (
            <Button
              variant="quiet"
              size="icon"
              onClick={() => setSearch('')}
              data-testid={`${TestIdPrefix}.ClearSearch`}
              aria-label="Clear search"
              className="absolute top-1/2 right-1 size-7 -translate-y-1/2"
            >
              <X aria-hidden className="size-3.5" />
            </Button>
          )}
        </div>

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
      </div>

      {/* Toggle chips rather than dropdowns: these are the filters people combine, and a
          chip row shows what's active without opening anything. */}
      <div className="flex gap-4 flex-wrap items-start">
        {MULTI_FILTERS.map((filter) => {
          const selected = (filters[filter.key] as string[] | undefined) ?? [];
          return (
            <div key={filter.key}>
              <span className="m-0 text-xs block text-ink-2 mb-1 font-semibold">
                {filter.label}
              </span>
              <div className="flex gap-1 flex-wrap">
                {Object.entries(filter.labels).map(([value, label]) => {
                  const isSelected = selected.includes(value);
                  const count = facetCount(filter.key, value);
                  return (
                    <Chip
                      key={value}
                      onClick={() => toggleMulti(filter.key, value)}
                      selected={isSelected}
                      aria-pressed={isSelected}
                      data-testid={`${TestIdPrefix}.${filter.label}.${value}`}
                    >
                      {count === undefined ? label : `${label} ${formatCount(count)}`}
                    </Chip>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>

      {/*
        Tags and the unfiled toggle (§96). Both are narrowing filters over things the grid shows,
        which is this bar's own rule — the tags column has been on the grid since Phase 1 with no
        way to filter by it, and an unfiled case appears under no node of the tree, so without the
        toggle the only way to find one is to page through the project.

        The tag input commits on Enter and renders as removable chips. There is no option list
        because there is no vocabulary — see the editor's own tags control for why offering a
        curated set is the thing that left `shared_step` empty.
      */}
      <div className="flex gap-3 items-center flex-wrap">
        <Field
          label="Tag"
          htmlFor="tag"
          help="All of these — a case must carry every one."
        >
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
          placeholder="Add a tag and press Enter"
          aria-label="Filter by tag"
          data-testid={`${TestIdPrefix}.TagInput`}
        />
        </Field>
        {(filters.tag ?? []).map((tag) => (
          <Chip
            key={tag}
            // The chip's own body navigates nowhere — the filter is already applied, and the only
            // act it offers is taking it off. `onRemove` is the affordance; `onClick` is required
            // by the type and is deliberately a no-op rather than a second way to do the same
            // thing.
            onClick={() => {}}
            onRemove={() =>
              setFilter(
                'tag',
                (filters.tag ?? []).filter((value) => value !== tag),
              )
            }
            data-testid={`${TestIdPrefix}.Tag.${tag}`}
          >
            {tag}
          </Chip>
        ))}
        <Chip
          onClick={() => setFilter('unfiled', filters.unfiled ? undefined : true)}
          selected={!!filters.unfiled}
          aria-pressed={!!filters.unfiled}
          // Disabled beside a suite filter rather than hidden (§31): the server ignores the pair,
          // so a chip that looked active and changed nothing would be the worse half of
          // render-then-reject. The reason is on the tooltip, not inferred from the greying.
          disabled={!!filters.suiteId}
          title={
            filters.suiteId
              ? 'Clear the suite filter first — a case cannot be both in a folder and in none.'
              : 'Only cases filed in no suite'
          }
          data-testid={`${TestIdPrefix}.Unfiled`}
          className={filters.suiteId ? 'cursor-not-allowed' : 'cursor-pointer'}
        >
          Not filed
        </Chip>
      </div>

      {/*
        The owner filter has no control of its own — it arrives only from a dashboard by-owner
        segment — so without this the user lands on a narrowed list with nothing saying whose
        cases they are looking at, and "Clear filters" as the only clue that it is narrowed at
        all. A filter the UI applies but never states is the same defect class as one it drops:
        both render a plausible page that answers a different question than the heading claims.
      */}
      {filters.ownerId && (
        <div>
          <Chip
            // As with the tag chips above: the body is a statement, `onRemove` is the act.
            onClick={() => {}}
            onRemove={() => setFilter('ownerId', undefined)}
            data-testid={`${TestIdPrefix}.Owner`}
          >
            {`Owner: ${ownerName ?? 'one owner'}`}
          </Chip>
        </div>
      )}

      <div className="flex gap-3 items-center">
        <p className="m-0 text-sm text-ink-2" data-testid={`${TestIdPrefix}.ResultCount`}>
          {/*
            An unknown count is rendered as absent, not as "Loading…". The `DataRegion` beside this
            bar already owns loading and failed, and a bare text node claiming the page was still
            working sat directly above that region's failure panel — the page saying it was working
            and that it had failed, at the same moment, in the same column.
          */}
          {total === undefined ? EM_DASH : plural(total, 'case')}
        </p>
        {hasActiveFilters && (
          <Button size="sm" onClick={clearFilters} data-testid={`${TestIdPrefix}.ClearAll`}>
            Clear filters
          </Button>
        )}
      </div>
    </div>
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
  return (
    <div style={{ minWidth: 160 }}>
      <NativeSelect
        value={value ?? ''}
        onChange={(event) => onChange(event.target.value || undefined)}
        data-testid={testId}
        inputProps={{ 'aria-label': `Filter by ${label.toLowerCase()}` }}
      >
        <option value="">{label}: any</option>
        {(dimension?.values ?? []).map((option) => (
          <option key={option.id} value={option.id}>
            {/* Hierarchical dimensions are indented by depth so "Refunds" reads as a child
                of "Payments" rather than as a peer. */}
            <span style={{ paddingLeft: (option.path.split('.').length - 1) * 12 }}>
              {option.name}
            </span>
          </option>
        ))}
      </NativeSelect>
    </div>
  );
}
