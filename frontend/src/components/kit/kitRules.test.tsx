import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Badge } from './Badge';
import { Chip } from './Chip';
import { RailRow } from './RailRow';
import { ResultBar } from './ResultBar';

/**
 * One test per rule the kit carries, from `docs/specs/2026-08-28-design-language.md` §5 and §6.
 *
 * These are deliberately not tests of a look. Each pins a decision that a plausible, well-meant
 * refactor undoes silently — a badge given an onClick because a filter needed one, a rail row
 * given a filled background because it "looked unfinished", an untested segment given a fill
 * because the bar looked empty. Every one of those has a measured reason behind it.
 */
describe('badge versus chip (UX#114 / UX#117)', () => {
  it('a badge is state the system owns, so it is not operable', () => {
    render(<Badge tone="passed">Passed</Badge>);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('a chip is a value the reader owns, so it is a real button with a name', () => {
    render(<Chip onClick={vi.fn()}>@smoke</Chip>);
    expect(screen.getByRole('button', { name: /@smoke/ })).toBeInTheDocument();
  });

  it('a chip is operable from the keyboard', async () => {
    const onClick = vi.fn();
    render(<Chip onClick={onClick}>@regression</Chip>);

    screen.getByRole('button', { name: /@regression/ }).focus();
    await userEvent.keyboard('{Enter}');

    expect(onClick).toHaveBeenCalled();
  });
});

/**
 * Design language rule 3, and the one the shipped rail got wrong in both directions at once: it
 * was loud (full outlined selects with a helper paragraph each) where the spec wants quiet, and
 * the render it was built from was a clickable div where the spec wants a control.
 */
describe('the rail row is quiet at rest and still a control', () => {
  it('is a real button with an accessible name', () => {
    render(<RailRow label="Priority" value="Medium" onOpen={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Priority/ })).toBeInTheDocument();
  });

  it('carries no filled surface at rest', () => {
    render(<RailRow label="Priority" value="Medium" onOpen={vi.fn()} />);
    const row = screen.getByRole('button', { name: /Priority/ });
    expect(row.className).not.toMatch(/\bbg-(field|panel|paper|steel)\b/);
    expect(row.className).toMatch(/border-transparent/);
  });

  it('has a visible focus ring', () => {
    render(<RailRow label="Priority" value="Medium" onOpen={vi.fn()} />);
    expect(screen.getByRole('button', { name: /Priority/ }).className).toMatch(
      /focus-visible:outline-2/,
    );
  });

  it('renders a value the author cannot change here as text, not a disabled button', () => {
    render(<RailRow label="Preconditions" value="2 inherited" readOnly />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByText('2 inherited')).toBeInTheDocument();
  });
});

/**
 * §6. `--color-untested` and `--color-skipped` measured 10.7 ΔE apart in OKLab — below the
 * normal-vision floor of 15, before CVD is considered — while sitting adjacent in every result bar
 * in the product. Untested is the absence of a result, so it became the empty track. Adding it
 * back as a fill re-creates a measured accessibility failure, which is why this counts fills.
 */
describe('the result bar', () => {
  it('renders untested as the empty track, never as a fill', () => {
    const { container } = render(<ResultBar passed={2} failed={1} untested={7} />);
    expect(container.querySelectorAll('[data-fill]')).toHaveLength(2);
  });

  it('never emits a fill for untested even when it is the only count', () => {
    const { container } = render(<ResultBar untested={9} />);
    expect(container.querySelectorAll('[data-fill]')).toHaveLength(0);
  });

  it('states the proportion for a reader who cannot see the bar', () => {
    render(<ResultBar passed={2} failed={1} untested={7} />);
    expect(screen.getByRole('img')).toHaveAccessibleName(/2 passed, 1 failed, 7 untested of 10/);
  });

  it('reads an empty bar as never run rather than as zero passed', () => {
    render(<ResultBar />);
    expect(screen.getByRole('img')).toHaveAccessibleName('Never run');
  });
});
