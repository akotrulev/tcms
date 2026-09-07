import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { PermissionKey, Project } from '../api/types'
import { heldPermissions } from '../test/permissions'
import { createTestQueryClient } from '../test/queryClient'
import { TestProviders } from '../test/TestProviders'
import { AppShell } from './AppShell'
import { NAV } from './nav'
import { TopStripSlot } from './topStripSlot'

/**
 * The shell's navigation affordances.
 *
 * The failures this file exists to catch:
 *
 * 1. **`/` is reachable from inside a project.** The picker is where sign-in lands and where a
 *    project the caller cannot see falls back to, and until it had a rail entry it was reachable
 *    only by editing the address bar. A regression here is invisible in every other test in the
 *    suite, because every other test renders a page rather than the shell around it.
 * 2. **The current-location marker is on the right item.** `/` is a prefix of every path in the
 *    app, so this is where a nav item that claims to be current on twenty screens at once would
 *    show up. **Stated honestly: this one does not catch a dropped `end`** — `NavLink`
 *    special-cases a root link and refuses the prefix match on its own, so removing `end: true`
 *    from the `Projects` entry leaves this green. It pins the behaviour, not the attribute that
 *    expresses it.
 * 3. **The collapsed rail keeps its accessible names.** Collapsing removes the label text from the
 *    DOM. An icon-only link with no `aria-label` is announced as its href, and the tooltip does not
 *    stand in for one — it never reaches a reader that is not hovering. This is the defect that is
 *    completely invisible on screen, where the icons look fine.
 * 4. **The preference survives a reload.** A collapse that resets on every page load is a gesture,
 *    not a setting.
 * 5. **The drawer has no collapse toggle.** The small-screen variant is already a deliberate
 *    reveal; a toggle there would offer to take the labels away from the users with the least room
 *    to guess at icons.
 * 6. **The rail is what `nav.ts` says it is** (021 D-h). The order of the project zone is the
 *    decision R8 made — Work first, Overview seventh — and an order asserted by transcribing the
 *    labels into this file would be a second copy that agrees with the first until somebody edits
 *    one. The test below reads the model and asserts the *rendering* matches it.
 */

const OLB = '11111111-1111-4111-8111-111111111111'
const MOB = '11111111-1111-4111-8111-111111111112'

const project = (id: string, key: string, name: string): Project => ({
  id,
  key,
  name,
  description: null,
  archived: false,
  caseCount: 0,
  myRole: 'Viewer',
})

const PROJECTS = [project(OLB, 'OLB', 'Online Banking'), project(MOB, 'MOB', 'Mobile App')]

function renderShell(route: string, permissions: PermissionKey[] = []) {
  vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
    if (path === '/projects') return PROJECTS
    // The rail reads permissions since §242, to decide whether the two audit entries exist at all
    // — and, since 005 C2, the credential entries as well.
    // Answered with the real shape rather than by the `[]` fallback below, which is an array where
    // the contract declares an object — the contract double catches it, and the failure reads as a
    // navigation defect rather than as a missing arm.
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
    // The palette's case arm. Anchored on the whole sub-resource so a `/cases` prefix does not
    // swallow it (the fixtures rule), and answered with the real `Paged` shape — an `[]` here is
    // an array where the contract declares an object, which the contract double catches as a
    // shape failure rather than as the navigation defect it would look like on screen.
    if (/^\/cases\/search(\?|$)/.test(path)) return { items: [], page: 0, size: 25, total: 0 }
    return []
  }) as typeof api.get)

  return render(
    <TestProviders client={createTestQueryClient()} route={route} userId="user-1">
      <AppShell />
    </TestProviders>,
  )
}

/** The rail and the drawer render the same content, so every query must name which one. */
const rail = () => within(screen.getByTestId('Nav.Rail'))

