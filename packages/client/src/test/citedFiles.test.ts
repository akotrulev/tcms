/**
 * A file a comment names must exist.
 *
 * ## Why this is a check and not a habit
 *
 * §205 opened by looking for one thing and finding five: `writeConformance.test.ts`,
 * ~~conformance.test.ts~~, `problemShape.test.ts`, ~~idempotency.test.ts~~ and ~~schemas.ts~~ were
 * named by eleven comments across `packages/` and **not one of them was a file**. (Two are files now
 * and three are struck, which is the notation below doing its own job: the plain ones were written,
 * the struck ones named a check that already existed under another name or a decision nobody had
 * recorded.) They are not typos. Each
 * was written at the moment the author knew what the check should be, and each then stood in for it:
 * `commands.ts` said *"the list is not decoration — `writeConformance.test.ts` walks it"* for
 * sixty-five sections while nothing walked it from that direction, and `tool.ts` justified
 * hand-written input schemas by pointing at a ~~schemas.ts~~ that had never been created.
 *
 * That is this repository's most-paid-for shape arriving through a new door. §152 found a callerless
 * method whose javadoc was evidence nothing had checked; §160 found four sites giving *"there is no
 * adapter"* as a live reason for a port that had existed since §125; §165 found three arguments
 * resting on a module *"that does not exist"* while it was 119 files. In every one of those the
 * prose was load-bearing and wrong, and in every one it was believed because prose is what a reader
 * reaches first. A filename is the cheapest possible instance and the only one a machine can settle
 * outright: the claim is `this path exists`, and the filesystem answers.
 *
 * ## Four corpora, one place
 *
 * `frontend/src` is scanned here rather than by a copy over there, and that is deliberate. The
 * walker in `contractBodies.ts` had to be copied and paired because a Vite `?raw` import and two npm
 * roots make an import impossible **in production code**; this is a test reading files off disk, the
 * repository is checked out whole by both CI jobs, and a second copy of a twenty-line scanner is the
 * §196 shape the pairing test exists to punish. So: one check, one corpus, no pair.
 *
 * What that costs is stated plainly — **a broken citation in `frontend/` reddens the `packages`
 * job.** That is the wrong job for the blame to land in, and it is still better than two scanners
 * that agree until somebody teaches one about a new extension.
 *
 * **`docs/specs` is the third corpus and §205 was wrong to leave it out.** That header said the
 * contract's prose *"is not this corpus's to police"* on the grounds that the two generated files
 * carry it — which is an argument for not scanning the **copies**, and §205 read it as an argument
 * for not scanning the source. The population was one: `WorkItemStatus` said
 * ~~boardColumns.test.ts~~ *"now derives the board's columns from this enum"* for a hundred and
 * fifty sections, and no such file has ever existed — the check it describes is real in two places
 * and neither is a test. It is the corpus whose prose the most readers meet — `npm run gen:api`
 * copies every description verbatim into `schema.d.ts` **and** `schema.ts`, so one stale sentence
 * here is three stale sentences on disk and the two downstream ones cannot be repaired where they
 * are read.
 *
 * So the generated pair stays excluded, now for the reason that always applied: a copy reports its
 * source's defect a second time and cannot be the place it is fixed.
 *
 * ## `backend/src` is the fourth, and it is scanned from here on purpose
 *
 * §205 left it out saying the same question there *"belongs to a rule of its own"*, which is true of
 * the **scanner** — javadoc writes `{@code}` where the others write a backtick, so this file grew a
 * second pattern — and false of the **home**. A Java rule was the obvious place and it is the one
 * place the rule cannot work, for a reason this repository already owns: §185's Gradle-cache trap.
 * A `Test` task's declared inputs are its classpath and its resources, and **a comment-only edit to
 * a `.java` file leaves the compiled class byte-identical**, so `:test` reports UP-TO-DATE and the
 * rule never runs.
 *
 * Measured rather than reasoned about, because §162's lesson is that an argument naming the wrong
 * mechanism reads as coverage: `./gradlew test --tests ConfigConventionTest` ran and passed, one
 * word of `CaseSort`'s javadoc changed, and the identical command answered `> Task :test UP-TO-DATE`
 * in one second. `:compileJava` did re-run. Nothing downstream of it noticed. So a javadoc rule in
 * the backend suite would be green by cache against exactly the one edit that can break it — which
 * is worse than §185's case, where the rules at least fail when their subject changes, since there
 * the subject was a file Gradle could be told about and here it is the source tree itself.
 *
 * Vitest has no such cache, so the check runs on every `npm run test:run` and reads the tree as it
 * is on disk. The cost is the one already stated one paragraph up, one stack wider: **a stale
 * javadoc citation in `backend/` reddens the `packages` job.**
 *
 * `{@link}` is deliberately not scanned. It resolves against the compiler's symbol table, so a
 * stale one is already a build failure — and that is the whole shape of this corpus's finding, since
 * what javadoc gets wrong is what it writes in the form nothing reads.
 *
 * ## The escape hatch is the repository's own notation
 *
 * A superseded claim in this repo is struck through — `~~x~~ → y` — and a struck-through filename is
 * therefore already a statement that it does *not* exist. So strikethrough spans are removed before
 * scanning, and no separate opt-out is needed for the commonest case.
 *
 * **That exclusion is where this check can quietly break, and §156 already paid for it**: a fixture's
 * unterminated `UPDATE` swallowed a whole file's literals and hid two members of the population the
 * check was written to find. An opening marker with no closing one pairs with the next marker in the
 * file instead, which inverts which text is excused and, at the limit, excuses the file.
 *
 * The guard is on that **symptom** rather than on marker parity, and the difference is not
 * fastidiousness: a parity rule cannot be satisfied by a file that writes *about* the notation an odd
 * number of times — this one — so the check would have had to exempt itself from its own corpus,
 * which is §154's defect verbatim. What is asserted instead is that the excused spans cover a small
 * share of a file. A file whose comments are two-fifths superseded text is either mid-rewrite or has
 * a marker missing, and both are worth a build failure.
 *
 * {@link CITED_BUT_ABSENT_BY_DECISION} is for what strikethrough cannot say: a present-tense
 * statement that this project *has no such file*, and a citation into somebody else's source tree.
 * It is pinned both ways (§162) — an entry naming a file that now exists fails, because a
 * one-directional ignore list cannot report a member that stopped needing to be on it.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');

/** Directories a scan must never descend into — build output, dependencies, VCS. */
const PRUNED = new Set([
  'node_modules', 'dist', 'build', '.git', '.gradle', '.idea', 'coverage', '.vite',
]);

