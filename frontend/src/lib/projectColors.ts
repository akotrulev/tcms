import type { ProjectColor } from '../api/types';

export const projectColorFill = {
  'project-1': 'var(--color-project-1)',
  'project-2': 'var(--color-project-2)',
  'project-3': 'var(--color-project-3)',
  'project-4': 'var(--color-project-4)',
  'project-5': 'var(--color-project-5)',
  'project-6': 'var(--color-project-6)',
  'project-7': 'var(--color-project-7)',
  'project-8': 'var(--color-project-8)',
} as const satisfies Record<ProjectColor, string>;
