import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { ClassificationDimension, Facet } from '../../api/types';
import { CaseFilterBar } from './CaseFilterBar';
import type { CaseFilterState } from './useCaseFilters';

/**
 * The strip's own rules, from design language rule 2 and rule 4 — and from the two things the
 * 2026-08-30 renders audit measured on the screen this replaced: ~480px of chrome above the first
 * data row, and four dead selects on a project with nothing in it.
 *
 * No `TestProviders` and no query client: this component takes everything it renders as props, and
 * the point of these tests is what it does with them.
 */
function makeState(overrides: Partial<CaseFilterState> = {}): CaseFilterState {
  return {
    filters: { page: 0, size: 25 },
    sort: 'updatedAt,desc',
    hasActiveFilters: false,
    activeFilterCount: 0,
    setFilter: vi.fn(),
    setPage: vi.fn(),
    setSize: vi.fn(),
    setSort: vi.fn(),
    clearFilters: vi.fn(),
    ...overrides,
  } as CaseFilterState;
}

const FEATURE: ClassificationDimension = {
  key: 'feature',
  name: 'Feature',
  values: [{ id: 'f1', name: 'Checkout', path: 'checkout' }],
} as unknown as ClassificationDimension;

const FACETS: Facet[] = [
  { key: 'lifecycleStatus', values: [{ id: 'APPROVED', count: 12 }] },
] as unknown as Facet[];

function renderBar(props: Partial<Parameters<typeof CaseFilterBar>[0]> = {}) {
  return render(
    <CaseFilterBar
      state={makeState()}
      facets={FACETS}
      dimensions={[FEATURE]}
      total={86}
      {...props}
    />,
  );
}

describe('the case filter strip shows what was chosen, not what exists', () => {
  it('renders no chip until a filter is applied', () => {
    renderBar();
    expect(screen.queryByTestId(/Cases\.Filters\.Applied\./)).not.toBeInTheDocument();
  });

  it('does not put the vocabulary on the screen', () => {
    // The defect this replaced: four labelled chip groups listing every value of every facet,
    // Health alone being eleven chips over two rows. The values live behind a closed menu now.
    renderBar();
    expect(screen.queryByText('Not linked to any spec')).not.toBeInTheDocument();
    expect(screen.getByTestId('Cases.Filters.Health')).toBeInTheDocument();
  });

  it('names each applied filter by facet and value, and removes it', async () => {
    const setFilter = vi.fn();
    renderBar({
      state: makeState({
        filters: { page: 0, size: 25, lifecycleStatus: ['APPROVED'] },
        hasActiveFilters: true,
        activeFilterCount: 1,
        setFilter,
      }),
    });

    await userEvent.click(screen.getByRole('button', { name: 'Remove Status · Approved' }));
    expect(setFilter).toHaveBeenCalledWith('lifecycleStatus', undefined);
  });

  it('offers the values behind one closed control per facet', async () => {
    const setFilter = vi.fn();
    renderBar({ state: makeState({ setFilter }) });

    await userEvent.click(screen.getByTestId('Cases.Filters.Status'));
    await userEvent.click(await screen.findByTestId('Cases.Filters.Status.APPROVED'));
    expect(setFilter).toHaveBeenCalledWith('lifecycleStatus', ['APPROVED']);
  });
});

describe('rule 4 — nothing configured, nothing shown', () => {
  it('renders no control at all on a project with no cases and no facets', () => {
    renderBar({ facets: [], dimensions: [], total: 0 });

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByTestId('Cases.Filters.Nothing')).toHaveTextContent(
      'Nothing to filter yet — this project has no cases.',
    );
  });

  it('keeps the strip when a filter is what emptied the project', () => {
    // The one case the rule must not swallow: 0 rows BECAUSE of a filter still needs the control
    // that would take it off.
    renderBar({
      facets: [],
      dimensions: [],
      total: 0,
      state: makeState({ hasActiveFilters: true, activeFilterCount: 1 }),
    });

    expect(screen.queryByTestId('Cases.Filters.Nothing')).not.toBeInTheDocument();
    expect(screen.getByTestId('Cases.Filters.Clear')).toBeInTheDocument();
  });

  it('drops a classification select whose dimension nobody has configured', () => {
    renderBar({ dimensions: [] });
    expect(screen.queryByTestId('Cases.Filters.Feature')).not.toBeInTheDocument();
  });
});
