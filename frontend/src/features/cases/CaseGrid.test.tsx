import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { api } from '../../api/client';

import type { TestCaseSummary } from '../../api/types';
import { TestProviders } from '../../test/TestProviders';
import { aCaseSummary } from '../../test/fixtures';
import { createTestQueryClient } from '../../test/queryClient';
import { CaseGrid } from './CaseGrid';

/**
 * The grid moved from MUI X to TanStack Table in plan 007's T9. These assertions are the ones
 * that would let the port look right and behave differently.
 *
 * The load-bearing property is that **the grid owns no data**: it renders the page it was handed,
 * in the order it was handed, and turns every interaction into a call the caller writes to the
 * URL. A headless engine will happily sort and page in the browser if asked, and a grid that
 * quietly did so would pass every visual check on a project with thirty cases and drop rows on
 * one with three thousand — which is the failure the server-side contract exists to prevent, and
 * one no screenshot shows.
 */

const PROJECT = '11111111-1111-1111-1111-111111111111';

const rows: TestCaseSummary[] = [
  aCaseSummary({ id: 'c1', key: 'TC-3', title: 'Zulu', suiteName: 'Refunds' }),
  aCaseSummary({
    id: 'c2',
    key: 'TC-2',
    title: 'Alpha',
    lastResult: {
      statusKind: 'FAILED',
      executedAt: '2026-08-20T09:00:00Z',
      releaseVersion: null,
      configurationName: 'nightly-eu',
    },
  }),
  aCaseSummary({ id: 'c3', key: 'TC-1', title: 'Mike' }),
];

function renderGrid(overrides: Partial<Parameters<typeof CaseGrid>[0]> = {}) {
  const props = {
    rows,
    total: 120,
    page: 0,
    size: 25,
    sort: 'keySeq,asc',
    selection: [] as string[],
    onSelectionChange: vi.fn(),
    onPageChange: vi.fn(),
    onSizeChange: vi.fn(),
    onSortChange: vi.fn(),
    projectId: PROJECT,
    loading: false,
    ...overrides,
  };

  /*
    The Verifies column's second read (013 D-i). Answered empty — this file is about the grid's
    contract with its caller, not about what a case proves — but answered, because without an arm
    the query reaches the real `fetch` and the harness fails the test on the failed read.
  */
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (/^\/projects\/[^/]+\/case-verifications$/.test(path)) return [];
    throw new Error(`unmocked read: ${path}`);
  }) as never);

  render(
    <TestProviders client={createTestQueryClient()}>
      <CaseGrid {...props} />
    </TestProviders>,
  );

  return props;
}

describe('the grid is a view over a query, not an owner of data', () => {
  it('renders the page in the order it was handed, however the rows compare', () => {
    renderGrid();

    expect(screen.getAllByTestId('Cases.Row.KeyLink').map((link) => link.textContent)).toEqual([
      'TC-3',
      'TC-2',
      'TC-1',
    ]);
  });

  it('sorting a column asks the caller to re-query and does not reorder anything locally', async () => {
    const props = renderGrid();

    await userEvent.click(screen.getByRole('button', { name: /Title/ }));

    expect(props.onSortChange).toHaveBeenCalledWith('title,asc');
    expect(screen.getAllByTestId('Cases.Row.Title').map((link) => link.textContent)).toEqual([
      'Zulu',
      'Alpha',
      'Mike',
    ]);
  });

  it('clearing a sort falls back to the default, which carries the unique tiebreaker', async () => {
    const props = renderGrid({ sort: 'title,desc' });

    await userEvent.click(screen.getByRole('button', { name: /Title/ }));

    expect(props.onSortChange).toHaveBeenCalledWith('keySeq,asc');
  });

  it('no header claims an order the grid is not showing', () => {
    renderGrid();

    // `keySeq` is the server's insertion order and has no column here, so nothing is sorted by
    // anything the reader can see. A header announcing `ascending` would be a lie.
    expect(screen.queryByRole('columnheader', { current: 'ascending' })).toBeNull();
    for (const header of screen.getAllByRole('columnheader')) {
      expect(header.getAttribute('aria-sort')).not.toBe('ascending');
    }
  });

  it('a composite column offers no sort at all, rather than half of one', () => {
    renderGrid();

    expect(screen.queryByRole('button', { name: /Last result/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Health/ })).toBeNull();
  });

  it('pages against the server total, not the rows it was given', async () => {
    const props = renderGrid();

    expect(screen.getByTestId('Cases.Pager.Previous')).toBeDisabled();
    await userEvent.click(screen.getByTestId('Cases.Pager.Next'));

    expect(props.onPageChange).toHaveBeenCalledWith(1);
  });

  it('changing the page size asks the caller, which is where the URL is written', async () => {
    const props = renderGrid();

    await userEvent.selectOptions(screen.getByTestId('Cases.Pager.Size'), '50');

    expect(props.onSizeChange).toHaveBeenCalledWith(50);
  });
});

