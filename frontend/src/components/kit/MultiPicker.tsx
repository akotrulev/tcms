import { useState, type ReactNode } from 'react';
import { X } from 'lucide-react';

import { cn } from '../../lib/cn';

/**
 * Several values chosen from a list — the multi-select shape eight pickers were spelling with
 * MUI's `<Autocomplete multiple>`.
 *
 * `Combobox` beside it is the single-value case and they are deliberately two components rather
 * than one with a `multiple` flag: the prop that differs is not a boolean, it is the whole
 * value type (`T` versus `T[]`), the whole commit gesture (replace versus append), and whether
 * there are chips to remove. A single component would have to make `selected` and `onSelect`
 * generic over both and every caller would carry the cast.
 *
 * ## What it does not do, and why
 *
 * **No client-side filtering when `serverFiltered` is set.** The list is what the server answered
 * for the current query; filtering again here hides rows that matched on a field the label does
 * not show. `BindTestDialog` found that first.
 *
 * **The chips are removable and the list is not a set of checkboxes.** A tag list can run to
 * dozens; a checkbox per option is a scrolling wall, and what the reader needs to see at rest is
 * *what they have chosen*, not what they have not.
 *
 * **`onCreate` is the free-text arm** — the tag pickers, where the value may be a word nobody has
 * used yet and `GET /tags` only lists tags with a live case. It is a separate prop rather than a
 * `freeSolo` boolean because the caller has to say what a typed string *becomes*, and only the
 * caller knows.
 *
 * The trigger is a real `role="combobox"` over a real `role="listbox"` for `Combobox`'s reason:
 * Radix's Select calls `hasPointerCapture`, which jsdom does not implement, and `src/test/setup.ts`
 * is not a place where the application is bent to suit the harness.
 */
export interface MultiPickerProps<T> {
  /** The input's id — the `Field` above it labels this. */
  id: string;
  options: T[];
  selected: T[];
  onChange: (next: T[]) => void;
  optionKey: (option: T) => string;
  optionLabel: (option: T) => ReactNode;
  /** Plain text for the chip and for local matching. Defaults to the key. */
  optionText?: (option: T) => string;
  /** Controlled query, for a server-filtered list. Omitted means filter locally. */
  query?: string;
  onQueryChange?: (next: string) => void;
  /** The server already filtered; render `options` verbatim. */
  serverFiltered?: boolean;
  /** Commit typed text as a value. Present only on the free-text pickers. */
  onCreate?: (text: string) => void;
  emptyLabel?: ReactNode;
  placeholder?: string;
  disabled?: boolean;
  onBlur?: () => void;
  'aria-invalid'?: boolean;
  'data-testid'?: string;
}

export function MultiPicker<T>({
  id,
  options,
  selected,
  onChange,
  optionKey,
  optionLabel,
  optionText,
  query,
  onQueryChange,
  serverFiltered,
  onCreate,
  emptyLabel = 'No match.',
  placeholder,
  disabled,
  onBlur,
  ...rest
}: MultiPickerProps<T>) {
  const [open, setOpen] = useState(false);
  const [localQuery, setLocalQuery] = useState('');
  const text = optionText ?? optionKey;
  const value = query ?? localQuery;
  const setValue = onQueryChange ?? setLocalQuery;
  const listId = `${id}-options`;

  const chosen = new Set(selected.map(optionKey));
  const visible = (serverFiltered ? options : options.filter(
    (option) => value.trim() === '' || text(option).toLowerCase().includes(value.trim().toLowerCase()),
  )).filter((option) => !chosen.has(optionKey(option)));

  const commitTyped = () => {
    const typed = value.trim();
    if (typed === '' || !onCreate) return;
    onCreate(typed);
    setValue('');
  };

  return (
    <div className="relative">
      {selected.length > 0 && (
        <div className="mb-1.5 flex flex-wrap gap-1.5">
          {selected.map((option) => (
            <span
              key={optionKey(option)}
              className="inline-flex items-center gap-1.5 rounded-[5px] border border-line px-2.5 py-[3px] text-[12px] text-ink-2"
            >
              {optionLabel(option)}
              <button
                type="button"
                aria-label={`Remove ${text(option)}`}
                disabled={disabled}
                onClick={() => onChange(selected.filter((s) => optionKey(s) !== optionKey(option)))}
                className="cursor-pointer text-ink-4 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]"
              >
                <X aria-hidden className="size-3" />
              </button>
            </span>
          ))}
        </div>
      )}
      <input
        id={id}
        type="text"
        role="combobox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-autocomplete="list"
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(event) => {
          setValue(event.target.value);
          setOpen(true);
        }}
        onClick={() => setOpen(true)}
        onBlur={onBlur}
        onKeyDown={(event) => {
          // Enter commits typed text on a free-text picker — the gesture MUI's `freeSolo` gave
          // these three, and the only way to name a tag the project has never used.
          if (event.key === 'Enter' && onCreate) {
            event.preventDefault();
            commitTyped();
          }
          if (event.key === 'Escape') setOpen(false);
        }}
        className={
          'w-full rounded-[8px] border border-line bg-field px-3 py-1.5 text-left text-[13px] text-ink ' +
          'placeholder:text-ink-4 focus-visible:outline-2 focus-visible:outline-offset-2 ' +
          'focus-visible:outline-[color:var(--color-given)] disabled:cursor-not-allowed disabled:opacity-45'
        }
        {...rest}
      />
      {open && (
        <>
          <div aria-hidden className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
          <div
            id={listId}
            role="listbox"
            aria-multiselectable
            className={cn(
              'absolute z-50 mt-1 max-h-64 w-full overflow-auto rounded-[8px] border border-line',
              'bg-panel p-1 shadow-e3',
            )}
          >
            {visible.length === 0 ? (
              <p className="m-0 px-2.5 py-1.5 text-[12.5px] text-ink-4">
                {onCreate && value.trim() !== '' ? `Press Enter to add “${value.trim()}”.` : emptyLabel}
              </p>
            ) : (
              visible.map((option) => (
                <button
                  key={optionKey(option)}
                  type="button"
                  role="option"
                  aria-selected={false}
                  onClick={() => {
                    onChange([...selected, option]);
                    setValue('');
                  }}
                  className={
                    'block w-full cursor-pointer rounded-sm px-2.5 py-1.5 text-left text-[13px] ' +
                    'text-ink-2 hover:bg-wash-2 hover:text-ink focus-visible:outline-2 ' +
                    'focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]'
                  }
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
