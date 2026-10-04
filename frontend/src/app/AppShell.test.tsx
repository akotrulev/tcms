import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { PermissionKey, Project } from '../api/types'
import { heldPermissions } from '../test/permissions'
import { createTestQueryClient } from '../test/queryClient'
import { TestProviders } from '../test/TestProviders'
import { AppShell } from './AppShell'
import { NAV } from './nav'

/**
 * The shell's navigation affordances, after the rail became one 48px top bar (023 N1).
 *
 * The failures this file exists to catch:
 *
 * 1. **`/` is reachable from inside a project** — by the launcher's Projects entry, the wordmark and
 *    the switcher's "All projects". The picker is where sign-in lands and where a project the caller
 *    cannot see falls back to.
 * 2. **The gates did not drift.** A gated entry is ABSENT from the launcher exactly as it was absent
 *    from the rail, and an ungated one is listed for everybody. The nine recorded exceptions are
 *    asserted from both ends, because a one-sided test cannot tell an absent entry from a launcher
 *    that failed to render.
 * 3. **The bar is what `nav.ts` says it is.** Tabs, More and the launcher's columns are read off the
 *    model, so this cannot become a second list that agrees with the first until somebody edits one.
 * 4. **Nothing of the old frame is left** — no rail, drawer, hamburger, collapse toggle or footer.
 * 5. **The switcher names the project and carries its colour**, which is the only place a project's
 *    colour is drawn in the shell (§3a, D5).
 */

const OLB = '11111111-1111-4111-8111-111111111111'
const MOB = '11111111-1111-4111-8111-111111111112'

const project = (
  id: string,
  key: string,
  name: string,
  color: Project['color'] = 'project-1',
): Project => ({
  id,
  key,
  name,
  description: null,
  archived: false,
  caseCount: 0,
  myRole: 'Viewer',
  color,
})

const PROJECTS = [
  project(OLB, 'OLB', 'Online Banking', 'project-3'),
  project(MOB, 'MOB', 'Mobile App', 'project-7'),
]

function mockReads(permissions: PermissionKey[], projects: Project[] = PROJECTS) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path === '/projects') return projects
    // Answered with the real shape rather than by the `[]` fallback below, which is an array where
    // the contract declares an object — the contract double catches it, and the failure reads as a
    // navigation defect rather than as a missing arm.
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    // Anchored on the whole sub-resource so a `/cases` prefix does not swallow it (the fixtures
    // rule), and answered with the real `Paged` shape.
    if (/^\/cases\/search(\?|$)/.test(path)) return { items: [], page: 0, size: 25, total: 0 }
    return []
  }) as typeof api.get)
}

function renderShell(route: string, permissions: PermissionKey[] = [], projects: Project[] = PROJECTS) {
  mockReads(permissions, projects)

  return render(
    <TestProviders client={createTestQueryClient()} route={route} userId="user-1">
      <AppShell />
    </TestProviders>,
  )
}

/** The project tabs render once `/projects` has answered and a project is resolved. */
const barReady = () =>
  waitFor(() => expect(screen.getByTestId('Nav.ProjectTabs')).toBeInTheDocument())

const tabs = () => within(screen.getByTestId('Nav.ProjectTabs'))

async function openLauncher() {
  const user = userEvent.setup()
  await user.click(screen.getByTestId('Nav.Launcher'))
  return { user, launcher: within(await screen.findByRole('menu')) }
}

async function openMore() {
  const user = userEvent.setup()
  await user.click(screen.getByTestId('Nav.More'))
  return { user, menu: within(await screen.findByRole('menu')) }
}

const labelsOf = (id: 'you' | 'project' | 'company', placement?: 'tab' | 'more') =>
  NAV.find((zone) => zone.id === id)!.entries.flatMap((entry) =>
    'items' in entry || (placement && entry.placement !== placement) ? [] : [entry.label],
  )

