import { Bug, FileText, Plus, Rocket, type LucideIcon } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import { PermissionGate } from '../components/PermissionGate';
import type { PermissionKey } from '../api/types';
import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/ui';

interface CreateEntry {
  label: string;
  to: (projectId: string) => string;
  icon: LucideIcon;
  permission: PermissionKey;
}

/**
 * Only routes that exist (023 D13). Each key is the one the entry's list page gates its own New
 * button on — `issue:create` (Work's + Add), `case:create` (Test cases' New case) and
 * `release:manage` (Releases' New release) — so the menu can never offer what the page refuses.
 */
const ENTRIES: readonly CreateEntry[] = [
  { label: 'Issue', to: (p) => `/projects/${p}/work/new`, icon: Bug, permission: 'issue:create' },
  { label: 'Test case', to: (p) => `/projects/${p}/cases/new`, icon: FileText, permission: 'case:create' },
  { label: 'Release', to: (p) => `/projects/${p}/releases/new`, icon: Rocket, permission: 'release:manage' },
];

/**
 * Create — the bar's one primary action. Items are **disabled with their reason, never hidden**
 * (§31), through `PermissionGate`. The label hides below `md`; the accessible name stays.
 */
export function CreateMenu({ projectId }: { projectId: string }) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="primary" size="sm" aria-label="Create" data-testid="TopBar.Create">
          <Plus aria-hidden className="size-4" />
          <span className="max-md:hidden">Create</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-[180px]">
        {ENTRIES.map((entry) => {
          const Icon = entry.icon;
          const testId = `TopBar.Create.${entry.label.replace(/[^A-Za-z]/g, '')}`;
          return (
            <PermissionGate
              key={entry.label}
              projectId={projectId}
              permission={entry.permission}
              wrapper="block"
            >
              {({ allowed }) =>
                allowed ? (
                  <DropdownMenuItem asChild>
                    <RouterLink to={entry.to(projectId)} data-testid={testId}>
                      <Icon aria-hidden className="size-4" />
                      <span>{entry.label}</span>
                    </RouterLink>
                  </DropdownMenuItem>
                ) : (
                  <DropdownMenuItem disabled data-testid={testId}>
                    <Icon aria-hidden className="size-4" />
                    <span>{entry.label}</span>
                  </DropdownMenuItem>
                )
              }
            </PermissionGate>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
