/**
 * The name a clone dialog opens with (§129 phase 3).
 *
 * **The server invents nothing.** `title`/`name` is required on all three clone inputs, and the
 * operation says why in as many words: two rows called *Regression* and *Copy of Regression* that
 * nobody can tell apart in a list is the failure a server-side default ships by construction, and
 * whoever writes the prefix also has to decide what happens at the length limit. So the suggestion
 * lives here — a default a person sees and can edit before it becomes a row is a different object
 * from a default the server writes.
 *
 * It lives in `lib/` rather than in one of the three dialogs because there are three of them, with
 * three different limits (a case title is 300, a plan and a run are 200). Three copies of one
 * sentence is how "Copy of" becomes "Copy of", "Copy of " and "Clone of" on three screens — the
 * `lib/labels.ts` argument, one field wide.
 */

const PREFIX = 'Copy of ';

/**
 * `Copy of <name>`, shortened to fit `max` — never longer, so the field it prefills is never
 * already refused by the rule the field itself mirrors.
 *
 * The **name** is what gets shortened, not the prefix: a suggestion reading `Copy o…` says
 * nothing, while `Copy of Regression pass for the …` still says what it is a copy of. The ellipsis
 * counts against the limit, which is the arithmetic that is wrong in every hand-rolled version.
 */
export function copyOf(name: string, max: number): string {
  const suggested = `${PREFIX}${name}`;
  if (suggested.length <= max) return suggested;
  const room = max - PREFIX.length - 1;
  // A limit too small to hold the prefix and one character is not a case any contract here has —
  // `TestCaseCloneInput.title` is 300 and both names are 200 — but truncating the source's own
  // name is the answer that stays inside the limit rather than the one that reads best.
  return room <= 0 ? name.slice(0, max) : `${PREFIX}${name.slice(0, room)}…`;
}
