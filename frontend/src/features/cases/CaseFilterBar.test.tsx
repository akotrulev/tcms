import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { ClassificationDimension, Facet } from '../../api/types';
import { openFilters } from '../../test/filters';
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

/*
  Declared, never cast — `contractFixtures.test.ts`'s rule, and its reason: a cast tells the
  compiler the object already IS the type, so the next required field the contract gains is a
  runtime failure in every test that reads it rather than one build error here.
*/
const FEATURE: ClassificationDimension = {
  id: 'dim-feature',
  key: 'feature',
  name: 'Feature',
  cardinality: 'SINGLE',
  hierarchical: false,
  locked: false,
  values: [
    {
      id: 'f1',
      parentId: null,
      name: 'Checkout',
      path: 'checkout',
      color: null,
    },
  ],
};

const FACETS: Facet[] = [
  {
    key: 'lifecycleStatus',
    label: 'Status',
    values: [{ id: 'APPROVED', label: 'Approved', count: 12 }],
  },
];

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

  it('does not put the vocabulary on the screen', async () => {
    // The defect this replaced: four labelled chip groups listing every value of every facet,
    // Health alone being eleven chips over two rows. The values live behind a closed menu now.
    renderBar();
    expect(screen.queryByTestId('Cases.Filters.Health')).not.toBeInTheDocument();
    await openFilters(userEvent.setup(), 'Cases.Filters');
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
    const user = userEvent.setup();

    await openFilters(user, 'Cases.Filters');
    await user.click(screen.getByTestId('Cases.Filters.Status'));
    await user.click(await screen.findByTestId('Cases.Filters.Status.APPROVED'));
    expect(setFilter).toHaveBeenCalledWith('lifecycleStatus', ['APPROVED']);
  });

  it('closes a facet menu before the popover on Escape, and returns focus to Filter', async () => {
    renderBar();
    const user = userEvent.setup();

    await openFilters(user, 'Cases.Filters');
    await user.click(screen.getByTestId('Cases.Filters.Status'));
    await screen.findByTestId('Cases.Filters.Status.APPROVED');

    await user.keyboard('{Escape}');
    expect(screen.queryByTestId('Cases.Filters.Status.APPROVED')).not.toBeInTheDocument();
    expect(screen.getByTestId('Cases.Filters.Popover')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByTestId('Cases.Filters.Popover')).not.toBeInTheDocument();
    expect(screen.getByTestId('Cases.Filters.Open')).toHaveFocus();
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

  it('drops a classification select whose dimension nobody has configured', async () => {
    renderBar({ dimensions: [] });
    await openFilters(userEvent.setup(), 'Cases.Filters');
    expect(screen.queryByTestId('Cases.Filters.Feature')).not.toBeInTheDocument();
  });
});
