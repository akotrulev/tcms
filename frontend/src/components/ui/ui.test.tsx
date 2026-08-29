import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Button } from './button';
import { Switch } from './switch';
import { Input } from './input';

/**
 * These are not snapshot tests of a look. Each one pins a rule from
 * `docs/specs/2026-08-28-design-language.md` that a plausible refactor can break silently.
 */
describe('the kit primitives', () => {
  it('takes a data-testid through to the rendered element', () => {
    render(<Button data-testid="Somewhere.Save">Save</Button>);
    expect(screen.getByTestId('Somewhere.Save')).toBeInTheDocument();
  });

  /**
   * ~~The whole reason the slot-prop test-id helper in `lib/` exists~~ → **it does not: T16
   * deleted it, and this is the assertion that let it.** MUI typed a slot's props as that
   * element's known DOM props, which excludes `data-*`, so thirteen call sites went through a
   * helper to put a test id on an input. A plain element takes one directly, which is what this
   * checks.
   *
   * The file's *name* is deliberately not written here. `packages/client/src/test/citedFiles.test.ts`
   * scans this tree for paths named in comments and fails on one that is not on disk — and it
   * reddens the **packages** job, not the frontend one, which is the wrong place to go looking.
   * A superseded decision is normally struck through rather than deleted (CLAUDE.md); here the
   * strike-through carries the reasoning and the path itself has to go.
   */
  it('takes a data-testid through to an input, with no slot-prop workaround', () => {
    render(<Input data-testid="Somewhere.Name" defaultValue="Reflex" />);
    expect(screen.getByTestId('Somewhere.Name')).toHaveValue('Reflex');
  });

  it('defaults to the quiet variant, so a screen has to ask for its one loud action', () => {
    render(<Button data-testid="B">Cancel</Button>);
    expect(screen.getByTestId('B')).not.toHaveClass('bg-steel');
  });

  it('carries the steel background only when asked (design language rule 1)', () => {
    render(
      <Button variant="primary" data-testid="B">
        Create case
      </Button>,
    );
    expect(screen.getByTestId('B')).toHaveClass('bg-steel');
  });

  it('defaults to type=button, so a button inside a form cannot submit it by accident', () => {
    render(<Button data-testid="B">Add a tag</Button>);
    expect(screen.getByTestId('B')).toHaveAttribute('type', 'button');
  });

  /**
   * Design language rule 3 — *a quiet control is still a control*. The rail, the settings toggles
   * and the filter chips are all visually silent at rest, and the failure mode that invites is
   * UX#117's clickable div: something that looks operable, is not reachable by Tab, and reports
   * no state to a screen reader. Keyboard operation is the half a look cannot fake.
   */
  it('operates a switch from the keyboard and reports its state', async () => {
    const onCheckedChange = vi.fn();
    render(<Switch aria-label="Match a suite by Feature: name" onCheckedChange={onCheckedChange} />);

    const control = screen.getByRole('switch', { name: 'Match a suite by Feature: name' });
    expect(control).toHaveAttribute('aria-checked', 'false');

    control.focus();
    await userEvent.keyboard(' ');

    expect(onCheckedChange).toHaveBeenCalledWith(true);
  });

  it('gives every interactive primitive a visible focus ring', () => {
    render(<Button data-testid="B">Edit</Button>);
    expect(screen.getByTestId('B').className).toMatch(/focus-visible:outline-2/);
  });
});