describe('AppShell — the top bar', () => {
  it('is one 48px header, and leaves no rail, drawer, hamburger, collapse toggle or footer', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const headers = screen.getAllByRole('banner')
    expect(headers).toHaveLength(1)
    expect(headers[0]).toHaveClass('h-12')

    for (const gone of [
      'Nav.Rail',
      'Nav.Drawer',
      'Nav.HamburgerButton',
      'Nav.CollapseToggle',
      'AppFooter',
      'AppFooter.HelpLink',
      'TopBar.CompanySlot',
      'Nav.TopStrip',
    ]) {
      expect(screen.queryByTestId(gone)).toBeNull()
    }
    expect(screen.queryByRole('contentinfo')).toBeNull()
  })

  it('links to the project picker from inside a project, by wordmark and by the launcher', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    // Asserted as an href rather than by clicking: the point is that this is a real anchor a user
    // can middle-click and copy.
    expect(screen.getByTestId('Nav.Wordmark')).toHaveAttribute('href', '/')
    const { launcher } = await openLauncher()
    expect(launcher.getByTestId('Nav.Link.Projects')).toHaveAttribute('href', '/')
  })

  it('draws the project tabs in nav.ts order, Work first, and the rest under More', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const rendered = tabs()
      .getAllByRole('link')
      .map((link) => link.textContent)
    expect(rendered).toEqual(labelsOf('project', 'tab'))
    expect(rendered[0]).toBe('Work')

    const { menu } = await openMore()
    const items = menu.getAllByRole('menuitem').map((item) => item.textContent)
    // The folded tabs are in More as `lg:hidden` items, then the `more` entries in model order.
    expect(items).toEqual([
      ...labelsOf('project', 'tab').filter((label) => label !== 'Work'),
      ...labelsOf('project', 'more'),
    ])
    expect(menu.getByTestId('Nav.Link.Settings')).toHaveAttribute(
      'href',
      `/projects/${OLB}/settings`,
    )
  })

  it('spends one entry on Settings, not eleven', async () => {
    renderShell(`/projects/${OLB}/cases`, ['issue:import', 'integration:manage', 'project:audit'])
    await barReady()

    const { menu } = await openMore()
    for (const gone of ['General', 'Workflow', 'Boards', 'Classification', 'Imports', 'Notifications', 'Issue tracker', 'Access & roles']) {
      expect(menu.queryByRole('menuitem', { name: gone })).toBeNull()
    }
  })

  it('marks the current tab with aria-current, and labels More with the current entry inside it', async () => {
    const { unmount } = renderShell(`/projects/${OLB}/cases`)
    await barReady()

    expect(tabs().getByTestId('Nav.Link.Testcases')).toHaveAttribute('aria-current', 'page')
    expect(tabs().getByTestId('Nav.Link.Work')).not.toHaveAttribute('aria-current')
    expect(screen.getByTestId('Nav.More')).toHaveTextContent('More')
    unmount()

    renderShell(`/projects/${OLB}/analytics`)
    await barReady()
    // The current place is never nowhere: when it lives under More, More says so.
    expect(screen.getByTestId('Nav.More')).toHaveTextContent('Analytics')
    expect(tabs().queryByRole('link', { current: 'page' })).toBeNull()
  })

  it('draws no tabs, More or Create when no project resolves, and keeps the launcher', async () => {
    renderShell('/', [], [])
    await screen.findByTestId('Nav.ProjectSwitcher')

    expect(screen.queryByTestId('Nav.ProjectTabs')).toBeNull()
    expect(screen.queryByTestId('Nav.More')).toBeNull()
    expect(screen.queryByTestId('TopBar.Create')).toBeNull()
    expect(screen.getByTestId('Nav.Launcher')).toBeInTheDocument()
    expect(screen.getByTestId('TopBar.MyWork')).toHaveAttribute('href', '/my-work')
  })
})