/**
 * A path this repository could plausibly contain, by its extension.
 *
 * One list for every corpus rather than one per language, because the *notation* is repo-wide and
 * an extension only a Java comment is likely to name still resolves the same way. Widening it for
 * `backend/` is what surfaced `pom.xml` in `frontend/src/lib/labels.ts`, which had been outside the
 * pattern and is one of the two entries below.
 */
const EXTENSIONS = 'ts|tsx|yaml|yml|json|sql|java|md|css|html|xml|kts|properties';
const PATH = `([A-Za-z0-9_][A-Za-z0-9_./-]*\\.(?:${EXTENSIONS}))`;

/**
 * A backtick-quoted path.
 *
 * Backticks only, and that is what keeps the reader honest without a whitelist: this repository
 * writes every identifier and path in a comment as code, so an unquoted filename in a sentence is
 * prose about a hypothetical and quoting it is the act of claiming the file is real.
 */
const BACKTICKED = new RegExp('`' + PATH + '`', 'g');

/**
 * Javadoc's spelling of exactly that claim, and the reason `backend/` needed a second pattern.
 *
 * `{@code}` is the markup a Java comment reaches for where the other three corpora reach for a
 * backtick — and it is the *unchecked* one. `{@link Foo}` resolves against the compiler's own symbol
 * table, so a stale one is already a build failure; `{@code Foo.java}` is a formatting instruction
 * and means nothing to anybody. So the citations javadoc can get wrong are precisely the ones
 * written in the form nothing reads, which is why §205 predicted this corpus would be the
 * non-empty one. Backticks are scanned there too — a handful of comments use them — for the cost of
 * one array element.
 */
const JAVADOC_CODE = new RegExp('\\{@code\\s+' + PATH + '\\}', 'g');

