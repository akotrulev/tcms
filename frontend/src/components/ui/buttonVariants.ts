import { cva } from 'class-variance-authority';

/**
 * The kit's button styling, ported from `docs/specs/assets/renders/tcms.css` (`.btn`).
 *
 * It lives apart from `button.tsx` because a module that exports both a component and a plain
 * value loses React Fast Refresh — the project lints that as a warning and the lint gate is zero
 * warnings. Callers that need the classes without the element (a `<Link>` styled as a button)
 * import from here.
 *
 * Two things here are decisions rather than transcription:
 *
 * **The focus ring is `--color-given`, not steel.** Rule 3 of the design language wants a
 * *visible* focus ring on every control, and a steel ring around a steel button is the one that
 * disappears at exactly the moment it is needed. Green is the only accent that reads against both
 * the ghost and the primary variant.
 *
 * **The primary's ring and glow are tokens, not literals.** They were
 * `rgb(107 168 196/30%)` and `/35%` inline — `#6ba8c4`, which is the DARK palette's accent. In the
 * light scheme `--color-action-primary` is `#2b6883`, so the button drew a pale-blue ring around a
 * dark-teal fill: two blues on one control, in the scheme least able to hide it. `--steel-line` /
 * `--steel-glow` follow the palette, and the ring is back at the kit's 22%.
 *
 * **`ghost` is the default variant, not `primary`.** The renders put one primary action on a
 * screen and everything else in outline or quiet — a default of `primary` would make the common
 * case the loud one and invert the page's hierarchy by omission.
 */
export const buttonVariants = cva(
  'inline-flex items-center justify-center gap-[7px] rounded-lg font-sans font-medium ' +
    'transition-colors cursor-pointer whitespace-nowrap ' +
    'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)] ' +
    'disabled:pointer-events-none disabled:opacity-45',
  {
    variants: {
      variant: {
        ghost: 'border border-line text-ink-2 hover:bg-wash-2 hover:text-ink',
        primary:
          'bg-steel text-[color:var(--color-on-action)] font-semibold ' +
          'shadow-[0_0_0_1px_var(--color-steel-line),0_4px_14px_-4px_var(--color-steel-glow)] ' +
          'hover:brightness-110',
        quiet: 'text-ink-2 hover:bg-wash-2 hover:text-ink',
        danger:
          'border border-[color:var(--color-failed)]/30 text-[color:var(--color-failed)] hover:bg-[color:var(--color-failed)]/10',
        link: 'text-steel underline-offset-4 hover:underline p-0 min-h-0',
      },
      size: {
        default: 'text-[13px] px-[13px] py-1.5 min-h-8',
        sm: 'text-[12px] px-2.5 py-1 min-h-[26px]',
        icon: 'size-8 p-0',
      },
    },
    defaultVariants: { variant: 'ghost', size: 'default' },
  },
);