/**
 * Waits for the *project* zone, not for the switcher.
 *
 * The switcher and the You zone render before `/projects` answers, so waiting on one of those
 * returns while the project zone is still absent and every project-scoped assertion below fails on
 * a rail that was merely early rather than wrong.
 */
const railReady = () => waitFor(() => expect(rail().getByTestId('Nav.Link.Testcases')).toBeInTheDocument())

/** `aria-current` sits on the box inside the anchor, alongside the icon — not on the anchor. */
const currentMarkerIn = (link: HTMLElement) => link.querySelector('[aria-current="page"]')

/** The labels of one zone's rows, in the order `nav.ts` declares them. */
const labelsOf = (id: 'you' | 'project' | 'company') =>
  NAV.find((zone) => zone.id === id)!.entries.flatMap((entry) =>
    'items' in entry ? [] : [entry.label],
  )

describe('AppShell navigation', () => {
  it('links to the project picker from inside a project, by nav item and by wordmark', async () => {
    renderShell(`/projects/${OLB}/cases`)

    await railReady()

    // Asserted as an href rather than by clicking: the point is that this is a real anchor a user
    // can middle-click and copy, which an onClick handler would satisfy neither of.
    expect(rail().getByTestId('Nav.Link.Projects')).toHaveAttribute('href', '/')
    expect(rail().getByTestId('Nav.Wordmark')).toHaveAttribute('href', '/')
  })

  it('marks Projects current on the picker and NOT on a project route', async () => {
    const { unmount } = renderShell(`/projects/${OLB}/cases`)
    await railReady()

    expect(currentMarkerIn(rail().getByTestId('Nav.Link.Projects'))).toBeNull()

    unmount()

    renderShell('/')
    await railReady()
    expect(currentMarkerIn(rail().getByTestId('Nav.Link.Projects'))).not.toBeNull()
  })

  /**
   * The three zones, and the order inside the project zone (021 §1, R8).
   *
   * Read off `NAV` rather than retyped, so this cannot become a second list that disagrees with
   * the first. What it actually pins is that the rail RENDERS the model in the model's order —
   * every row present, none invented, `Work` first and `Overview` seventh — which is the whole of
   * the usage-order decision and is otherwise only visible by eye.
   */
  it('renders the three zones, and the project zone in nav.ts order', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    expect(rail().getByTestId('Nav.Zone.you')).toBeInTheDocument()
    expect(rail().getByTestId('Nav.Zone.project')).toBeInTheDocument()
    expect(rail().getByTestId('Nav.Zone.company')).toBeInTheDocument()

    const rendered = within(rail().getByTestId('Nav.Zone.project'))
      .getAllByRole('link')
      .map((link) => link.textContent)
    expect(rendered).toEqual(labelsOf('project'))

    // The decision, said out loud: the board is where the day starts, and the dashboard is read
    // rather than worked.
    expect(rendered[0]).toBe('Work')
    expect(rendered[6]).toBe('Overview')
  })

  it('carries the renamed rows and none of the words they replaced', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    // §8.1's twelve renames, at the six places the rail is the thing that shows them.
    expect(rail().getByRole('link', { name: 'Projects' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Documents' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Test reports' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Team' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Capacity' })).toBeInTheDocument()

    // The duplicate labels the brief found: one word, two scopes, two screens. Both are gone.
    expect(rail().queryByRole('link', { name: 'All projects' })).toBeNull()
    expect(rail().queryByRole('link', { name: 'Documentation' })).toBeNull()
    expect(rail().queryAllByRole('link', { name: 'Staffing' })).toEqual([])
    expect(rail().queryAllByRole('link', { name: 'Audit trail' })).toEqual([])
  })

  /**
   * §5: eleven rail siblings became one row leading to a landing page. The rows are named here
   * rather than read from `SETTINGS_SECTIONS` on purpose — the claim is that they are NOT in the
   * rail any more, and a list read from the model would pass on an empty model.
   */
  it('spends one row on Settings, not eleven', async () => {
    renderShell(`/projects/${OLB}/cases`, ['issue:import', 'integration:manage', 'project:audit'])
    await railReady()

    expect(rail().getByTestId('Nav.Link.Settings')).toHaveAttribute(
      'href',
      `/projects/${OLB}/settings`,
    )
    for (const gone of ['General', 'Workflow', 'Boards', 'Classification', 'Imports', 'Notifications', 'Issue tracker', 'Access & roles']) {
      expect(rail().queryByRole('link', { name: gone })).toBeNull()
    }
  })

  it('collapses to icons, keeping every link an accessible name', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)

    await railReady()

    const toggle = rail().getByTestId('Nav.CollapseToggle')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(rail().getByTestId('Nav.Link.Testcases')).toHaveTextContent('Test cases')

    await user.click(toggle)

    expect(screen.getByTestId('Nav.Rail')).toHaveAttribute('data-collapsed', 'true')
    expect(rail().getByTestId('Nav.CollapseToggle')).toHaveAttribute('aria-expanded', 'false')

    // The label text is gone from the DOM...
    expect(rail().getByTestId('Nav.Link.Testcases')).not.toHaveTextContent('Test cases')
    // ...so every link must carry it as an accessible name instead. Queried by role+name, which is
    // what a screen reader does — a testid lookup would pass on a link announced as its href.
    expect(rail().getByRole('link', { name: 'Test cases' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Projects' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'My work' })).toBeInTheDocument()
  })

  /**
   * The collapse is a **label swap and nothing else** (§1's collapsed-state requirement, and the
   * property the withdrawn subject folders could not give). Every row that is in the expanded rail
   * is in the collapsed one, in the same order — nothing moves depth, nothing disappears.
   */
  it('shows the same rows in the same order at 64px', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    const expanded = within(rail().getByTestId('Nav.Zone.project'))
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'))

    await user.click(rail().getByTestId('Nav.CollapseToggle'))

    const collapsed = within(rail().getByTestId('Nav.Zone.project'))
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'))

    expect(collapsed).toEqual(expanded)
  })

  it('remembers the collapsed rail across a reload', async () => {
    const user = userEvent.setup()
    const { unmount } = renderShell(`/projects/${OLB}/cases`)

    await railReady()
    await user.click(rail().getByTestId('Nav.CollapseToggle'))
    expect(screen.getByTestId('Nav.Rail')).toHaveAttribute('data-collapsed', 'true')

    // A fresh mount is what a reload is, from the shell's point of view.
    unmount()
    renderShell(`/projects/${OLB}/cases`)

    await waitFor(() => expect(screen.getByTestId('Nav.Rail')).toHaveAttribute('data-collapsed', 'true'))
  })

  it('offers no collapse toggle in the small-screen drawer', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)

    await railReady()
    await user.click(screen.getByTestId('Nav.HamburgerButton'))

    const drawer = within(await screen.findByTestId('Nav.Drawer'))
    // The drawer really did render the rest of the rail — otherwise the absence below proves
    // nothing about the toggle.
    expect(drawer.getByTestId('Nav.Link.Projects')).toHaveAttribute('href', '/')
    expect(drawer.queryByTestId('Nav.CollapseToggle')).not.toBeInTheDocument()
  })
})