/** Where comments are read from. Generated files are not in it — see the header. */
const CORPUS: { root: string; extensions: string[]; skip: string[]; patterns: RegExp[] }[] = [
  {
    root: 'packages',
    extensions: ['.ts'],
    skip: ['packages/client/src/schema.ts'],
    patterns: [BACKTICKED],
  },
  {
    root: 'frontend/src',
    extensions: ['.ts', '.tsx'],
    skip: ['frontend/src/api/schema.d.ts'],
    patterns: [BACKTICKED],
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
    patterns: [BACKTICKED],
  },
  /*
    `backend/src`, both source sets: a test's javadoc argues from the same files a main one does, and
    §205's own population was three-quarters test code.
  */
  {
    root: 'backend/src',
    extensions: ['.java'],
    skip: [],
    patterns: [BACKTICKED, JAVADOC_CODE],
  },
];

/** A superseded claim, in this repo's notation. Non-greedy and multi-line — a span often is. */
const STRUCK = /~~[\s\S]*?~~/g;

/**
 * Citations that name no file in this repository on purpose, with the reason.
 *
 * Four kinds, and none is something strikethrough could express. The first three are present-tense
 * statements that this project **has no such file** — the point of the sentence is the absence, so
 * striking the name would say the opposite. The fourth names a file in a dependency's own source.
 * The fifth is a file this repository deleted and whose deletion is the subject.
 *
 * The last two are the kind the widened corpus found, and they are the one this product is *for*:
 * **a file in the caller's project, not in ours.** A test management system reads other people's
 * build output and is configured against other people's build files, so `testng-results.xml` and
 * `pom.xml` are named correctly, precisely, and by a comment that is doing its job. There is no
 * notation for that — striking them would say the format does not exist — so they are entries.
 */
const CITED_BUT_ABSENT_BY_DECISION: Record<string, string> = {
  'error.tsx':
    'A Next.js App Router file. `ErrorBoundaries.tsx` names it to say this is a Vite SPA and the '
    + 'framework tier does not exist here, so the boundaries are the whole of it.',
  'global-error.tsx':
    'The same sentence, one tier up — see `error.tsx`.',
  'middleware.ts':
    'Next.js again. `RequireAuth.tsx` names it because a route guard is the thing a Next app would '
    + 'put there, and this app has no server to put it on.',
  'queryObserver.ts':
    'TanStack Query\'s own source, cited in `api/queries/cases.ts` for where the placeholder '
    + 'identity comparison lives. A dependency\'s file, not this repository\'s.',
  'src/mocks/handlers/injection.ts':
    'Deleted with the whole mock layer on 2026-08-06, and `lib/devFaults.ts` names it because the '
    + 'deletion is the point: the fault seam moved in front of `fetch` so it faults the real backend.',
  'testng-results.xml':
    'TestNG\'s own output file, named by `TestNgReportParser` and `ReportFormat` as the thing this '
    + 'product ingests. A file in the caller\'s CI workspace, which is where an ingestion format '
    + 'lives; this repository has never contained one and should not.',
  'pom.xml':
    'Maven\'s build file, named by `lib/labels.ts` to say why the four ingestion-format labels keep '
    + 'each project\'s own capitalisation — a person matches the label against the tool they '
    + 'configured. Also the caller\'s file, and this repository builds with Gradle.',
};

