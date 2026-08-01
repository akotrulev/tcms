import {
  Box,
  Button,
  Chip,
  InputAdornment,
  MenuItem,
  Select,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ClearIcon from '@mui/icons-material/Close';
import SearchIcon from '@mui/icons-material/Search';
import { useEffect, useState } from 'react';
import type { ClassificationDimension, Facet } from '../../api/types';
import {
  automationLabels,
  healthLabels,
  lifecycleLabels,
  priorityLabels,
} from '../../lib/labels';
import { formatCount } from '../../lib/format';
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
    <Stack sx={{ gap: 1.5, mb: 2 }} data-testid={TestIdPrefix}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ gap: 1.5, alignItems: { md: 'center' } }}>
        <TextField
          placeholder="Search titles, or paste a key like TC-42"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          sx={{ flex: 1, minWidth: 240 }}
          slotProps={{
            htmlInput: { 'data-testid': `${TestIdPrefix}.Search`, 'aria-label': 'Search test cases' },
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
              endAdornment: search ? (
                <InputAdornment position="end">
                  <Button
                    size="small"
                    onClick={() => setSearch('')}
                    data-testid={`${TestIdPrefix}.ClearSearch`}
                    aria-label="Clear search"
                    sx={{ minWidth: 0, p: 0.5 }}
                  >
                    <ClearIcon fontSize="small" />
                  </Button>
                </InputAdornment>
              ) : null,
            },
          }}
        />

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
      </Stack>

      {/* Toggle chips rather than dropdowns: these are the filters people combine, and a
          chip row shows what's active without opening anything. */}
      <Stack direction="row" sx={{ gap: 2, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        {MULTI_FILTERS.map((filter) => {
          const selected = (filters[filter.key] as string[] | undefined) ?? [];
          return (
            <Box key={filter.key}>
              <Typography
                variant="caption"
                sx={{ display: 'block', color: 'text.secondary', mb: 0.5, fontWeight: 600 }}
              >
                {filter.label}
              </Typography>
              <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                {Object.entries(filter.labels).map(([value, label]) => {
                  const isSelected = selected.includes(value);
                  const count = facetCount(filter.key, value);
                  return (
                    <Chip
                      key={value}
                      size="small"
                      label={count === undefined ? label : `${label} ${formatCount(count)}`}
                      onClick={() => toggleMulti(filter.key, value)}
                      variant={isSelected ? 'filled' : 'outlined'}
                      color={isSelected ? 'primary' : 'default'}
                      aria-pressed={isSelected}
                      data-testid={`${TestIdPrefix}.${filter.label}.${value}`}
                      sx={{ cursor: 'pointer' }}
                    />
                  );
                })}
              </Stack>
            </Box>
          );
        })}
      </Stack>

      {/*
        The owner filter has no control of its own — it arrives only from a dashboard by-owner
        segment — so without this the user lands on a narrowed list with nothing saying whose
        cases they are looking at, and "Clear filters" as the only clue that it is narrowed at
        all. A filter the UI applies but never states is the same defect class as one it drops:
        both render a plausible page that answers a different question than the heading claims.
      */}
      {filters.ownerId && (
        <Box>
          <Chip
            size="small"
            label={`Owner: ${ownerName ?? 'one owner'}`}
            onDelete={() => setFilter('ownerId', undefined)}
            data-testid={`${TestIdPrefix}.Owner`}
          />
        </Box>
      )}

      <Stack direction="row" sx={{ gap: 1.5, alignItems: 'center' }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid={`${TestIdPrefix}.ResultCount`}>
          {total === undefined ? 'Loading…' : `${formatCount(total)} ${total === 1 ? 'case' : 'cases'}`}
        </Typography>
        {hasActiveFilters && (
          <Button size="small" onClick={clearFilters} data-testid={`${TestIdPrefix}.ClearAll`}>
            Clear filters
          </Button>
        )}
      </Stack>
    </Stack>
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
    <Box sx={{ minWidth: 160 }}>
      <Select
        size="small"
        fullWidth
        displayEmpty
        value={value ?? ''}
        onChange={(event) => onChange(event.target.value || undefined)}
        data-testid={testId}
        renderValue={(selected) => {
          if (!selected) return <Box sx={{ color: 'text.secondary' }}>{label}: any</Box>;
          const match = dimension?.values.find((v) => v.id === selected);
          return `${label}: ${match?.name ?? selected}`;
        }}
        inputProps={{ 'aria-label': `Filter by ${label.toLowerCase()}` }}
      >
        <MenuItem value="">{label}: any</MenuItem>
        {(dimension?.values ?? []).map((option) => (
          <MenuItem key={option.id} value={option.id}>
            {/* Hierarchical dimensions are indented by depth so "Refunds" reads as a child
                of "Payments" rather than as a peer. */}
            <Box component="span" sx={{ pl: (option.path.split('.').length - 1) * 1.5 }}>
              {option.name}
            </Box>
          </MenuItem>
        ))}
      </Select>
    </Box>
  );
}
