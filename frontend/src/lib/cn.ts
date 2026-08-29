import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Join class names, letting a later Tailwind utility beat an earlier one in the same group.
 *
 * Plain `clsx` would emit `px-3 px-5` and leave the winner to CSS source order — which is the
 * bundler's business, not the caller's, so a component that accepts a `className` override could
 * not be overridden reliably. `twMerge` resolves the conflict by group, so the caller's class wins
 * because it came last, which is the behaviour every `className` prop in the kit assumes.
 */
export const cn = (...inputs: ClassValue[]) => twMerge(clsx(inputs));
