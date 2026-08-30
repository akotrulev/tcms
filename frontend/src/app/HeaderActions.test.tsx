import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { DropdownMenuItem } from '../components/ui/dropdown-menu';
import { Button } from '../components/ui';
import { HeaderActions } from './HeaderActions';

/**
 * The property under test is the one the audit measured: the group does not wrap, and what does
 * not fit is one press away rather than on a second row that lands on the page content.
 */
describe('HeaderActions', () => {
  it('does not wrap — the group is a single non-wrapping row', () => {
    render(
      <HeaderActions data-testid="CaseDetail.Actions">
        <Button size="sm">Edit case</Button>
      </HeaderActions>,
    );
    const group = screen.getByTestId('CaseDetail.Actions');
    expect(group.className).toContain('min-w-0');
    expect(group.className).not.toContain('flex-wrap');
  });

  it('shows no overflow control when there is no overflow', () => {
    render(
      <HeaderActions data-testid="CaseDetail.Actions">
        <Button size="sm">Edit case</Button>
      </HeaderActions>,
    );
    expect(screen.queryByRole('button', { name: 'More actions' })).not.toBeInTheDocument();
  });

  it('reaches a folded action through the menu', async () => {
    render(
      <HeaderActions
        data-testid="CaseDetail.Actions"
        overflow={<DropdownMenuItem>Delete</DropdownMenuItem>}
      >
        <Button size="sm">Edit case</Button>
      </HeaderActions>,
    );
    expect(screen.queryByRole('menuitem', { name: 'Delete' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'More actions' }));
    expect(await screen.findByRole('menuitem', { name: 'Delete' })).toBeInTheDocument();
  });
});
