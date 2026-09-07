import { NAV, SETTINGS_SECTIONS, TABS, isFolder, type NavGate } from './nav';

/**
 * **Every place in the product, as one flat list** — the palette's `Places` group (021 §7).
 *
 * Generated from `nav.ts` rather than written out, which is the whole reason `nav.ts` is data
 * (D-h): the rail, the tab strips, the settings landing and this index are four renderings of one
 * list, so a rename reaches all four or none. A hand-written palette index is the fifth copy of
 * twenty labels, and the one nobody would remember to edit — it is not on screen until somebody
 * types.
 *
 * ## What counts as a place
 *
 * Four sources, and the fourth is the one worth stating:
 *
 *   1. **The rail** — every zone's items, and the items inside its one surviving folder.
 *   2. **The tab strips** — `Suites`, `Proposals`, `Schedules`, `CI results`, `Artifacts`. Five
 *      screens that stopped being rail rows in T6 and would otherwise have become unfindable by
 *      name: a tab is only visible once you are already on the screen it extends.
 *   3. **The settings sections** — eleven cards behind one rail row, same argument one level down.
 *   4. **The user menu** — which is what keeps `Timesheets` one keystroke away from anywhere now
 *      that R11 has moved it out of the rail. The menu rows are listed here literally, because
 *      unlike the other three the menu is markup in `AppShell` rather than data; that asymmetry is
 *      real and is the cost of R11, not an oversight. `Sign out` is not here — it is an act, not a
 *      place, and a fuzzy match on "sign" must not offer to end the session.
 *
 * ## `where`
 *
 * The trailing hint the mockup draws on each row — `Project`, `Test cases → tab`, `Settings`. It is
 * how the reader tells two similar labels apart before pressing Enter, and it is a **navigational**
 * fact rather than a category: `Suites` is findable by name here, and the hint is what says the
 * screen is a tab on `Test cases` when you get there.
 */
export interface Place {
  label: string;
  /** `projectId` is `''` outside the project frame; a place that ignores it is outside it. */
  to: (projectId: string) => string;
  /** Where this place lives, for the row's trailing hint. */
  where: string;
  /** The same gate the rail applies, asked at the same scope. Undefined means listed for all. */
  gate?: NavGate;
}

/**
 * True when a place only exists inside a project frame — detected rather than declared.
 *
 * `to` is a function of the project id (`nav.ts` header, point 3), and "a row that ignores its
 * argument is a row outside the frame" is a property that can be *read off the data* instead of
 * being a fifth field somebody has to remember to set. Without this the palette would offer
 * `/projects//cases` to a member who holds no project grant.
 */
export function needsProject(place: Place): boolean {
  return place.to('a') !== place.to('b');
}

/** The user menu's places (§4). Not `Sign out` — see this file's header. */
const USER_MENU: Place[] = [
  { label: 'Timesheets', to: () => '/timesheets', where: 'You' },
  { label: 'Timesheet approvals', to: () => '/timesheets/approvals', where: 'You' },
  { label: 'Security', to: () => '/account/security', where: 'Account' },
  { label: 'Tracker connections', to: () => '/account/trackers', where: 'Account' },
  { label: 'AI assistants', to: () => '/account/assistants', where: 'Account' },
  { label: 'Your access', to: () => '/account/access', where: 'Account' },
  /* ~~`Help & documentation`~~ → **`User guide`** (§8.1, R6). */
  { label: 'User guide', to: () => '/help', where: 'Account' },
];

/** Which rail row each tab strip extends — the `where` hint's first half. */
const TAB_STRIP_OWNER: Record<keyof typeof TABS, string> = {
  cases: 'Test cases',
  runs: 'Test runs',
  docs: 'Documents',
};

/**
 * The four sources, before the duplicates are removed.
 *
 * Three tabs are the same destination as the rail row they hang off — `Cases` is `Test cases`,
 * `Runs` is `Test runs`, `Pages` is `Documents` — because a strip's first tab IS the screen. In the
 * rail they read as one row and one strip; flattened into a list they would read as six places,
 * two of which go to the same URL under a shorter name. {@link PLACES} keeps the first spelling of
 * each destination, which is the rail's, because that is the label the reader has seen.
 */
const EVERY_PLACE: readonly Place[] = [
  ...NAV.flatMap((zone) =>
    zone.entries.flatMap((entry) =>
      isFolder(entry)
        ? entry.items.map((item) => ({
            label: item.label,
            to: item.to,
            // The folder is named as well as the zone: `Administration` is where these three
            // live, and a reader who cannot find `API tokens` in the rail is exactly the reader
            // who did not open the folder.
            where: `${zone.label} → ${entry.label}`,
            ...(item.gate ? { gate: item.gate } : {}),
          }))
        : [
            {
              label: entry.label,
              to: entry.to,
              where: zone.label,
              ...(entry.gate ? { gate: entry.gate } : {}),
            },
          ],
    ),
  ),
  ...Object.entries(TABS).flatMap(([strip, tabs]) =>
    tabs.map((tab) => ({
      label: tab.label,
      to: tab.to,
      where: `${TAB_STRIP_OWNER[strip as keyof typeof TABS]} → tab`,
    })),
  ),
  ...SETTINGS_SECTIONS.map((section) => ({
    label: section.label,
    to: section.to,
    where: 'Settings',
    ...(section.gate ? { gate: section.gate } : {}),
  })),
  ...USER_MENU,
];

export const PLACES: readonly Place[] = EVERY_PLACE.filter((place, index) => {
  // Keyed on the resolved route with a stand-in project, so two places that differ only in which
  // project they are read for still count as one place.
  const destination = place.to('_');
  return EVERY_PLACE.findIndex((other) => other.to('_') === destination) === index;
});