/**
 * The project switcher, back in the rail as the Project zone's header (021 R1).
 *
 * ~~"puts the project switcher in the header"~~ — that test asserted the 2026-08-29 placement and
 * **inverts here**, which is the honest shape for a reversal: the old assertion is not deleted and
 * quietly replaced, it is the same two-ended claim pointed the other way. Both ends are asserted
 * so this cannot pass on a switcher that simply failed to render.
 */
describe('AppShell — the project switcher (021 R1)', () => {
  it('is the Project zone header in the rail, and is not in the page header', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    /*
      `AppShell` is mounted here without the app's route table, so nothing matches
      `/projects/:projectId` and `useActiveProject` falls back to the first project the caller can
      see — which is the fallback's own behaviour, and what the switcher must say when it fires.
    */
    const switcher = rail().getByTestId('Nav.ProjectSwitcher')
    expect(switcher).toHaveTextContent('Online Banking')
    expect(switcher.closest('header')).toBeNull()
    expect(within(rail().getByTestId('Nav.Zone.project')).getByTestId('Nav.ProjectSwitcher')).toBe(
      switcher,
    )
  })

  it('becomes the project key chip when the rail collapses, still opening the same control', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    await user.click(rail().getByTestId('Nav.CollapseToggle'))

    const chip = rail().getByTestId('Nav.ProjectSwitcher')
    // The key, not the name: at 64px there is no room for one and the key is what the rest of the
    // product identifies a project by.
    expect(chip).toHaveTextContent('OLB')
    expect(chip).not.toHaveTextContent('Online Banking')
    // Still the switcher — the name says which project AND that it can be changed.
    expect(chip).toHaveAccessibleName('Project: Online Banking. Switch project')
  })
})