describe('AppShell — the launcher', () => {
  it('lists You and Company from nav.ts, with each entry’s hint', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const { launcher } = await openLauncher()
    const you = within(launcher.getByTestId('Nav.Launcher.You'))
    expect(you.getAllByRole('menuitem').map((item) => item.querySelector('span > span')?.textContent)).toEqual(
      labelsOf('you'),
    )
    expect(you.getByTestId('Nav.Link.Mywork')).toHaveTextContent('Assigned to you, every project')

    const company = within(launcher.getByTestId('Nav.Launcher.Company'))
    for (const label of ['Org chart', 'Directory', 'Capacity', 'Teams & roles']) {
      expect(company.getByRole('menuitem', { name: new RegExp(`^${label}`) })).toBeInTheDocument()
    }
    expect(launcher.getByText('Only entries you can open are listed.')).toBeInTheDocument()
    expect(launcher.getByTestId('Nav.Launcher.AccessLink')).toHaveAttribute('href', '/account/access')
  })

  it('carries the renamed entries and none of the words they replaced', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    expect(tabs().getByRole('link', { name: 'Documents' })).toBeInTheDocument()
    const { launcher } = await openLauncher()
    expect(launcher.getByRole('menuitem', { name: /^Projects/ })).toBeInTheDocument()
    expect(launcher.getByRole('menuitem', { name: /^Capacity/ })).toBeInTheDocument()
    expect(launcher.queryByRole('menuitem', { name: /^All projects/ })).toBeNull()
    expect(launcher.queryAllByRole('menuitem', { name: /^Staffing/ })).toEqual([])
    expect(launcher.queryAllByRole('menuitem', { name: /^Audit trail/ })).toEqual([])
  })
})

/**
 * The credential entries (005 C2, decided) — **the Administration section of the launcher**, and
 * the rule is unchanged: those pages have no read-only view at all, so what a caller without the key
 * gets is a whole screen whose only content is a refusal. Both directions, because a one-sided test
 * cannot tell an absent entry from a launcher that failed to render.
 */
describe('AppShell — the gated entries (005 C2, 021 §1)', () => {
  it('lists the Administration section and its children for a caller who holds their keys', async () => {
    renderShell(`/projects/${OLB}/cases`, ['token:issue', 'company:idp', 'audit:view'])
    await barReady()

    const { launcher } = await openLauncher()
    await waitFor(() => expect(launcher.getByTestId('Nav.Launcher.Administration')).toBeInTheDocument())
    expect(launcher.getByRole('menuitem', { name: /^API tokens/ })).toBeInTheDocument()
    expect(launcher.getByRole('menuitem', { name: /^Single sign-on/ })).toBeInTheDocument()
    expect(launcher.getByRole('menuitem', { name: /^Company audit/ })).toBeInTheDocument()
  })

  it('draws no Administration section for a caller holding none of its three keys', async () => {
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await barReady()

    const { launcher } = await openLauncher()
    // The launcher really did render the rest of Company — otherwise the absence proves nothing.
    expect(launcher.getByRole('menuitem', { name: /^Org chart/ })).toBeInTheDocument()
    expect(launcher.queryByTestId('Nav.Launcher.Administration')).toBeNull()
    expect(launcher.queryByRole('menuitem', { name: /^API tokens/ })).toBeNull()
    expect(launcher.queryByRole('menuitem', { name: /^Single sign-on/ })).toBeNull()
    expect(launcher.queryByRole('menuitem', { name: /^Company audit/ })).toBeNull()
  })

  it('draws the section for a caller holding ONE of the three, with only that entry in it', async () => {
    renderShell(`/projects/${OLB}/cases`, ['token:issue'])
    await barReady()

    const { launcher } = await openLauncher()
    await waitFor(() => expect(launcher.getByRole('menuitem', { name: /^API tokens/ })).toBeInTheDocument())
    expect(launcher.queryByRole('menuitem', { name: /^Single sign-on/ })).toBeNull()
    expect(launcher.queryByRole('menuitem', { name: /^Company audit/ })).toBeNull()
  })

  it('renders Billing for a caller holding billing:read, in the Company section', async () => {
    renderShell(`/projects/${OLB}/cases`, ['billing:read'])
    await barReady()

    const { launcher } = await openLauncher()
    const billing = await within(launcher.getByTestId('Nav.Launcher.Company')).findByRole('menuitem', {
      name: /^Billing/,
    })
    expect(billing).toHaveAttribute('href', '/billing')
  })

  it.each([
    ['a Test Engineer', ['case:read', 'time:log'] as PermissionKey[]],
    ['an Account Manager', ['client:read', 'time:log'] as PermissionKey[]],
  ])('does not render Billing for %s', async (_who, permissions) => {
    renderShell(`/projects/${OLB}/cases`, permissions)
    await barReady()

    const { launcher } = await openLauncher()
    expect(launcher.getByRole('menuitem', { name: /^Org chart/ })).toBeInTheDocument()
    // ABSENT, not disabled — and the Account Manager arm is the sharper negative: that role reads
    // the client's hours, so seeing the work is not seeing what it costs.
    expect(launcher.queryByRole('menuitem', { name: /^Billing/ })).toBeNull()
    expect(launcher.queryByText('Billing')).toBeNull()
  })
})

