import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { Segmented } from './Segmented';

type Scope = 'mine' | 'all';

function Harness() {
  const [scope, setScope] = useState<Scope>('mine');
  return (
    <Segmented<Scope>
      label="Scope"
      options={[
        { value: 'mine', label: 'Mine' },
        { value: 'all', label: 'Everyone' },
      ]}
      value={scope}
      onChange={setScope}
      testId="Scope"
    />
  );
}

describe('Segmented', () => {
  it('is a named group whose items report the choice with aria-pressed', () => {
    render(<Harness />);
    expect(screen.getByRole('group', { name: 'Scope' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mine' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Everyone' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('is operable from the keyboard', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.tab();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Everyone' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: 'Everyone' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Mine' })).toHaveAttribute('aria-pressed', 'false');
  });
});