/**
 * The credential entries (005 C2, decided) — **now the Administration folder and two settings
 * sections**, and the rule is unchanged.
 *
 * Five comments used to argue in `AppShell.tsx` that hiding a rail entry tells a lie about the
 * **product** — "this instance has no CI credentials" — which is worse than telling the truth about
 * the reader's access. They live in `nav.ts` now, still right for `Review policy` and
 * `Access & roles`, and still outweighed for exactly these entries: those pages have **no
 * read-only view at all**, so what a caller without the key gets is a whole screen whose only
 * content is a refusal.
 *
 * So this pins the rule in both directions, which is the part a one-sided test would miss.
 */
describe('AppShell — the gated entries (005 C2, 021 §1)', () => {
  it('lists the Administration folder and its children for a caller who holds their keys', async () => {
    renderShell(`/projects/${OLB}/cases`, ['token:issue', 'company:idp', 'audit:view'])
    await railReady()

    await expandAdministration()

    expect(rail().getByRole('link', { name: 'API tokens' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Single sign-on' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Company audit' })).toBeInTheDocument()
  })

  it('draws no Administration folder at all for a caller holding none of its three keys', async () => {
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await railReady()

    // A folder whose every child is hidden is itself hidden — a folder that opens onto nothing
    // advertises exactly the screens the permissions exist to withhold.
    expect(rail().queryByTestId('Nav.Folder.Administration')).toBeNull()
    expect(rail().queryByRole('link', { name: 'API tokens' })).toBeNull()
    expect(rail().queryByRole('link', { name: 'Single sign-on' })).toBeNull()
    expect(rail().queryByRole('link', { name: 'Company audit' })).toBeNull()
  })

  it('draws the folder for a caller holding ONE of the three, with only that child in it', async () => {
    renderShell(`/projects/${OLB}/cases`, ['token:issue'])
    await railReady()

    await expandAdministration()

    expect(rail().getByRole('link', { name: 'API tokens' })).toBeInTheDocument()
    expect(rail().queryByRole('link', { name: 'Single sign-on' })).toBeNull()
    expect(rail().queryByRole('link', { name: 'Company audit' })).toBeNull()
  })

  async function expandAdministration() {
    const user = userEvent.setup()
    const folder = rail().getByTestId('Nav.Folder.Administration')
    if (folder.getAttribute('aria-expanded') === 'false') await user.click(folder)
  }
})

/**
 * The Billing entry (019 D-o) — the gated Company row, moved out of the ungrouped block and
 * otherwise unchanged.
 *
 * `billing.html` says it in a callout on the screen itself: *"Switch the top bar to Test Engineer
 * or Account Manager and this entry disappears from the sidebar entirely — not greyed out, not
 * empty. It is not their screen."*
 *
 * Both directions, because a one-sided test cannot tell an absent entry from a rail that failed to
 * render at all — and the Account Manager arm is the sharper of the two negatives: that role holds
 * `client:read` and reads the client's hours, so the thing being asserted is that seeing the work
 * is not seeing what it costs.
 */