describe('selection', () => {
  it('survives paging — a row selected elsewhere is not dropped by a click on this page', async () => {
    const props = renderGrid({ selection: ['not-on-this-page'] });

    fireEvent.click(screen.getAllByTestId('Cases.Row.Select')[0]);

    expect(props.onSelectionChange).toHaveBeenCalledWith(['not-on-this-page', 'c1']);
  });

  it('select-all reaches the page, never the 120 rows behind it', async () => {
    const props = renderGrid();

    fireEvent.click(screen.getByTestId('Cases.SelectAll'));

    expect(props.onSelectionChange).toHaveBeenCalledWith(['c1', 'c2', 'c3']);
  });

  it('is signalled by the row, not only by a 16px tick', () => {
    renderGrid({ selection: ['c2'] });

    const tinted = screen
      .getAllByRole('row')
      .filter((row) => row.style.backgroundColor !== '');

    expect(tinted).toHaveLength(1);
    expect(within(tinted[0]).getByTestId('Cases.Row.KeyLink')).toHaveTextContent('TC-2');
  });
});

describe('the kit’s Table rule (design language §5)', () => {
  it('a failing case says what it failed on in the cell, not in a column of its own', () => {
    renderGrid();

    expect(screen.getByText('Last run failed on nightly-eu')).toBeInTheDocument();
    // ~~11.~~ → **12 since 013 D-i** added the Verifies column. The number is the point rather than
    // the header list: the failure reason is a second line in the Last run cell, and this fails the
    // day somebody gives it a column instead.
    expect(screen.getAllByRole('columnheader')).toHaveLength(12);
  });

  it('a healthy case carries no second line', () => {
    renderGrid();

    expect(screen.queryAllByText(/Last run failed on/)).toHaveLength(1);
  });
});

describe('the grid gives its columns room, and says what is on screen', () => {
  it('declares a floor no narrower than the widths it hints at', () => {
    // The audit's finding 1.3: 1588px of `meta.width` hints inside a 1240px floor, and
    // `table-layout: auto` took the difference out of the one column allowed to wrap. Title
    // measured 137px at 1680.
    renderGrid()
    const table = screen.getByTestId('Cases.Grid').querySelector('table')!
    const declared = [...table.querySelectorAll('thead th')].reduce(
      (sum, th) => sum + parseInt((th as HTMLElement).style.width || '0', 10),
      0,
    )
    const floor = Number(/min-w-\[(\d+)px\]/.exec(table.className)?.[1] ?? 0)
    expect(floor).toBeGreaterThanOrEqual(declared)
  })

  it('gives the title a real floor and not only a hint', () => {
    renderGrid()
    expect(screen.getAllByTestId('Cases.Row.Title')[0].className).toContain('min-w-[300px]')
  })

  it('states the count, the sort and the keys under the table', () => {
    renderGrid()
    const foot = screen.getByTestId('Cases.Foot')
    expect(foot).toHaveTextContent('sorted by')
    expect(foot).toHaveTextContent('Enter open')
  })
})
