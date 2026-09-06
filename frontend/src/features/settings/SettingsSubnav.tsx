import { NavLink } from 'react-router-dom';
import { cn } from '../../lib/cn';
import { useSettingsSections } from './useSettingsSections';

/**
 * The row every settings subpage carries (021 §5) — the row the mockup and two router comments
 * already assumed and the build never had.
 *
 * Eleven rail siblings became one `Settings` row leading to a landing page, and the cost of that
 * trade is that a reader standing on `/settings/workflow` can no longer see `/settings/boards` in
 * the rail. This is what pays it back: the same eleven sections, filtered by the same predicate,
 * on every page they lead to. The rail keeps *one* highlighted `Settings` row rather than eleven
 * siblings, and the depth is carried here and by the breadcrumb instead.
 *
 * **Filtered by the same predicate the rail used**, which is the half a second implementation
 * would get wrong: `useSettingsSections` is the one place the gate is asked, and both this and
 * `SettingsLandingPage` read it.
 *
 * Links with the active one read from the route, not a `current` prop, for two reasons. A prop
 * would be a twelfth place the label is written — `nav.ts` exists so it is written once — and it
 * would let a page claim to be a section it is not. It is a separate component from
 * `components/TabStrip.tsx` because the shape is genuinely different: eleven wrapping items
 * reading as a row of links, against three segments in a track. Collapsing them would give one of
 * the two the wrong affordance.
 */
export function SettingsSubnav({ projectId }: { projectId: string }) {
  const sections = useSettingsSections(projectId);

  return (
    <nav
      aria-label="Settings sections"
      data-testid="Settings.Subnav"
      className="mb-5 flex flex-wrap items-center gap-x-1 gap-y-0.5 border-b border-line pb-2"
    >
      {sections.map((section) => (
        <NavLink
          key={section.label}
          to={section.to(projectId)}
          data-testid={`Settings.Subnav.${section.label.replace(/[^A-Za-z]/g, '')}`}
          className="no-underline"
        >
          {({ isActive }) => (
            <span
              aria-current={isActive ? 'page' : undefined}
              className={cn(
                'block rounded-md px-2 py-1 text-[12.5px]',
                isActive
                  ? 'bg-wash-1 font-medium text-ink'
                  : 'text-ink-3 hover:bg-wash-1 hover:text-ink-2',
              )}
            >
              {section.label}
            </span>
          )}
        </NavLink>
      ))}
    </nav>
  );
}