describe('AppShell — the Billing entry (019 D-o)', () => {
  it('renders it for a caller holding billing:read', async () => {
    renderShell(`/projects/${OLB}/cases`, ['billing:read'])
    await railReady()

    // `get`, not `find`: the company gate answers on the first `/me/permissions` and has no second
    // round trip to wait for.
    expect(rail().getByRole('link', { name: 'Billing' })).toHaveAttribute('href', '/billing')
    // It is in the Company zone now, not floating above the project frame.
    expect(within(rail().getByTestId('Nav.Zone.company')).getByRole('link', { name: 'Billing' }))
      .toBeInTheDocument()
  })

  it.each([
    ['a Test Engineer', ['case:read', 'time:log'] as PermissionKey[]],
    ['an Account Manager', ['client:read', 'time:log'] as PermissionKey[]],
  ])('does not render it for %s', async (_who, permissions) => {
    renderShell(`/projects/${OLB}/cases`, permissions)
    await railReady()

    // The rail really did render the rest of the Company zone — otherwise the absence below proves
    // nothing about the entry.
    expect(rail().getByRole('link', { name: 'Org chart' })).toBeInTheDocument()
    // ABSENT, not disabled. `queryByRole` over the whole rail, so a disabled-looking entry
    // rendered as a non-link would still have to be missing by name.
    expect(rail().queryByRole('link', { name: 'Billing' })).toBeNull()
    expect(rail().queryByText('Billing')).toBeNull()
  })

  it('keeps it out of the small-screen drawer too', async () => {
    /*
      The drawer renders the same `RailContent`, so this could only fail by the gate being read
      twice with different arguments — which is exactly the shape of defect a shared component
      invites when one caller passes a prop the other does not.
    */
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await railReady()
    await user.click(screen.getByTestId('Nav.HamburgerButton'))

    const drawer = within(await screen.findByTestId('Nav.Drawer'))
    expect(drawer.getByRole('link', { name: 'Org chart' })).toBeInTheDocument()
    expect(drawer.queryByRole('link', { name: 'Billing' })).toBeNull()
  })
})

/**
 * `docs/specs/2026-08-28-design-language.md` §4 — the rail collapses on the routes where the
 * reader is following a procedure at a measure, and the rail is navigation they are not doing.
 *
 * §9.2 recorded this as an open problem on the grounds that "the shell reads the route, which
 * `AppShell` does not do today". These are the tests that say it does, and — the half that is
 * easier to lose — that it still does not on everything else. A collapse rule that quietly widened
 * to every project route would look like a nicer shell and would take 168px off forty screens.
 */
describe('the rail collapses on the document routes (design language §4)', () => {
  const widthOf = () => screen.getByTestId('Nav.Rail').getAttribute('data-collapsed')

  it.each([
    [`/projects/${OLB}/cases/new`, 'the case editor, with no case behind it yet'],
    [`/projects/${OLB}/cases/case-1/edit`, 'the case document editor'],
    [`/projects/${OLB}/runs/run-1/executions/exec-1`, 'the execution runner'],
  ])('collapses on %s — %s', async (route) => {
    renderShell(route)
    await railReady()
    expect(widthOf()).toBe('true')
  })

  it.each([
    [`/projects/${OLB}/cases`],
    [`/projects/${OLB}/cases/case-1`],
    [`/projects/${OLB}/runs/run-1`],
    [`/projects/${OLB}/docs`],
  ])('stays expanded on %s', async (route) => {
    renderShell(route)
    await railReady()
    expect(widthOf()).toBe('false')
  })

  /**
   * The route forces the collapse, so a toggle offering to expand it would be a control that
   * appears to do nothing. Hiding it is the honest option — and it is why `onToggleCollapse` is
   * passed as `undefined` rather than the rail being told to ignore the click.
   */
  it('offers no collapse toggle where the route is what collapsed it', async () => {
    renderShell(`/projects/${OLB}/cases/new`)
    await railReady()
    expect(rail().queryByTestId('Nav.CollapseToggle')).toBeNull()
  })

  /** Proves the assertion above discriminates: the toggle IS there on an ordinary route. */
  it('offers the collapse toggle everywhere else', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()
    expect(rail().getByTestId('Nav.CollapseToggle')).toBeInTheDocument()
  })
})