describe('AppShell — the project switcher', () => {
  it('names the project in the bar, with its key on its colour', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    /*
      `AppShell` is mounted here without the app's route table, so nothing matches
      `/projects/:projectId` and `useActiveProject` falls back to the first project the caller can
      see — which is the fallback's own behaviour, and what the switcher must say when it fires.
    */
    const switcher = screen.getByTestId('Nav.ProjectSwitcher')
    expect(switcher.closest('header')).not.toBeNull()
    expect(switcher).toHaveTextContent('OLB')
    expect(switcher).toHaveTextContent('Online Banking')
    expect(switcher).toHaveAccessibleName('Project: Online Banking. Switch project')

    const chip = within(switcher).getByTestId('ProjectKeyChip')
    expect(chip).toHaveStyle({ backgroundColor: 'var(--color-project-3)' })
    expect(chip).toHaveClass('text-white')
  })

  it('lists every project on its own colour, marks the current one, and ends with All projects', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    await user.click(screen.getByTestId('Nav.ProjectSwitcher'))
    const popover = within(await screen.findByTestId('Nav.ProjectSwitcher.Popover'))

    const current = popover.getByTestId('Nav.ProjectSwitcher.Option.OLB')
    expect(current).toHaveAttribute('aria-current', 'true')
    expect(within(current).getByTestId('ProjectKeyChip')).toHaveAttribute('data-color', 'project-3')

    const other = popover.getByTestId('Nav.ProjectSwitcher.Option.MOB')
    expect(other).not.toHaveAttribute('aria-current')
    expect(within(other).getByTestId('ProjectKeyChip')).toHaveStyle({
      backgroundColor: 'var(--color-project-7)',
    })

    expect(popover.getByPlaceholderText('Find a project')).toBeInTheDocument()
    expect(popover.getByTestId('Nav.ProjectSwitcher.AllProjects')).toHaveTextContent('All projects')
  })

  it('goes to the chosen project, and to the picker from All projects', async () => {
    const user = userEvent.setup()
    mockReads([])
    function Probe() {
      const location = useLocation()
      return <span data-testid="Probe.Location">{location.pathname}</span>
    }
    render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${OLB}/cases`} userId="user-1">
        <AppShell />
        <Probe />
      </TestProviders>,
    )
    await barReady()

    await user.click(screen.getByTestId('Nav.ProjectSwitcher'))
    await user.click(await screen.findByTestId('Nav.ProjectSwitcher.Option.MOB'))
    await waitFor(() => expect(screen.getByTestId('Probe.Location')).toHaveTextContent(`/projects/${MOB}`))

    await user.click(screen.getByTestId('Nav.ProjectSwitcher'))
    await user.click(await screen.findByTestId('Nav.ProjectSwitcher.AllProjects'))
    await waitFor(() => expect(screen.getByTestId('Probe.Location')).toHaveTextContent(/^\/$/))
  })
})

/**
 * Create (023 D13): only routes that exist, each gated on the key its list page's New button uses,
 * and **disabled rather than hidden** (§31) — a Viewer seeing a greyed "Release" learns the
 * capability exists; seeing none learns the product cannot do it.
 */
describe('AppShell — Create', () => {
  async function openCreate() {
    const user = userEvent.setup()
    await user.click(screen.getByTestId('TopBar.Create'))
    return within(await screen.findByRole('menu'))
  }

  it('offers Issue, Test case and Release as links for a caller who holds their keys', async () => {
    renderShell(`/projects/${OLB}/cases`, ['issue:create', 'case:create', 'release:manage'])
    await barReady()

    const menu = await openCreate()
    await waitFor(() =>
      expect(menu.getByTestId('TopBar.Create.Issue')).toHaveAttribute('href', `/projects/${OLB}/work/new`),
    )
    expect(menu.getByTestId('TopBar.Create.Testcase')).toHaveAttribute('href', `/projects/${OLB}/cases/new`)
    expect(menu.getByTestId('TopBar.Create.Release')).toHaveAttribute('href', `/projects/${OLB}/releases/new`)
  })

  it('keeps every item, disabled, for a caller holding none of the keys', async () => {
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await barReady()

    const menu = await openCreate()
    // `PermissionGate` remounts the control it gates when the verdict lands, so the query is inside
    // the `waitFor`: a node captured earlier is a detached element.
    for (const id of ['Issue', 'Testcase', 'Release']) {
      await waitFor(() =>
        expect(menu.getByTestId(`TopBar.Create.${id}`)).toHaveAttribute('aria-disabled', 'true'),
      )
      expect(menu.getByTestId(`TopBar.Create.${id}`)).not.toHaveAttribute('href')
    }
  })

  it('is an icon below md without losing its accessible name', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const create = screen.getByTestId('TopBar.Create')
    expect(create).toHaveAccessibleName('Create')
    expect(within(create).getByText('Create')).toHaveClass('max-md:hidden')
  })
})

/**
 * The bar's controls: the palette trigger, its two shortcuts, and what the palette lists. The
 * regression that matters is unchanged since 021 §7 — a search that costs a navigation before it can
 * be typed into — so the tests open the palette by its two shortcuts and by the trigger, and assert
 * that a query with no match still reaches `/search`.
 */
describe('AppShell bar controls', () => {
  const probe = () => screen.getByTestId('Probe.Location').textContent

  function renderWithProbe(route: string) {
    mockReads([])

    function Probe() {
      const location = useLocation()
      return <span data-testid="Probe.Location">{`${location.pathname}${location.search}`}</span>
    }

    return render(
      <TestProviders client={createTestQueryClient()} route={route} userId="user-1">
        <AppShell />
        <Probe />
      </TestProviders>,
    )
  }

  it('opens the palette with the trigger, and lands a query with no match on /search', async () => {
    const user = userEvent.setup()
    renderWithProbe(`/projects/${OLB}/cases`)
    await barReady()

    const trigger = screen.getByTestId('TopBar.Search')
    expect(trigger.closest('header')).not.toBeNull()
    expect(trigger).toHaveAccessibleName('Search or jump to…')
    expect(trigger.closest('[role="search"]')).not.toBeNull()

    await user.click(trigger)
    await user.type(screen.getByTestId('Palette.Input'), 'login timeout')
    await user.click(await screen.findByText(/Search all test cases for/))

    await waitFor(() => expect(probe()).toBe('/search?q=login+timeout'))
  })

  it.each([
    ['{Meta>}k{/Meta}', 'command'],
    ['{Control>}k{/Control}', 'control'],
    ['/', 'slash'],
  ])('opens on %s', async (keys) => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    await user.keyboard(keys)

    expect(await screen.findByTestId('Palette.Input')).toBeInTheDocument()
  })

  it('does not fire the slash shortcut while the caret is in a text field', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const field = document.createElement('input')
    document.body.append(field)
    await user.click(field)
    await user.keyboard('a/b')

    // Both halves matter: the palette stayed shut AND the character was typed.
    expect(screen.queryByTestId('Palette.Input')).toBeNull()
    expect(field).toHaveValue('a/b')
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const trigger = screen.getByTestId('TopBar.Search')
    await user.click(trigger)
    expect(await screen.findByTestId('Palette.Input')).toBeInTheDocument()

    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByTestId('Palette.Input')).toBeNull())
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('offers places by name, including the tabs and settings sections the bar does not list', async () => {
    const user = userEvent.setup()
    renderWithProbe(`/projects/${OLB}/cases`)
    await barReady()

    await user.click(screen.getByTestId('TopBar.Search'))
    await user.type(screen.getByTestId('Palette.Input'), 'suites')

    await user.click(await screen.findByText('Suites'))

    await waitFor(() => expect(probe()).toBe(`/projects/${OLB}/suites`))
  })

  it('hides a gated place from the palette exactly as the launcher hides it', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    await user.click(screen.getByTestId('TopBar.Search'))
    await user.type(screen.getByTestId('Palette.Input'), 'billing')

    await waitFor(() => expect(screen.queryByText('Billing')).toBeNull())
  })

  it('jumps to an issue typed as a key', async () => {
    const user = userEvent.setup()
    renderWithProbe(`/projects/${OLB}/cases`)
    await barReady()

    await user.click(screen.getByTestId('TopBar.Search'))
    await user.type(screen.getByTestId('Palette.Input'), 'ACME-118')
    await user.click(await screen.findByText('ACME-118'))

    await waitFor(() => expect(probe()).toBe('/issues/ACME-118'))
  })

  it('links My work from the bar, without a count', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const link = screen.getByTestId('TopBar.MyWork')
    expect(link).toHaveAttribute('href', '/my-work')
    expect(link).toHaveAccessibleName('My work')
    expect(link.textContent).toBe('')
  })

  it('switches the theme from the bar', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const toggle = screen.getByTestId('TopBar.ThemeToggle')
    const before = toggle.getAttribute('aria-label')
    await user.click(toggle)
    expect(screen.getByTestId('TopBar.ThemeToggle').getAttribute('aria-label')).not.toBe(before)
  })
})

/**
 * The user menu — the person's own things (021 R11), now behind a 32px avatar, and the build the
 * footer used to carry. Asserted from both ends: the launcher carries neither timesheet entry, and
 * a menu entry added without removing the other is the ordinary way a move half-lands.
 */
describe('AppShell — the user menu (021 R11, 023 §3)', () => {
  async function openMenu() {
    const user = userEvent.setup()
    await user.click(screen.getByTestId('TopBar.UserMenuButton'))
    return user
  }

  it('is an avatar named for the account, showing initials', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const button = screen.getByTestId('TopBar.UserMenuButton')
    expect(button.getAttribute('aria-label')).toMatch(/^Account: /)
    expect(button.textContent).toMatch(/^[A-Z?]{1,2}$/)
  })

  it('carries Timesheets and Timesheet approvals, and the launcher carries neither', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()

    const { launcher } = await openLauncher()
    expect(launcher.queryByRole('menuitem', { name: /Timesheets/ })).toBeNull()
    await userEvent.setup().keyboard('{Escape}')

    await openMenu()

    expect(await screen.findByTestId('TopBar.TimesheetsLink')).toHaveAttribute('href', '/timesheets')
    expect(screen.getByTestId('TopBar.TimesheetApprovalsLink')).toHaveAttribute(
      'href',
      '/timesheets/approvals',
    )
  })

  /**
   * Approvals is listed for **everybody** and carries **no count**: the page explains its own
   * refusal (§31), and the count would come from a read that 403s for anyone without `time:approve`
   * — a PROJECT-scope grant, so there is no company-wide question this shell could ask first.
   */
  it('lists approvals for a caller who can approve nothing, with no count beside it', async () => {
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await barReady()
    await openMenu()

    const link = await screen.findByTestId('TopBar.TimesheetApprovalsLink')
    expect(link.textContent).toBe('Timesheet approvals')
  })

  it('names the help entry User guide, and shows the version above Sign out', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await barReady()
    await openMenu()

    const help = await screen.findByTestId('TopBar.HelpLink')
    expect(help).toHaveTextContent('User guide')
    expect(help).toHaveAttribute('href', '/help')
    expect(screen.getByTestId('TopBar.AccessLink')).toHaveAttribute('href', '/account/access')

    const version = screen.getByTestId('TopBar.Version')
    expect(version).toHaveTextContent(`TCMS ${__APP_VERSION__}`)
    const signOut = screen.getByTestId('TopBar.SignOutButton')
    expect(version.compareDocumentPosition(signOut) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
