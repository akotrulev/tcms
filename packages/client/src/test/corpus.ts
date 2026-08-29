/**
 * The four corpora this repository's prose is read from, and the machinery for reading them.
 *
 * §206 established the set and the argument for each root — `packages`, `frontend/src`,
 * `docs/specs` and `backend/src` — inside `citedFiles.test.ts`, because at the time exactly one
 * rule read them. §212 wrote the second, and a second rule is the moment a corpus list stops being
 * one check's configuration and becomes a fact about the repository. It is extracted here rather
 * than copied, for the reason `contractPairing.test.ts` exists to enforce one file over: two
 * scanners agree until somebody teaches one of them about a new extension, and the one that was not
 * taught goes on reporting a clean sweep of a tree it is half-reading.
 *
 * ## Why the rules that use this live in `packages/`
 *
 * Stated once here so neither reader has to reconstruct it. The prose belongs to four stacks and
 * the check belongs to this one, which means **a stale sentence in `frontend/` or `backend/`
 * reddens the `packages` job** — the wrong place for the blame and still the right place for the
 * rule:
 *
 * - `frontend/` — a Vite `?raw` import and two npm roots make importing across the boundary
 *   impossible in *production* code (which is why `contractBodies.ts` is a pinned pair), but this
 *   is a test reading files off disk and both CI jobs check the repository out whole.
 * - `docs/specs` — the source the generated pair is copied from. A copy reports its source's defect
 *   a second time and cannot be the place it is fixed.
 * - `backend/` — §185's Gradle-cache trap. A `Test` task's declared inputs are its classpath, so a
 *   comment edit that does not shift a line number leaves the class byte-identical and the suite
 *   `UP-TO-DATE`; worse, the *usual* cause of a stale sentence is a change somewhere else
 *   entirely, which changes no backend input at all. Vitest has no such cache and reads the tree
 *   as it is on disk.
 *
 * ## What is deliberately not a corpus
 *
 * **The generated pair** — `packages/client/src/schema.ts` and `frontend/src/api/schema.d.ts`.
 * `npm run gen:api` copies every contract description verbatim into both, so one sentence in
 * `openapi.yaml` is three on disk and the two downstream ones cannot be repaired where they are
 * read.
 *
 * **`docs/`, apart from `docs/specs`.** The handoff narrative and the plans are a *record of what
 * was believed when they were written*, and a rule that made them keep up would be a rule against
 * this repository's own notation. `docs/specs` is in because a spec is present-tense by
 * construction: it says what the product is, and `openapi.yaml` is compiled into two source files.
 */
import { readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');

/**
 * Directories a scan must never descend into — build output, dependencies, VCS.
 *
 * `worktrees` is the one that is none of those, and it is here because it made the answer depend on
 * the machine: `.claude/worktrees/` holds git-ignored checkouts of this repository at other commits,
 * so a file deleted on `master` still resolves for whoever has a stale worktree and does not for CI.
 * That is the failure mode a citation rule cannot have — it makes a stale citation green locally and
 * red only after a push. Pruned by bare name like the rest, so a directory genuinely called
 * `worktrees` would be invisible to the resolver; there is none, and the alternative is a path rule
 * for one entry.
 */
export const PRUNED = new Set([
  'node_modules', 'dist', 'build', '.git', '.gradle', '.idea', 'coverage', '.vite', 'worktrees',
]);

/**
 * A superseded claim, in this repo's two notations. Non-greedy and multi-line — a span often is.
 *
 * `~~x~~` is the markdown form and the one this repository writes almost everywhere. `<del>` is
 * javadoc's, and it is here because javadoc renders markdown as literal tildes — so `backend/`'s
 * comments say the same thing in the only spelling that comes out looking like a correction.
 * Missing it costs a rule exactly what missing the other one would: §212 read four `<del>` spans as
 * live claims, including `CaseController`'s, which had been struck through and corrected in the
 * same sentence.
 */
export const STRUCK = /~~[\s\S]*?~~|<del>[\s\S]*?<\/del>/g;

export type CorpusRoot = {
  root: string;
  extensions: string[];
  /** Repo-relative paths inside the root that are not this repository's prose. */
  skip: string[];
};

export const CORPUS: CorpusRoot[] = [
  {
    root: 'packages',
    extensions: ['.ts'],
    skip: ['packages/client/src/schema.ts'],
  },
  {
    root: 'frontend/src',
    extensions: ['.ts', '.tsx'],
    skip: ['frontend/src/api/schema.d.ts'],
  },
  /*
    The directory rather than `openapi.yaml` alone, for `build.gradle.kts`'s reason one stack over: a
    second contract artifact added here is one this check would want to see, and a hand-kept tally of
    filenames is what §152–§156 replaced four times.
  */
  {
    root: 'docs/specs',
    extensions: ['.yaml', '.yml'],
    skip: [],
  },
  /*
    `backend/src`, both source sets: a test's javadoc argues from the same files a main one does, and
    §205's own population was three-quarters test code.
  */
  {
    root: 'backend/src',
    extensions: ['.java'],
    skip: [],
  },
];

/** The files of one root, repo-relative, in the order the walk found them. */
export function filesOf(entry: CorpusRoot): string[] {
  return walk(join(REPO, entry.root))
    .map(repoRelative)
    .filter((path) => entry.extensions.some((extension) => path.endsWith(extension)))
    .filter((path) => !entry.skip.includes(path));
}

/**
 * Every path under `root`, directories included and marked with a trailing `/`.
 *
 * The marker is what lets one set answer both questions: a citation ends in `/` when it means a
 * directory, so `src/mocks/` is looked for among the entries that end in one and `labels.ts` among
 * the entries that do not. A directory recorded without it would let a file called `mocks` satisfy
 * a citation of the folder.
 */
export function walk(root: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (PRUNED.has(entry.name)) continue;
    const path = join(root, entry.name);
    if (entry.isDirectory()) found.push(`${path}/`, ...walk(path));
    else if (entry.isFile()) found.push(path);
  }
  return found;
}