/**
 * The header, after R1 emptied its left-hand side.
 *
 * What each test here is protecting:
 *
 * 1. **The company slot exists.** It is the outermost frame — instance → company → project →
 *    screen — and it is reserved rather than filled: no endpoint in the contract names the
 *    caller's company, so this side shows nothing rather than hard-coding the mockup's "Acme Ltd"
 *    onto every company on the instance. The element is the reservation, and the test is what says
 *    the reservation is deliberate.
 * 2. ~~**The search is a field, not a link.**~~ → **the search is the palette** (021 §7, T7). The
 *    regression that matters is unchanged and only its shape moved: what must not come back is a
 *    control that costs a navigation before it can be typed into. So the tests below open the
 *    palette by its two shortcuts and by the trigger, and assert that a query with no match still
 *    reaches `/search` — which is the property the field had and the one a palette most easily
 *    loses.
 * 3. **`/` must not fire inside a text field.** The way this feature ships broken: the shortcut
 *    eats the character in every input in the app, and no test that does not type a slash into one
 *    will see it.
 * 4. **The trail is the shell's, and it names the project.** R1 took the switcher out of the
 *    header on the promise that the breadcrumb would state the frame at full width instead. If the
 *    trail is absent or generic, R1 is a net loss rather than a trade.
 * 5. **The collapse toggle is in the rail's header row**, not its foot — the placement is the
 *    change, so the test names the row rather than only the button.
 */
