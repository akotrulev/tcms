/**
 * A dropped folder of `.feature` files, read and measured — the half of the import screen that is
 * not rendering (§255).
 *
 * ## Why it is a module and not the page's top half
 *
 * Two reasons, and the second is the load-bearing one. `capsRefusals` and `countScenarios` are pure
 * over data and their arms are worth writing directly rather than through nine hundred `File`s and
 * 8 MiB of jsdom heap — a UI arm can afford to drive **one** of the four bounds, and these are what
 * the screen calls, so the other three are the real numbers rather than a restatement of them. And
 * a `.tsx` that exports a component may not also export a plain function: `react/only-export-components`
 * is a **warning**, `npm run lint` is a gate at zero warnings, and the `allowConstantExport` option
 * the SPA's oxlint config sets covers a `const` and not a `function`.
 *
 * ## What is deliberately not here
 *
 * **No Gherkin parsing.** `countScenarios` counts keyword lines and its refusal says so — the one
 * parser is `GherkinScenarios` on the server (plan §6a rule 3), and `POST …/cases/parse-gherkin`
 * exists precisely so the SPA does not grow a second one. See the function's own header for what
 * that costs.
 *
 * **→ amended by §003: a non-authoritative colouring scanner is allowed; a parser whose output is
 * committed is not.** The Write surface (003 C2) needs keyword, docstring and table *spans* to
 * colour text and to number the lines a refusal names, and no round trip can answer that per
 * keystroke. `features/cases/gherkinText.ts` is that scanner and its header states its own
 * limits: it commits nothing, it decides nothing, and what a save sends is still whatever
 * `parse-gherkin` read. The rule above is kept rather than rewritten, because the reasoning it
 * records is what makes the amendment narrow — see the working agreement on superseded decisions.
 */

/** The caps, mirroring `FeatureImportService.MAX_*`. Exported so the test drives the real numbers. */
export const MAX_FILES = 200;
export const MAX_FILE_BYTES = 1024 * 1024;
export const MAX_TOTAL_BYTES = 8 * 1024 * 1024;
export const MAX_SCENARIOS = 500;

/** One file the caller picked, read and measured. */
export interface PickedFile {
  /** The picked **relative** path — provenance, and the reason this is not a multipart upload. */
  path: string;
  content: string;
  bytes: number;
  scenarios: number;
}

/**
 * How many scenarios a file looks like it holds.
 *
 * **A heuristic, and the one cap the client cannot compute exactly.** The server counts what the
 * parser found; this counts `Scenario:` / `Scenario Outline:` / `Example:` keyword lines, which
 * disagrees with a real parse on a file that puts one of those words inside a doc string. So the
 * refusal is worded not to overclaim, and a file that slips past it is refused by the server on
 * `errors.files` exactly as before — the client cap saves an 8 MiB upload, it does not replace the
 * check.
 */
export function countScenarios(content: string): number {
  return (content.match(/^[ \t]*(?:Scenario Outline|Scenario|Example):/gm) ?? []).length;
}

/**
 * Everything wrong with this selection, in the server's own words where they exist.
 *
 * All of them, not the first: a caller who dropped 300 files of 2 MiB each should be told both
 * things, which is `FeatureImportService.shapeErrors`' argument on the other side of the wire.
 */
export function capsRefusals(files: PickedFile[]): string[] {
  const problems: string[] = [];
  if (files.length > MAX_FILES) {
    problems.push(`Send at most ${MAX_FILES} files in one request.`);
  }

  const seen = new Set<string>();
  let total = 0;
  let scenarios = 0;
  for (const file of files) {
    if (seen.has(file.path)) problems.push(`Two files share the path '${file.path}'.`);
    seen.add(file.path);
    if (file.bytes > MAX_FILE_BYTES) problems.push(`'${file.path}' is larger than 1 MiB.`);
    total += file.bytes;
    scenarios += file.scenarios;
  }
  if (total > MAX_TOTAL_BYTES) problems.push('The files total more than 8 MiB.');
  if (scenarios > MAX_SCENARIOS) {
    problems.push(
      `These files look like they hold more than ${MAX_SCENARIOS} scenarios, which is the most one request may carry.`,
    );
  }

  // De-duplicated for the server's reason: 300 oversized files must not produce 300 identical
  // sentences under a drop zone.
  return [...new Set(problems)];
}

/** The relative path a browser gives a picked file — a folder pick carries one, a plain pick does not. */
export function pathOf(file: File): string {
  return file.webkitRelativePath || file.name;
}

export function isFeature(path: string): boolean {
  return path.toLowerCase().endsWith('.feature');
}

export async function readOne(file: File, path: string): Promise<PickedFile> {
  const content = await file.text();
  return { path, content, bytes: file.size, scenarios: countScenarios(content) };
}

/**
 * Every `.feature` file under a dropped entry, with its path relative to what was dropped.
 *
 * **A folder drop carries no `File`s at all** — `dataTransfer.files` is empty and the directory is
 * only reachable through `webkitGetAsEntry()`, so a handler that read the file list would silently
 * import nothing from the gesture this screen is built around. `fullPath` is what keeps the
 * provenance honest: a multipart part would have carried `cancel.feature`, and
 * `features/orders/cancel.feature` is the string somebody can find in their repository.
 *
 * `readEntries` answers at most a hundred entries per call and signals the end with an empty batch,
 * which is why this loops rather than reading once.
 */
export async function fromEntry(entry: FileSystemEntry): Promise<PickedFile[]> {
  const relative = entry.fullPath.replace(/^\//, '');
  if (entry.isFile) {
    if (!isFeature(relative)) return [];
    const file = await new Promise<File>((resolve, reject) => {
      (entry as FileSystemFileEntry).file(resolve, reject);
    });
    return [await readOne(file, relative)];
  }
  if (!entry.isDirectory) return [];

  const reader = (entry as FileSystemDirectoryEntry).createReader();
  const found: PickedFile[] = [];
  for (;;) {
    const batch = await new Promise<FileSystemEntry[]>((resolve, reject) => {
      reader.readEntries(resolve, reject);
    });
    if (batch.length === 0) break;
    for (const child of batch) found.push(...(await fromEntry(child)));
  }
  return found;
}
