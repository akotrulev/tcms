import { SETTINGS_SECTIONS, type SettingsSection } from '../../app/nav';
import { useCan } from '../../auth/useCan';

/**
 * Which settings sections this caller may open — **one predicate, read by both renderings.**
 *
 * `SettingsLandingPage` draws them as cards and `SettingsSubnav` draws them as a row, and the
 * cheapest way for those two to disagree is for each to ask the gate itself. A section present on
 * the landing and missing from the subnav would tell a reader their access changed between two
 * clicks; the reverse would offer a link to a screen whose whole content is a refusal.
 *
 * The predicate is the one the rail applied to these same eleven entries before they left it, and
 * it is deliberately not "may they *write* it": ~~a section is listed for a caller who can change
 * it~~ → the test is **whether the page can be read** (005 C2). `Review policy`, `Classification`,
 * `Workflow` and `Access & roles` all render read-only for a caller without the write key and are
 * therefore listed for everybody; the four gated ones cost their key on the *read*, so what a
 * caller without it would get is a whole screen that exists only to refuse them.
 *
 * Every gate on this list is PROJECT scope, which `NavGate.scope` states rather than assumes — a
 * company-scope question asked of a project is answered confidently and wrongly. `can()` fails
 * closed while pending and open when `/me/permissions` itself failed, which is the same asymmetry
 * the rail uses and for the same reason: a card wrongly shown leads to a page that refuses, while
 * a card wrongly hidden takes an administrator's own screens away with nothing on screen to say
 * why.
 */
export function useSettingsSections(projectId: string): readonly SettingsSection[] {
  const gate = useCan(projectId);
  return SETTINGS_SECTIONS.filter((section) => !section.gate || gate.can(section.gate.key));
}