/** Repo-relative, keeping the trailing `/` that `path.relative` normalises away. */
export function repoRelative(absolute: string): string {
  const path = relative(REPO, absolute);
  return absolute.endsWith('/') ? `${path}/` : path;
}

/**
 * The prose of a file, with everything that is not prose blanked out.
 *
 * Offsets are preserved — code is replaced by spaces of the same length rather than removed — so a
 * span found here is a span in the file, and {@link liveSentences} can still excuse a struck one.
 *
 * **A rule about what this repository claims must read what it claims, and code claims nothing.**
 * Measured before the split was written: a first cut of §212's rule over whole `.java` files
 * reported a Test Engineer as lacking `case:delete` on the strength of an `assertThat` chain, and
 * a `@DisplayName` string — *"an engineer cannot take a lead's approval back"* — as a statement
 * about the catalogue. Both are the test asserting the very rule the sentence would have been wrong
 * about. A wordlist over identifiers finds the words it is made of.
 *
 * `.yaml` is returned whole. The contract is prose in a structure — every `description` block is a
 * paragraph and the keys between them are three words long — so there is nothing to subtract, and
 * subtracting by indentation would be a YAML parser written badly.
 */
export function proseOf(path: string, text: string): string {
  if (!/\.(?:ts|tsx|java)$/.test(path)) return text;
  const blanked = ' '.repeat(text.length).split('');
  for (const match of text.matchAll(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g)) {
    for (let at = 0; at < match[0].length; at += 1) blanked[match.index + at] = match[0][at] as string;
  }
  return blanked.join('');
}

export type Span = { from: number; to: number };

/** The superseded spans of a file, as offsets — text a rule must not read as a live claim. */
export function struckSpans(text: string): Span[] {
  return [...text.matchAll(STRUCK)].map((match) => ({
    from: match.index,
    to: match.index + match[0].length,
  }));
}

/**
 * The sentences of a file with every superseded span blanked out, each with the offset it starts at.
 *
 * **Blanked rather than dropped, and the difference is most of the population.** A superseded claim
 * is written `~~the old sentence~~ → **what is true now**`, so the strikethrough almost never
 * occupies a whole sentence — it is the first clause of one whose second clause is the correction.
 * Dropping any sentence that *overlaps* a struck span would excuse the correction too; dropping only
 * those that *begin* inside one — the first cut of this — excuses nothing at all, because a comment
 * marker or two words of lead-in sit in front of the `~~` almost every time. Measured: six of §212's
 * first thirty findings were text this repository had already struck through and said so.
 *
 * Offsets survive the blanking, so a caller can still say where in the file a live claim sits.
 *
 * Split on `.`/`!` followed by whitespace, which is §211's rule and is wrong in the two ways a
 * sentence splitter is always wrong — `e.g.` and a version number split early, a heading with no
 * full stop joins two claims into one. Both failures are conservative for what these rules do with
 * a sentence: they look for a phrase and a permission key near each other *in the same one*, so an
 * early split can only lose a finding and a late join can only produce one a reader sees through.
 */
export function liveSentences(text: string): { text: string; index: number }[] {
  const live = text.split('');
  for (const span of struckSpans(text)) {
    for (let at = span.from; at < span.to; at += 1) live[at] = ' ';
  }
  const blanked = live.join('');

  const sentences: { text: string; index: number }[] = [];
  let index = 0;
  for (const piece of blanked.split(/(?<=[.!])\s+/)) {
    const at = index;
    index += piece.length;
    // The separator the split consumed. Whitespace only, so its length is the gap to the next.
    index += blanked.slice(index).length - blanked.slice(index).replace(/^\s+/, '').length;
    sentences.push({ text: piece, index: at });
  }
  return sentences;
}
