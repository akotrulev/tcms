import { render, screen, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import type { Role } from '../../api/types';
import { allPermissions } from '../../lib/permissions';
import { createTestQueryClient } from '../../test/queryClient';
import { TestProviders } from '../../test/TestProviders';
import { HelpPage } from './HelpPage';
import { helpSections } from './sections';

/**
 * The user guide — and the four things about it that no type, gate or lint rule can see.
 *
 *   1. **Section ids are URLs and must stay unique and stable.** They are pasted into tickets and
 *      quoted from onboarding notes. A duplicate id silently makes one section unreachable, and
 *      nothing else in the build would notice.
 *   2. **A bad id redirects, it does not 404.** A guide that answers a mistyped or retired section
 *      with the not-found page teaches the reader the help is broken rather than that they took a
 *      wrong turn.
 *   3. **The role matrix is READ FROM THE SERVER, not written down.** This is the test that stops
 *      the sixth hand-copy of the permission matrix appearing on the one page whose whole purpose
 *      is telling users the truth about their access — so it asserts the table follows the
 *      response, including a grant the spec does NOT have.
 *   4. **Every section renders.** Eleven bodies of hand-written JSX are eleven chances to leave a
 *      component unclosed or a hook where it does not belong, and nobody opens all eleven by hand.
 */

const VIEWER: Role = {
  id: 'role-viewer',
  name: 'Viewer',
  scope: 'PROJECT',
  builtIn: true,
  assignable: true,
  description: 'Read-only access to a project.',
  permissions: ['case:read', 'project:read'],
};

const LEAD: Role = {
  id: 'role-lead',
  name: 'Test Lead',
  scope: 'PROJECT',
  builtIn: true,
  assignable: true,
  description: 'Everything a Test Engineer can do, plus approvals and project configuration.',
  permissions: ['case:read', 'project:read', 'case:edit', 'case:approve', 'project:members'],
};

const AGENT: Role = {
  id: 'role-agent',
  name: 'Agent',
  scope: 'PROJECT',
  builtIn: true,
  assignable: false,
  description: 'Carried by an API token issued for an AI agent. Never granted to a person.',
  permissions: ['case:read', 'project:read', 'case:edit'],
};

function mockApi(roles: Role[] = [VIEWER, LEAD, AGENT]) {
  return vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path.startsWith('/roles')) return roles;
    throw new Error(`unmocked read: ${path}`);
  }) as never);
}

function renderAt(route: string) {
  return render(
    <TestProviders client={createTestQueryClient()} route={route}>
      <Routes>
        <Route path="/help" element={<HelpPage />} />
        <Route path="/help/:sectionId" element={<HelpPage />} />
      </Routes>
    </TestProviders>,
  );
}

describe('HelpPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('gives every section a unique, url-safe id', () => {
    const ids = helpSections.map((section) => section.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const id of ids) expect(id).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
  });

  it('lands /help on the first section rather than a contents page of its own', async () => {
    mockApi();
    renderAt('/help');

    expect(await screen.findByTestId(`Help.Section.${helpSections[0]!.id}`)).toBeInTheDocument();
  });

  it('redirects an unknown section id to the first section instead of showing not-found', async () => {
    mockApi();
    renderAt('/help/a-section-that-was-renamed');

    expect(await screen.findByTestId(`Help.Section.${helpSections[0]!.id}`)).toBeInTheDocument();
  });

  it.each(helpSections.map((section) => [section.id, section.title] as const))(
    'renders the %s section',
    async (id, title) => {
      mockApi();
      renderAt(`/help/${id}`);

      const section = await screen.findByTestId(`Help.Section.${id}`);
      expect(within(section).getByRole('heading', { level: 2, name: title })).toBeInTheDocument();
    },
  );

  it('marks the section being read as the current one in the contents', async () => {
    mockApi();
    const target = helpSections[2]!;
    renderAt(`/help/${target.id}`);

    const link = await screen.findByTestId(`Help.Contents.Link.${target.id}`);
    expect(link).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId(`Help.Contents.Link.${helpSections[0]!.id}`)).not.toHaveAttribute(
      'aria-current',
    );
  });

  it('links on to the next section so the guide reads straight through', async () => {
    mockApi();
    renderAt(`/help/${helpSections[0]!.id}`);

    const next = await screen.findByTestId('Help.Pager.Next');
    expect(next).toHaveTextContent(helpSections[1]!.title);
    expect(screen.queryByTestId('Help.Pager.Previous')).not.toBeInTheDocument();
  });
});

