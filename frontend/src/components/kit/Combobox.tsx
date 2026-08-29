import { useState, type ReactNode } from 'react';

import { cn } from '../../lib/cn';

/**
 * A server-filtered type-ahead — the shape six dialogs in this codebase were each spelling with
 * MUI's `Autocomplete`.
 *
 * ## Why not `ui/select`, and why not a library
 *
 * The kit's Radix `Select` calls `hasPointerCapture` on its trigger; jsdom implements neither it
 * nor a polyfill, and `src/test/setup.ts` is deliberately not a place where the application is bent
 * to suit the harness — `ReviewPanel` and `LinkBlockDialog` both record the same call. So this is a
 * real `role="combobox"` over a real `role="listbox"`: native focus, native keys, nothing to stub.
 * The combobox is the **input itself**, because typing into it is what narrows the list.
 *
 * ## The list is never re-filtered here
 *
 * `options` is what the server answered for the current `query`, and it is rendered as it arrived.
 * Filtering again on this side hides rows that matched on a field the label does not show —
 * `BindTestDialog` found that first and it is true of every caller: a case can match on its title
 * while the label shows its key.
 *
 * ## The backdrop is a click-catcher, not a scrim
 *
 * A fixed transparent layer under the popup closes it on any outside click without the focus
 * trapping a real overlay would bring — this control is often inside a dialog that already owns
 * one, and two nested traps is how Escape stops reaching the thing the reader meant to close.
 *
 * `features/docs/LinkBlockDialog.tsx` has a private copy of this, written in 007 T14 before there
 * was anywhere shared to put it — the same relationship `kit/Field` has with
 * `features/cases/ConditionEditDialog.tsx`. That one should collapse onto this the next time the
 * file is open.
 */
export const COMBOBOX_FIELD =
  'w-full rounded-[6px] border border-line bg-field px-3 py-1.5 text-left text-[13px] text-ink ' +
  'placeholder:text-ink-4 focus-visible:outline-2 focus-visible:outline-offset-2 ' +
  'focus-visible:outline-[color:var(--color-given)] disabled:cursor-not-allowed disabled:opacity-45';

const POPOVER =
  'absolute z-50 mt-1 max-h-64 w-full overflow-auto rounded-md border border-line bg-panel p-1 ' +
  'shadow-[0_16px_32px_-8px_rgb(0_0_0/60%)]';

const OPTION =
  'block w-full cursor-pointer rounded-[4px] px-2.5 py-1.5 text-left text-[13px] text-ink-2 ' +
  'hover:bg-wash-2 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-[-2px] ' +
  'focus-visible:outline-[color:var(--color-given)]';

export interface ComboboxProps<T> {
  /** The input's id — the `Field` above it labels this. */
  id: string;
  /** What is typed. Controlled, so a caller can pre-type a carried selection. */
  query: string;
  onQueryChange: (next: string) => void;
  /** What the server answered for `query`. Rendered verbatim — see the header. */
  options: T[];
  optionKey: (option: T) => string;
  optionLabel: (option: T) => ReactNode;
  selected: T | null;
  onSelect: (option: T) => void;
  /** What the list says when it is empty — usually "Searching…" vs "No match." */
  emptyLabel: ReactNode;
  placeholder?: string;
  disabled?: boolean;
  'data-testid'?: string;
}

export function Combobox<T>({
  id,
  query,
  onQueryChange,
  options,
  optionKey,
  optionLabel,
  selected,
  onSelect,
  emptyLabel,
  placeholder,
  disabled,
  ...rest
}: ComboboxProps<T>) {
  const [open, setOpen] = useState(false);
  const listId = `${id}-options`;

  return (
    <div className="relative">
      <input
        id={id}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-autocomplete="list"
        value={query}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(event) => {
          onQueryChange(event.target.value);
          setOpen(true);
        }}
        onClick={() => setOpen(true)}
        className={COMBOBOX_FIELD}
        {...rest}
      />
      {open && (
        <>
          <div aria-hidden className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
          <div
            id={listId}
            role="listbox"
            onKeyDown={(event) => {
              if (event.key === 'Escape') setOpen(false);
            }}
            className={POPOVER}
          >
            {options.length === 0 ? (
              <p className="m-0 px-2.5 py-1.5 text-[12.5px] text-ink-4">{emptyLabel}</p>
            ) : (
              options.map((option) => (
                <button
                  key={optionKey(option)}
                  type="button"
                  role="option"
                  aria-selected={selected !== null && optionKey(option) === optionKey(selected)}
                  onClick={() => {
                    onSelect(option);
                    setOpen(false);
                  }}
                  className={cn(OPTION)}
                >
                  {optionLabel(option)}
                </button>
              ))
            )}
          </div>
        </>
      )}
    </div>
  );
}