describe('a file a comment names', () => {
  const perCorpus = CORPUS.map((entry) => ({
    root: entry.root,
    patterns: entry.patterns,
    files: walk(join(REPO, entry.root))
      .map((absolute) => relative(REPO, absolute))
      .filter((path) => entry.extensions.some((extension) => path.endsWith(extension)))
      .filter((path) => !entry.skip.includes(path)),
  }));
  const files = perCorpus.flatMap((entry) => entry.files);

  const everything = new Set(walk(REPO).map((absolute) => relative(REPO, absolute)));

  const cited = perCorpus.flatMap((corpus) => corpus.files.flatMap((path) => {
    const text = readFileSync(join(REPO, path), 'utf8');
    const struck = [...text.matchAll(STRUCK)].map((match) => ({
      from: match.index,
      to: match.index + match[0].length,
    }));

    /*
      §156's defect, pre-empted — see the header. An unterminated marker excuses text nobody meant to
      excuse, and a corpus quietly missing half its citations reads exactly like a corpus with
      nothing wrong in it.
    */
    const excused = struck.reduce((total, span) => total + (span.to - span.from), 0);
    expect(
      excused / Math.max(text.length, 1),
      `${path}: superseded spans cover ${Math.round((excused / Math.max(text.length, 1)) * 100)}% `
        + 'of this file, which normally means an opening strikethrough marker has no closing one and '
        + 'has paired with the next span instead',
    ).toBeLessThan(0.4);

    return corpus.patterns.flatMap((pattern) => [...text.matchAll(pattern)]
      .filter((match) => !struck.some((span) => match.index >= span.from && match.index < span.to))
      .map((match) => ({ path, root: corpus.root, name: match[1] ?? '' })));
  }));

  it('reads every corpus, and finds citations in each', () => {
    // Two vacuity guards, because either one alone can be satisfied by a scan that found nothing.
    // A regex that stopped matching and a walk that returned nothing look identical from the
    // outside, and both look identical to a repository in which every citation resolves.
    //
    // PER CORPUS, and that is the part §205 got wrong by counting the whole population: with more
    // than one root, an entry whose directory was renamed contributes zero files and the total
    // still clears a threshold the biggest root meets on its own. Which is this check's own defect
    // one layer up — a guard against a silent empty scan, silently scanning less than it says.
    for (const corpus of perCorpus) {
      expect(corpus.files.length, `${corpus.root} contributed no files`).toBeGreaterThan(0);
      expect(
        new Set(cited.filter((entry) => entry.root === corpus.root).map((entry) => entry.name)).size,
        `${corpus.root} contributed no citations`,
      ).toBeGreaterThan(0);
    }
    expect(files.length).toBeGreaterThan(300);
    expect(new Set(cited.map((entry) => entry.name)).size).toBeGreaterThan(30);
  });

  /*
    One assertion over the whole population rather than `it.each` per name, which is the shape the
    conformance checks use. The difference is what a failure is *about*: there, each arm is a
    separate subject and a reader wants to know which one broke; here the names are one list and the
    useful failure is the whole set that stopped resolving — a rename typically breaks several at
    once, and 139 green test names for 139 filenames is noise in every run that passes.
  */
  it('resolves every one of them', () => {
    const names = [...new Set(cited.map((entry) => entry.name))].sort();
    const unresolved = names
      .filter((name) => CITED_BUT_ABSENT_BY_DECISION[name] === undefined)
      .filter((name) => ![...everything].some((path) => path === name || path.endsWith(`/${name}`)))
      .map((name) => `  \`${name}\` — named by `
        + [...new Set(cited.filter((entry) => entry.name === name).map((entry) => entry.path))].join(', '));

    expect(unresolved.join('\n'), `${unresolved.length} of ${names.length} cited files do not exist:\n\n`
      + `${unresolved.join('\n')}\n\n`
      + 'Either the citation is stale — the check it points at exists under another name, or the file '
      + 'was renamed and this comment was not — or it names a check nobody wrote, which is the '
      + 'population §205 was opened by. If the absence is the point of the sentence, strike the name '
      + 'through (`~~x~~ → y`) or put it in CITED_BUT_ABSENT_BY_DECISION with the reason.')
      .toBe('');
  });

  it('excuses nothing that has stopped needing it', () => {
    // §162's rule. A one-directional ignore list cannot report a member that came back.
    for (const [name, reason] of Object.entries(CITED_BUT_ABSENT_BY_DECISION)) {
      expect(reason.length, `${name} is excused without a reason`).toBeGreaterThan(40);
      expect(
        [...everything].some((path) => path === name || path.endsWith(`/${name}`)),
        `${name} is excused as absent and now exists — take the entry out`,
      ).toBe(false);
      expect(
        cited.some((entry) => entry.name === name),
        `${name} is excused and nothing cites it any more — take the entry out`,
      ).toBe(true);
    }
  });
});

function walk(root: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (PRUNED.has(entry.name)) continue;
    const path = join(root, entry.name);
    if (entry.isDirectory()) found.push(...walk(path));
    else if (entry.isFile()) found.push(path);
  }
  return found;
}