describe('HelpPage — the role matrix', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('orders the roles least-permissioned first, whatever order the server sent', async () => {
    mockApi([LEAD, AGENT, VIEWER]);
    renderAt('/help/roles');

    const table = await screen.findByTestId('Help.RoleMatrix.Table');
    const headers = within(table).getAllByRole('columnheader').map((cell) => cell.textContent);
    expect(headers).toEqual(['What it lets you do', 'Viewer', 'Agent', 'Test Lead']);
  });

  it('reads the grants from the server rather than from a table written into this app', async () => {
    // THE HEADLINE. `Viewer` does not hold `case:edit` anywhere in the spec — so if this cell
    // renders as granted, the matrix is following the response and not a copy of the catalogue.
    const inventedViewer: Role = { ...VIEWER, permissions: [...VIEWER.permissions, 'case:edit'] };
    mockApi([inventedViewer, LEAD]);
    renderAt('/help/roles');

    const row = await screen.findByTestId('Help.RoleMatrix.Row.case:edit');
    expect(within(row).getByLabelText('Viewer can edit test cases')).toBeInTheDocument();
  });

  it('shows a permission no role holds as held by nobody, rather than dropping the row', async () => {
    mockApi([VIEWER, LEAD]);
    renderAt('/help/roles');

    // `case:approve` is on `LEAD` and not on `VIEWER`: the row exists and the Viewer cell is a dash.
    const row = await screen.findByTestId('Help.RoleMatrix.Row.case:approve');
    expect(within(row).getByLabelText('Viewer cannot approve test cases')).toBeInTheDocument();
    expect(within(row).getByLabelText('Test Lead can approve test cases')).toBeInTheDocument();
  });

  it('omits a permission no role on this instance holds at all', async () => {
    // The rows are the catalogue narrowed to what the response actually grants. A server whose
    // seed lacks a permission shows a shorter table, not fifty rows of dashes.
    mockApi([VIEWER]);
    renderAt('/help/roles');

    await screen.findByTestId('Help.RoleMatrix.Table');
    expect(screen.queryByTestId('Help.RoleMatrix.Row.case:approve')).not.toBeInTheDocument();
    expect(screen.getByTestId('Help.RoleMatrix.Row.case:read')).toBeInTheDocument();
  });

  it('renders the whole catalogue when a role holds every permission', async () => {
    const instanceAdmin: Role = {
      id: 'role-instance-admin',
      name: 'Instance Admin',
      scope: 'SYSTEM',
      builtIn: true,
      assignable: true,
      description: 'Holds every permission on the instance.',
      permissions: [...allPermissions],
    };
    mockApi([VIEWER, instanceAdmin]);
    renderAt('/help/roles');

    const table = await screen.findByTestId('Help.RoleMatrix.Table');
    expect(within(table).getAllByTestId(/^Help\.RoleMatrix\.Row\./)).toHaveLength(
      allPermissions.length,
    );
  });

  it('says which roles are carried by a credential and never granted to a person', async () => {
    mockApi([VIEWER, AGENT]);
    renderAt('/help/roles');

    await screen.findByTestId('Help.RoleMatrix.Table');
    expect(screen.getByText('Credentials only — never granted to a person')).toBeInTheDocument();
    expect(screen.getByText(AGENT.description)).toBeInTheDocument();
  });
});
