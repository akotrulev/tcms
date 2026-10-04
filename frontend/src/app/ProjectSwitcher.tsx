import { Check, ChevronDown } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Project } from '../api/types';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '../components/ui';
import { cn } from '../lib/cn';
import { projectColorFill } from '../lib/projectColors';
import { useActiveProject } from './shellNav';

/**
 * A project's key on its colour — the one place a project's colour is drawn (023 §3a, D5, D17).
 * The text is white on every palette value in both schemes, which `projectPalette.test.ts` holds at
 * 4.5:1, so it is never tinted by scheme.
 */
export function ProjectKeyChip({
  project,
  className,
}: {
  project: Pick<Project, 'key' | 'color'>;
  className?: string;
}) {
  return (
    <span
      data-testid="ProjectKeyChip"
      data-color={project.color}
      style={{ backgroundColor: projectColorFill[project.color] }}
      className={cn(
        'identifier inline-flex h-[22px] shrink-0 items-center whitespace-nowrap rounded-sm px-1.5',
        'text-[10.5px] font-semibold text-white',
        className,
      )}
    >
      {project.key}
    </span>
  );
}

/**
 * The project switcher — the project's key chip and name in the bar, opening a filterable list.
 * It renders with **no project to switch to**: a member holding no project grants should see that
 * the control exists and is empty, not conclude the app has no projects in it. The name hides below
 * `sm`, leaving the chip.
 */
export function ProjectSwitcher() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const { projects, activeProjectId } = useActiveProject();
  const active = projects.find((project) => project.id === activeProjectId);

  const go = (to: string) => {
    setOpen(false);
    navigate(to);
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-testid="Nav.ProjectSwitcher"
          aria-label={active ? `Project: ${active.name}. Switch project` : 'Project'}
          className={cn(
            'flex h-8 min-w-0 cursor-pointer items-center gap-2 rounded-md border-0 bg-transparent px-1.5',
            'text-ink hover:bg-wash-1',
            'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[color:var(--color-given)]',
          )}
        >
          {active ? (
            <>
              <ProjectKeyChip project={active} />
              <span className="hidden max-w-[180px] truncate text-[14px] font-semibold sm:inline">
                {active.name}
              </span>
            </>
          ) : (
            <span className="text-[13px] text-ink-3">Select a project</span>
          )}
          <ChevronDown aria-hidden className="size-3.5 shrink-0 text-ink-3" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[280px] p-0" data-testid="Nav.ProjectSwitcher.Popover">
        <Command>
          <CommandInput placeholder="Find a project" aria-label="Find a project" />
          <CommandList>
            <CommandEmpty>No project matches.</CommandEmpty>
            <CommandGroup>
              {projects.map((project) => (
                <CommandItem
                  key={project.id}
                  value={`${project.key} ${project.name}`}
                  onSelect={() => go(`/projects/${project.id}`)}
                  data-testid={`Nav.ProjectSwitcher.Option.${project.key}`}
                  aria-current={project.id === activeProjectId ? 'true' : undefined}
                >
                  <ProjectKeyChip project={project} />
                  <span className="min-w-0 flex-1 truncate">{project.name}</span>
                  {project.id === activeProjectId && <Check aria-hidden className="size-4 text-steel" />}
                </CommandItem>
              ))}
            </CommandGroup>
            <CommandGroup className="border-t border-line">
              <CommandItem
                value="all projects"
                onSelect={() => go('/')}
                data-testid="Nav.ProjectSwitcher.AllProjects"
              >
                All projects
              </CommandItem>
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
