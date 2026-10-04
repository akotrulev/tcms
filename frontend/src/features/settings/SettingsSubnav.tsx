import { NavLink } from 'react-router-dom';
import { cn } from '../../lib/cn';
import { segmentItemClass, segmentTrackClass } from '../../components/kit/Segmented';
import { useSettingsSections } from './useSettingsSections';

/**
 * The row every settings subpage carries in its page strip, as links on the segment helpers.
 *
 * The sections are filtered by the same predicate the landing page reads — `useSettingsSections`
 * is the one place the gate is asked, so the row and the cards cannot disagree about what a caller
 * may open.
 *
 * The active link is read from the route, not a prop: a prop would be a second place the label is
 * written, and would let a page claim to be a section it is not. The row scrolls sideways inside its
 * own track rather than wrapping, so the strip stays one line.
 */
export function SettingsSubnav({ projectId }: { projectId: string }) {
  const sections = useSettingsSections(projectId);

  return (
    <nav
      aria-label="Settings sections"
      data-testid="Settings.Subnav"
      className={cn(segmentTrackClass, 'max-w-full overflow-x-auto')}
    >
      {sections.map((section) => (
        <NavLink
          key={section.label}
          to={section.to(projectId)}
          data-testid={`Settings.Subnav.${section.label.replace(/[^A-Za-z]/g, '')}`}
          className={({ isActive }) =>
            cn(segmentItemClass(isActive), 'shrink-0 whitespace-nowrap no-underline')
          }
        >
          {section.label}
        </NavLink>
      ))}
    </nav>
  );
}
