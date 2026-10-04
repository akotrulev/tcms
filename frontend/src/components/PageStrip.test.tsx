import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { ShellFrameProvider } from '../app/shellFrame';
import { PageStrip } from './PageStrip';

function renderAt(path: string, strip: React.ReactElement) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ShellFrameProvider value={{ projectId: 'p1', projectKey: 'ACME', projectName: 'Acme Web' }}>
        {strip}
      </ShellFrameProvider>
    </MemoryRouter>,
  );
}

describe('PageStrip', () => {
  it('renders no h1 without a title', () => {
    renderAt('/projects/p1/cases', <PageStrip testId="Strip" />);
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument();
  });

  it('keeps the title as a visually hidden h1 when a lead replaces it', () => {
    renderAt(
      '/projects/p1/cases',
      <PageStrip title="Work" lead={<span>Board picker</span>} testId="Strip" />,
    );
    const heading = screen.getByRole('heading', { level: 1, name: 'Work' });
    expect(heading).toHaveClass('sr-only');
    expect(screen.getByText('Board picker')).toBeInTheDocument();
  });

  it('draws the ancestors only: no root, no project root, no last crumb', () => {
    renderAt('/projects/p1/cases/c1/history', <PageStrip title="History" testId="Strip" />);
    const trail = screen.getByTestId('Strip.Trail');
    const links = within(trail).getAllByRole('link');
    expect(links.map((link) => link.textContent)).toEqual(['Test cases', 'Case']);
    expect(within(trail).queryByText('Projects')).not.toBeInTheDocument();
    expect(within(trail).queryByText('ACME')).not.toBeInTheDocument();
    expect(within(trail).queryByText('History')).not.toBeInTheDocument();
  });

  it('draws nothing when no ancestor is left', () => {
    renderAt('/projects/p1/cases', <PageStrip title="Test cases" testId="Strip" />);
    expect(screen.queryByTestId('Strip.Trail')).not.toBeInTheDocument();
  });

  it('draws no trail when asked not to', () => {
    renderAt('/projects/p1/cases/c1/history', <PageStrip title="History" trail={false} testId="Strip" />);
    expect(screen.queryByTestId('Strip.Trail')).not.toBeInTheDocument();
  });
});
