import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import type { PermissionKey, Project } from '../api/types'
import { heldPermissions } from '../test/permissions'
import { createTestQueryClient } from '../test/queryClient'
import { TestProviders } from '../test/TestProviders'
import { AppShell } from './AppShell'

/**
 * The shell's navigation affordances — the first test file it has ever had, added with the two
 * things it was missing.
 *
 * The failures this file exists to catch:
 *
 * 1. **`/` is reachable from inside a project.** The picker is where sign-in lands and where a
 *    project the caller cannot see falls back to, and until now nothing in the app linked to it —
 *    reachable only by editing the address bar. A regression here is invisible in every other test
 *    in the suite, because every other test renders a page rather than the shell around it.
 * 2. **The current-location marker is on the right item.** `/` is a prefix of every path in the
 *    app, so this is where a nav item that claims to be current on twenty screens at once would
 *    show up. **Stated honestly: this one does not catch a dropped `end`** — `NavLink` special-cases
 *    a root link and refuses the prefix match on its own, so removing `end: true` from
 *    `ALL_PROJECTS` leaves this green. It pins the behaviour, not the attribute that expresses it.
 * 3. **The collapsed rail keeps its accessible names.** Collapsing removes the label text from the
 *    DOM. An icon-only link with no `aria-label` is announced as its href, and the tooltip does not
 *    stand in for one — it never reaches a reader that is not hovering. This is the defect that is
 *    completely invisible on screen, where the icons look fine.
 * 4. **The preference survives a reload.** A collapse that resets on every page load is a gesture,
 *    not a setting.
 * 5. **The drawer has no collapse toggle.** The small-screen variant is already a deliberate
 *    reveal; a toggle there would offer to take the labels away from the users with the least room
 *    to guess at icons.
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
    // — and, since 005 C2, the three credential entries as well.
    // Answered with the real shape rather than by the `[]` fallback below, which is an array where
    // the contract declares an object — the contract double catches it, and the failure reads as a
    // navigation defect rather than as a missing arm.
    if (path.startsWith('/me/permissions')) return heldPermissions(permissions)
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
 * Waits for the *project* group, not for the switcher.
 *
 * The switcher and the two ungrouped items render before `/projects` answers, so waiting on one of
 * those returns while `groups` is still empty and every project-scoped assertion below fails on a
 * rail that was merely early rather than wrong.
 */
const railReady = () => waitFor(() => expect(rail().getByTestId('Nav.Link.Testcases')).toBeInTheDocument())

/** `aria-current` sits on the box inside the anchor, alongside the icon — not on the anchor. */
const currentMarkerIn = (link: HTMLElement) => link.querySelector('[aria-current="page"]')

describe('AppShell navigation', () => {
  it('links to the project picker from inside a project, by nav item and by wordmark', async () => {
    renderShell(`/projects/${OLB}/cases`)

    await railReady()

    // Asserted as an href rather than by clicking: the point is that this is a real anchor a user
    // can middle-click and copy, which an onClick handler would satisfy neither of.
    expect(rail().getByTestId('Nav.Link.Allprojects')).toHaveAttribute('href', '/')
    expect(rail().getByTestId('Nav.Wordmark')).toHaveAttribute('href', '/')
  })

  it('marks All projects current on the picker and NOT on a project route', async () => {
    const { unmount } = renderShell(`/projects/${OLB}/cases`)
    await railReady()

    expect(currentMarkerIn(rail().getByTestId('Nav.Link.Allprojects'))).toBeNull()

    unmount()

    renderShell('/')
    await railReady()
    expect(currentMarkerIn(rail().getByTestId('Nav.Link.Allprojects'))).not.toBeNull()
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
    expect(rail().getByRole('link', { name: 'All projects' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'My work' })).toBeInTheDocument()

    // The switcher survives as the project's key — the one question a 64px rail must still answer.
    expect(rail().getByTestId('Nav.ProjectSwitcher')).toHaveAccessibleName(
      'Switch project — current project Online Banking',
    )
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
    expect(drawer.getByTestId('Nav.Link.Allprojects')).toHaveAttribute('href', '/')
    expect(drawer.queryByTestId('Nav.CollapseToggle')).not.toBeInTheDocument()
  })
})

/**
 * The credential entries (005 C2, decided).
 *
 * Five comments in `AppShell.tsx` argue that hiding a rail entry tells a lie about the **product**
 * — "this instance has no CI credentials" — which is worse than telling the truth about the
 * reader's access, and those comments are still right for `Review` and `Access & roles`. They are
 * outweighed for exactly the three entries here, because those pages have **no read-only view at
 * all**: the reads themselves cost the key, so what a caller without it gets is a whole screen
 * whose only content is a refusal.
 *
 * So this file pins the rule in both directions, which is the part a one-sided test would miss.
 */
describe('AppShell — the credential entries (005 C2)', () => {
  it('lists them for a caller who can open the pages they lead to', async () => {
    renderShell(`/projects/${OLB}/cases`, ['integration:manage', 'token:issue', 'company:idp'])
    await railReady()

    // `find`, not `get`, for the two PROJECT-scoped entries. The company gate answers immediately
    // — `/me/permissions` with no projectId — while the project one cannot even be REQUESTED until
    // `/projects` has said which project is active, so it is still pending when the rail's groups
    // first render and `can()` fails closed while pending. The two company entries below are `get`
    // deliberately: they have no second round trip to wait for, and turning them into `find` would
    // hide the day one of them acquires one.
    expect(await rail().findByRole('link', { name: 'Notifications' })).toBeInTheDocument()
    expect(await rail().findByRole('link', { name: 'Issue tracker' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'API tokens' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Single sign-on' })).toBeInTheDocument()
  })

  it('un-lists them for a caller who cannot', async () => {
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await railReady()

    expect(rail().queryByRole('link', { name: 'Notifications' })).not.toBeInTheDocument()
    expect(rail().queryByRole('link', { name: 'Issue tracker' })).not.toBeInTheDocument()
    expect(rail().queryByRole('link', { name: 'API tokens' })).not.toBeInTheDocument()
    expect(rail().queryByRole('link', { name: 'Single sign-on' })).not.toBeInTheDocument()
  })

  it('keeps listing the entries whose pages ARE readable without the write key', async () => {
    // The rule is whether the page can be READ, not whether it can be written. Both of these
    // render read-only for a caller without `project:settings` / `project:members`, so both stay.
    renderShell(`/projects/${OLB}/cases`, ['case:read'])
    await railReady()

    expect(rail().getByRole('link', { name: 'Review' })).toBeInTheDocument()
    expect(rail().getByRole('link', { name: 'Access & roles' })).toBeInTheDocument()
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
