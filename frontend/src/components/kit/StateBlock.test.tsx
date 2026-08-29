import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { StateBlock } from './StateBlock';

/**
 * `docs/specs/2026-08-28-design-language.md` §7 — six states, not five.
 *
 * The load-bearing assertion is the first one. A 200 with the wrong shape rendering as *no
 * results* is the quietest data-loss bug a client can have: nothing errors, nothing is logged, and
 * the reader concludes their filter is too narrow. `tcmsFaults.drift()` exists to provoke exactly
 * that, and this is the check that says what it must look like when it does.
 */
describe('StateBlock', () => {
  it('draws failed and empty so they cannot be mistaken for each other', () => {
    const failed = render(
      <StateBlock kind="failed" reference="A3F19C" timestamp="2026-08-29T10:00:00Z" />,
    );
    const failedText = failed.container.textContent ?? '';
    failed.unmount();

    const empty = render(<StateBlock kind="empty" hidden={1284} filters={3} />);
    const emptyText = empty.container.textContent ?? '';

    expect(failedText).not.toEqual(emptyText);
    expect(failedText).toMatch(/A3F19C/);
    expect(emptyText).not.toMatch(/A3F19C/);
  });

  it('gives a failure a reference and a time, so it can be chased', () => {
    render(<StateBlock kind="failed" reference="A3F19C" timestamp="2026-08-29T10:00:00Z" />);
    expect(screen.getByText(/A3F19C/)).toBeInTheDocument();
  });

  it('counts what the filters hid rather than claiming there is nothing', () => {
    render(<StateBlock kind="empty" hidden={1284} filters={3} onClearFilters={vi.fn()} />);
    const text = document.body.textContent ?? '';
    expect(text).toMatch(/1[\s ]?284/);
    expect(text).toMatch(/3/);
  });

  /**
   * Refused is not an error, and that is the whole point of it being its own state. Retrying a
   * permission you do not hold is not a recovery path, and drawing it red teaches people the
   * product is broken when it is working exactly as configured.
   */
  it('names the permission and someone who can grant it, and offers no retry', () => {
    render(<StateBlock kind="refused" permission="case:edit" grantee="M. Kalcheva" />);

    expect(screen.getByText(/case:edit/)).toBeInTheDocument();
    expect(screen.getByText(/M\. Kalcheva/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry/i })).toBeNull();
  });

  /**
   * The common path on this codebase, not an edge case: `005-renders-backend-support` C1b dropped
   * the endpoint that would answer *who can grant this here*, so there is usually no name to give.
   * The permission is still named — that is the clause the reader can repeat to somebody — and the
   * ask degrades to the role rather than to nothing.
   */
  it('still names the permission when no individual can be named', () => {
    render(<StateBlock kind="refused" permission="case:edit" />);

    expect(screen.getByText(/case:edit/)).toBeInTheDocument();
    expect(screen.getByText(/Company Admin/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry/i })).toBeNull();
  });

  it('does not dress a refusal as a failure', () => {
    const { container } = render(
      <StateBlock kind="refused" permission="case:edit" grantee="M. Kalcheva" />,
    );
    expect(container.innerHTML).not.toMatch(/--color-failed/);
  });
});