describe('AppShell header controls', () => {
  const probe = () => screen.getByTestId('Probe.Location').textContent

  function renderWithProbe(route: string) {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path === '/projects') return PROJECTS
      if (path.startsWith('/me/permissions')) return heldPermissions([])
      if (/^\/cases\/search(\?|$)/.test(path)) return { items: [], page: 0, size: 25, total: 0 }
      return []
    }) as typeof api.get)

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

  it('reserves the company slot in the header, and puts nothing in it yet', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    const slot = screen.getByTestId('TopBar.CompanySlot')
    expect(slot.closest('header')).not.toBeNull()
    /*
      **Empty on purpose, and this is the assertion that keeps it honest.** `GET /me` answers a
      `CurrentUser` with no company on it, and no other operation in `openapi.yaml` names one — so
      there is nothing truthful to render. When the field lands, this test changes to assert the
      name; until then it pins that nobody filled the slot with a constant.
    */
    expect(slot).toBeEmptyDOMElement()
  })

  it('draws the trail in the header, leading with Projects and the project key', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    const trail = within(screen.getByTestId('TopBar.Breadcrumbs'))
    await waitFor(() =>
      expect(trail.getAllByTestId('TopBar.Breadcrumb').map((crumb) => crumb.textContent)).toEqual([
        'Projects',
        'OLB',
        'Test cases',
      ]),
    )
    // The trail is chrome: it is in the header, not in the page, so a page's failed read cannot
    // take the only non-rail way back up with it.
    expect(screen.getByTestId('TopBar.Breadcrumbs').closest('header')).not.toBeNull()
    expect(trail.getByText('Test cases')).toHaveAttribute('aria-current', 'page')
  })

  it('opens the palette with the trigger, and lands a query with no match on /search', async () => {
    const user = userEvent.setup()
    renderWithProbe(`/projects/${OLB}/cases`)
    await railReady()

    // The rail entry the search replaced is still gone: a link would satisfy "search is reachable"
    // while restoring the empty-input hop the header control exists to remove.
    expect(rail().queryByTestId('Nav.Link.Searchcases')).toBeNull()

    const trigger = screen.getByTestId('TopBar.Search')
    expect(trigger.closest('header')).not.toBeNull()
    // The `role="search"` landmark moved from the field onto the trigger's form, not away.
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
    await railReady()

    await user.keyboard(keys)

    expect(await screen.findByTestId('Palette.Input')).toBeInTheDocument()
  })

  it('does not fire the slash shortcut while the caret is in a text field', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    // A stand-in for every input in the app. The shortcut is registered on `document`, so what is
    // being asserted is a property of the listener rather than of any one screen's markup.
    const field = document.createElement('input')
    document.body.append(field)
    await user.click(field)
    await user.keyboard('a/b')

    // Both halves matter: the palette stayed shut AND the character was typed. A shortcut that
    // opens nothing but still swallows the slash is the same defect with a quieter symptom.
    expect(screen.queryByTestId('Palette.Input')).toBeNull()
    expect(field).toHaveValue('a/b')
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    const trigger = screen.getByTestId('TopBar.Search')
    await user.click(trigger)
    expect(await screen.findByTestId('Palette.Input')).toBeInTheDocument()

    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByTestId('Palette.Input')).toBeNull())
    // Focus left behind in a closed dialog strands a keyboard user at the top of the document.
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('offers places by name, including the tabs and settings sections the rail no longer lists', async () => {
    const user = userEvent.setup()
    renderWithProbe(`/projects/${OLB}/cases`)
    await railReady()

    await user.click(screen.getByTestId('TopBar.Search'))
    await user.type(screen.getByTestId('Palette.Input'), 'suites')

    // `Suites` is a TAB on Test cases — invisible until you are already on the screen it extends,
    // which is exactly why the palette indexes it.
    await user.click(await screen.findByText('Suites'))

    await waitFor(() => expect(probe()).toBe(`/projects/${OLB}/suites`))
  })

  it('hides a gated place from the palette exactly as the rail hides it', async () => {
    const user = userEvent.setup()
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    await user.click(screen.getByTestId('TopBar.Search'))
    await user.type(screen.getByTestId('Palette.Input'), 'billing')

    // The caller holds no `billing:read`. A palette that offered it by name would undo the
    // recorded decision in the one surface searched by name rather than scanned.
    await waitFor(() => expect(screen.queryByText('Billing')).toBeNull())
  })

  it('jumps to an issue typed as a key', async () => {
    const user = userEvent.setup()
    renderWithProbe(`/projects/${OLB}/cases`)
    await railReady()

    await user.click(screen.getByTestId('TopBar.Search'))
    await user.type(screen.getByTestId('Palette.Input'), 'ACME-118')
    await user.click(await screen.findByText('ACME-118'))

    await waitFor(() => expect(probe()).toBe('/issues/ACME-118'))
  })

  it('puts the collapse toggle in the rail header row, beside the wordmark', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    const toggle = rail().getByTestId('Nav.CollapseToggle')
    const wordmark = rail().getByTestId('Nav.Wordmark')
    expect(toggle.parentElement).toBe(wordmark.parentElement)
  })
})

/**
 * The user menu after 021 R11 — the two rows that moved, and the one that was renamed.
 *
 * The rule this pins is the one that decided the move: **the rail is a place in the product scoped
 * by the project switcher or by the company; the user menu is the person's own things.** A
 * timesheet is one person's fortnight, so it fails the first test and passes the second. Asserted
 * from both ends, because a menu entry added without removing the rail row is the ordinary way a
 * move half-lands.
 */
