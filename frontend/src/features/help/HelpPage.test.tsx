import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

/**
 * The setup blocks — the part of the guide a reader does not read but *takes*.
 *
 * Three things are locked here and none is visible to the build:
 *
 *   1. **No placeholder base URL survives.** The guide is only ever read from the instance it
 *      describes, so `<your-tcms-url>` asked the reader to supply a value the browser already had
 *      — three times, without a typo, in a JSON file another program parses.
 *   2. **The blocks are configuration, not prose.** They are copied whole into an MCP host, so
 *      they are asserted by parsing rather than by matching a sentence.
 *   3. **Both surfaces render the same artefact.** These come from `lib/clientSetup.ts`, which the
 *      issue-token dialog renders too; the assertion that they agree is that file's unit test, and
 *      this one is what fails if a section goes back to hand-writing a block.
 */
describe('HelpPage — the setup blocks', () => {
  /*
    `navigator.clipboard` is replaced outright rather than spied on, because the case worth testing
    is the one where the whole object is missing — so the original descriptor is put back by hand:
    `restoreAllMocks` knows nothing about a `defineProperty`, and a leaked `undefined` clipboard
    would make every later copy test in this file pass for the wrong reason.
  */
  const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    if (clipboardDescriptor) Object.defineProperty(navigator, 'clipboard', clipboardDescriptor);
    else Reflect.deleteProperty(navigator, 'clipboard');
  });

  it('fills the MCP configuration in with this instance’s own address', async () => {
    mockApi();
    renderAt('/help/mcp');

    const block = await screen.findByTestId('Help.Mcp.Hosted.Value');
    const config = JSON.parse(block.textContent ?? '') as {
      mcpServers: { tcms: { url: string; headers: Record<string, string> } };
    };

    expect(config.mcpServers.tcms.url).toBe(`${window.location.origin}/mcp`);
    // The token is the one thing left to fill in, and it is named as such rather than left blank:
    // an empty string in a config file is a host that connects as nobody and says nothing.
    expect(config.mcpServers.tcms.headers.Authorization).toBe('Bearer <your token>');
  });

  it('gives the hosted shape as a config block, not as a bare URL to assemble', async () => {
    // The distribution spec calls hosted the shape to prefer, and the guide used to print it as a
    // URL while the local shape got a block — so the recommended path had more work in it.
    mockApi();
    renderAt('/help/mcp');

    await screen.findByTestId('Help.Mcp.Hosted');
    expect(screen.getByTestId('Help.Mcp.Hosted.Copy')).toBeInTheDocument();

    await userEvent.click(screen.getByTestId('Help.Mcp.Tab.Stdio'));
    const local = await screen.findByTestId('Help.Mcp.Stdio.Value');
    const env = (
      JSON.parse(local.textContent ?? '') as {
        mcpServers: { tcms: { command: string; env: Record<string, string> } };
      }
    ).mcpServers.tcms;
    expect(env.command).toBe('tcms-mcp');
    expect(env.env.TCMS_URL).toBe(window.location.origin);
  });

  it('installs both clients from the instance being read, never from a registry', async () => {
    mockApi();
    renderAt('/help/mcp');

    await userEvent.click(await screen.findByTestId('Help.Mcp.Tab.Stdio'));
    expect(await screen.findByTestId('Help.Mcp.Install.Value')).toHaveTextContent(
      `${window.location.origin}/downloads/tcms-mcp-latest.tgz`,
    );

    renderAt('/help/cli');
    expect(await screen.findByTestId('Help.Cli.Setup.Value')).toHaveTextContent(
      `${window.location.origin}/downloads/tcms-cli-latest.tgz`,
    );
  });

  it('tells a reader how to get the token both surfaces start by requiring', async () => {
    // The step the guide left out entirely: both sections opened by telling the reader to use a
    // credential and neither said where one comes from.
    mockApi();
    renderAt('/help/mcp');

    const section = await screen.findByTestId('Help.Section.mcp');
    expect(within(section).getByRole('link', { name: /API tokens/i })).toHaveAttribute(
      'href',
      '/admin/tokens',
    );
    expect(section).toHaveTextContent(/shown once/i);
    // Named for the reader who cannot follow that link — the thing they take to an administrator.
    expect(section).toHaveTextContent(/token:issue/);
  });

  it('copies the whole configuration in one press', async () => {
    mockApi();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    renderAt('/help/mcp');

    await userEvent.click(await screen.findByTestId('Help.Mcp.Hosted.Copy'));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    const copied = JSON.parse(writeText.mock.calls[0][0] as string) as {
      mcpServers: { tcms: { url: string } };
    };
    expect(copied.mcpServers.tcms.url).toBe(`${window.location.origin}/mcp`);
    expect(await screen.findByTestId('Help.Mcp.Hosted.Status')).toHaveTextContent('Copied.');
  });

  it('says what to do instead when the browser has no clipboard', async () => {
    // A self-hosted first install is reached over plain HTTP, where `navigator.clipboard` is
    // absent entirely — a missing capability, not a rejected promise. The block is still readable
    // and the button has to say so rather than doing nothing.
    mockApi();
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    renderAt('/help/mcp');

    await userEvent.click(await screen.findByTestId('Help.Mcp.Hosted.Copy'));

    expect(await screen.findByTestId('Help.Mcp.Hosted.Status')).toHaveTextContent(
      /select the text/i,
    );
    expect(screen.getByTestId('Help.Mcp.Hosted.Value')).toHaveTextContent('mcpServers');
  });
});