describe('AppShell — the user menu (021 R11, §4)', () => {
  async function openMenu() {
    const user = userEvent.setup()
    await user.click(screen.getByTestId('TopBar.UserMenuButton'))
    return user
  }

  it('carries Timesheets and Timesheet approvals, and the rail carries neither', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    expect(rail().queryByTestId('Nav.Link.Timesheets')).toBeNull()
    expect(rail().queryByRole('link', { name: 'Timesheets' })).toBeNull()

    await openMenu()

    expect(await screen.findByTestId('TopBar.TimesheetsLink')).toHaveAttribute(
      'href',
      '/timesheets',
    )
    expect(screen.getByTestId('TopBar.TimesheetApprovalsLink')).toHaveAttribute(
      'href',
      '/timesheets/approvals',
    )
  })

  /**
   * Approvals is listed for **everybody** and carries **no count**, and both are the standing
   * decisions rather than an omission: the page explains its own refusal (§31), and the count
   * would come from a read that 403s for anyone without `time:approve` — a PROJECT-scope grant, so
   * there is no company-wide question this shell could ask first. §8.3's badge table says `none`
   * on that row, alone among its seven.
   */
  it('lists approvals for a caller who can approve nothing, with no count beside it', async () => {
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await railReady()
    await openMenu()

    const link = await screen.findByTestId('TopBar.TimesheetApprovalsLink')
    expect(link).toHaveTextContent('Timesheet approvals')
    expect(link.textContent).toBe('Timesheet approvals')
  })

  it('names the help entry User guide, in the menu and in the footer', async () => {
    renderShell(`/projects/${OLB}/cases`)
    await railReady()

    expect(screen.getByTestId('AppFooter.HelpLink')).toHaveTextContent('User guide')

    await openMenu()

    const help = await screen.findByTestId('TopBar.HelpLink')
    expect(help).toHaveTextContent('User guide')
    expect(help).toHaveAttribute('href', '/help')
  })
})

/**
 * The top strip's slot (007 T7 Step 0) — the one thing the case editor needs from the shell.
 *
 * A page's commit bar belongs beside the breadcrumb rather than in a footer bar of its own, and a
 * page cannot reach into `<header>`. The two halves worth pinning are that a child rendered into
 * the slot ends up *inside the header element* — not merely somewhere on the page, which a portal
 * pointed at the wrong target would also satisfy — and that a route filling nothing leaves the
 * strip empty rather than reserving a band of chrome for it.
 */
describe('AppShell top-strip slot', () => {
  function renderWithSlot(children: ReactNode) {
    vi.spyOn(api, 'get').mockImplementation((async (path: string) => {
      if (path === '/projects') return PROJECTS
      if (path.startsWith('/me/permissions')) return heldPermissions([])
      if (/^\/cases\/search(\?|$)/.test(path)) return { items: [], page: 0, size: 25, total: 0 }
      return []
    }) as typeof api.get)

    return render(
      <TestProviders client={createTestQueryClient()} route={`/projects/${OLB}/cases/new`} userId="user-1">
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/projects/:projectId/cases/new" element={children} />
          </Route>
        </Routes>
      </TestProviders>,
    )
  }

  it('renders a page’s commit bar inside the header, not merely on the page', async () => {
    renderWithSlot(
      <TopStripSlot>
        <button type="button" data-testid="Page.Save">Save</button>
      </TopStripSlot>,
    )

    const save = await screen.findByTestId('Page.Save')
    await waitFor(() => expect(save.closest('header')).not.toBeNull())
    expect(within(screen.getByTestId('Nav.TopStrip')).getByTestId('Page.Save')).toBeInTheDocument()
  })

  it('leaves the strip empty on a route that fills nothing', async () => {
    renderWithSlot(<p>Just a page</p>)

    await screen.findByText('Just a page')
    expect(screen.getByTestId('Nav.TopStrip')).toBeEmptyDOMElement()
  })
})
