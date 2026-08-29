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
 * **The roots and the walker moved to `corpus.ts` in §212**, when a second rule started reading the
 * same four trees — see that file for the argument for each root and for why a rule about
 * `frontend/`, `docs/specs` and `backend/` prose lives in `packages/`. What follows is this check's
 * own half: which citation notation each corpus is read with.
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
 * A `Test` task's declared inputs are its classpath and its resources, so it sees a `.java` file
 * only through the class compiled from it — and it does not see this rule's subject at all.
 *
 * Two ways that bites, and the second is the one that matters:
 *
 * 1. **An edit that changes a comment's text without changing its line count leaves the class
 *    byte-identical.** Measured: `./gradlew test --tests ConfigConventionTest` ran and passed, one
 *    word of `CaseSort`'s javadoc changed, and the identical command answered
 *    `> Task :test UP-TO-DATE` in one second. `:compileJava` re-ran; nothing downstream noticed.
 *    (Add or remove a *line* and the line-number table shifts, the class differs, and the suite does
 *    re-run — §206's own nine repairs did — so this half is a trap, not a wall.)
 * 2. **A stale citation is usually not caused by an edit to the citing file at all.** It is caused
 *    by a rename somewhere else, and `backend/`'s declared inputs are a Dockerfile, two compose
 *    files, `deploy`, `docs/specs` and exactly one `.ts` file. Delete ~~handlers.ts~~ — which is
 *    what happened — and no backend input changes by one byte, whichever way the line numbers fall.
 *
 * Which is worse than §185's case, where the rules at least fail when their subject changes: there
 * the subject was a file Gradle could be told about, and here it is the whole repository.
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
 *
 * ## Directories are the same claim, and the reach the rule does not have
 *
 * A cited path ending in `/` is checked the same way, which took a branch in {@link PATH} and a
 * trailing marker in `corpus.ts`'s walk. Its whole population is three entries, so as a *finder* it is a
 * guard. What it is worth having for is the one it names: `src/mocks/`, the SPA's deleted mock
 * layer, cited by a backend test that is entirely right about it.
 *
 * That is worth stating precisely, because the sweep it prompted is the larger half of §206 and it
 * is the half this file cannot do. Fifteen comments in `backend/src/main` argued from the mock, a
 * second implementation deleted on 2026-08-06 — and only **two** named a file, which is why the
 * rule found two. Nine of the rest made a present-tense claim about it: that the search matches it,
 * that the facets match it, that it *has to be able to copy exactly one wording*. Every one of those
 * was a live constraint resting on something that no longer exists, and none of them is reachable by
 * asking the filesystem a question, because none of them names a path.
 *
 * So the reach is: **this rule catches a dead reference, not a dead reason.** It found the two
 * doorways into a room fifteen sentences wide, which is the argument for having it and the exact
 * measure of what it does not cover. A wordlist rule over the concept was considered and rejected —
 * the mock layer is deleted and cannot come back, so a permanent scanner for the word would be
 * guarding a closed door, and this repository has already paid for an ignore list naming the wrong
 * mechanism (§162's `CONTAINER_ERROR_PATH`).
 *
 * ## A class named as a check is the same claim again, and the biggest population of the three
 *
 * {@link JAVADOC_TEST_CLASS} takes `{@code SomethingTest}`, appends the extension, and asks the
 * filesystem for the file that would declare it. **Eight of sixty-five did not resolve** — more than both path halves found
 * together, in the corpus where a comment names the check that guards it most often.
 *
 * Six were a name that had drifted: `ArchitectureTest` for `ArchitectureRulesTest`,
 * `PlanStatisticsTest` for `PlanStatisticsEndpointTest`, `AttachmentOwnerTest` for the assertion
 * inside `CaseAttachmentEndpointTest`, `ReportHeaderTest` for `ReportRenderingTest`,
 * ~~FilesystemEvidenceStoreTest~~ for a file renamed in §125, and `ApiSurfaceTest` — twice — for a
 * guarantee that belongs to **Spring**, which refuses to build an ambiguous mapping at all. That
 * last one is the interesting kind: a framework property written as a local check, so a reader
 * looking for the test that enforces it finds nothing and concludes nothing does.
 *
 * One was `UnmatchedTest`, a contract schema and not a class at all — the entry below.
 *
 * **And one was §205's shape exactly.** `AggregateExecutionReport.WORST_FIRST` said it was *"pinned
 * by ~~AggregateExecutionPolicyTest~~ against `StatusKind`'s own value set so a kind added to the
 * product cannot silently fall through to the `ELSE` and rank as the best possible result"*. No
 * class of that name has ever existed and nothing else made that comparison, so the sentence was
 * the entire pin: a `StatusKind` added and forgotten would rank as the best result a case can have,
 * silently, in the callout whose whole job is naming the worst. `StatusKind` has grown a member
 * before. The check exists as of §206, in the class the comment names.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { CORPUS as CORPUS_ROOTS, REPO, filesOf, repoRelative, struckSpans, walk } from './corpus.js';

/**
 * A path this repository could plausibly contain, by its extension.
 *
 * One list for every corpus rather than one per language, because the *notation* is repo-wide and
 * an extension only a Java comment is likely to name still resolves the same way. Widening it for
 * `backend/` is what surfaced `pom.xml` in `frontend/src/lib/labels.ts`, which had been outside the
 * pattern and is one of the two entries below.
 */
const EXTENSIONS = 'ts|tsx|yaml|yml|json|sql|java|md|css|html|xml|kts|properties';

/**
 * A path, either kind — a filename with one of those extensions, or anything ending in `/`.
 *
 * **The trailing slash is the whole of the directory half**, and it is the reason this stayed one
 * pattern rather than becoming a second rule. A comment writes `src/mocks/` with the slash exactly
 * when it means the directory, so the notation already separates the two claims and neither needs a
 * whitelist of known folder names. Without it the branch would have to guess whether `core/rewrite`
 * is a path or a sentence, which is the kind of guess that grows an ignore list.
 *
 * Measured before it was written, because a rule whose failures are mostly noise gets silenced:
 * fifteen directories are cited across the four corpora and three do not resolve. All three are
 * below, and one of them is the finding.
 */
const PATH = `([A-Za-z0-9_][A-Za-z0-9_./-]*(?:\\.(?:${EXTENSIONS})|/))`;

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

/**
 * `{@code SomethingTest}` — a Java class named as a check, resolved against the file that would
 * declare it.
 *
 * A class is not a path, so this is a third pattern rather than a branch of {@link PATH}: the
 * citation is `SuiteScopeAgreementTest` and what has to exist is the file declaring it, so the
 * extension is appended before the lookup. Anywhere in the repository — a Java class's file
 * lives at a path determined by its package, and this rule is not the place to re-implement that.
 *
 * **Only `*Test`, and the boundary is the point.** 739 class-shaped `{@code}` tokens occur in
 * `backend/src` and 392 of them name nothing in this repository — enum constants (`ABORTED`), SQL
 * keywords (`CASE`, `DISTINCT`), migrations (`V26`), contract schemas (`TestCaseSummary`), JDK and
 * Spring types (`HttpHeaders`, `ProblemDetail`). A rule over all of them needs an ignore list
 * bigger than the finding, which is the shape that gets a check silenced rather than fixed.
 *
 * `*Test` is the tractable subset for a reason that is not convenience: **it is the subset where
 * `{@link}` is impossible.** Test classes are not on `src/main`'s compile classpath, so a javadoc
 * in `main` that wants to name the check guarding it *cannot* use the checked form — and this
 * repository names its checks from the code they guard constantly. That is precisely a population
 * of references the compiler is structurally unable to see, which is the same argument the header
 * makes for `{@code}` over `{@link}`, one turn tighter.
 *
 * It found eight of sixty-five, and one of them was not a name at all — see
 * {@link CITED_BUT_ABSENT_BY_DECISION}.
 */
const JAVADOC_TEST_CLASS = /\{@code\s+([A-Z][A-Za-z0-9]*Test)\}/g;

/** Where comments are read from. Generated files are not in it — see the header. */
type Pattern = {
  /** Captures the cited name in group 1. */
  find: RegExp;
  /**
   * Appended before the name is looked up, so a class citation resolves to the file that declares
   * it. Empty for the patterns that already capture a path.
   */
  suffix: string;
};

const CITED_PATH = { find: BACKTICKED, suffix: '' };
const CITED_PATH_IN_JAVADOC = { find: JAVADOC_CODE, suffix: '' };
const CITED_TEST_CLASS = { find: JAVADOC_TEST_CLASS, suffix: '.java' };

/**
 * Which patterns each root is read with. The roots themselves — and the argument for each — are
 * `corpus.ts`'s, shared with `tierClaims.test.ts` since §212; what stays here is the half that is
 * about *citations*, which is the per-language notation. `backend/src` needs three because javadoc
 * writes `{@code}` where the other three write a backtick.
 */
const PATTERNS: Record<string, Pattern[]> = {
  'packages': [CITED_PATH],
  'frontend/src': [CITED_PATH],
  'docs/specs': [CITED_PATH],
  'backend/src': [CITED_PATH, CITED_PATH_IN_JAVADOC, CITED_TEST_CLASS],
};

const CORPUS = CORPUS_ROOTS.map((entry) => ({
  ...entry,
  patterns: PATTERNS[entry.root] ?? [CITED_PATH],
}));


/**
 * Citations that name no file in this repository on purpose, with the reason.
 *
 * Four kinds, and none is something strikethrough could express. The first three are present-tense
 * statements that this project **has no such file** — the point of the sentence is the absence, so
 * striking the name would say the opposite. The fourth names a file in a dependency's own source.
 * The fifth is a file this repository deleted and whose deletion is the subject.
 *
 * Three of the rest are the kind the widened corpus found, and they are the one this product is
 * *for*: **a path in the caller's project, not in ours.** A test management system reads other
 * people's build output and is configured against other people's build files, so
 * `testng-results.xml`, `pom.xml` and `allure-results/` are named correctly, precisely, and by
 * comments that are doing their job. There is no notation for that — striking them would say the
 * format does not exist — so they are entries.
 *
 * `dist/` is the one entry that is none of those: the path is this repository's and the scanner is
 * what cannot see it, because the walk prunes build output. It is written that way rather than by
 * un-pruning `dist`, which would make the check's answer depend on whether anyone had run a build.
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
  'allure-results/':
    'Allure\'s own output directory, named by `AllureReportParser` as the thing this product ingests '
    + '— the third entry of that kind, after `testng-results.xml` and `pom.xml`, and the reason that '
    + 'kind is worth naming: a test management system cites the caller\'s tree constantly.',
  'dist/':
    'This repository\'s, and pruned by the walk above along with `node_modules` — it holds build '
    + 'output, exists only after `npm run build`, and is absent on a fresh checkout. Cited by both '
    + '`harness.ts` copies for where the compiled entry point a spawned CLI runs comes from. The '
    + 'entry says the scanner cannot see it, not that it is missing.',
  'UnmatchedTest.java':
    'NOT A CLASS — a contract schema whose name happens to end in Test, cited by `UnmatchedTestView` '
    + 'and saying so in the same sentence ("in `docs/specs/openapi.yaml`"). The `*Test` pattern '
    + 'cannot tell a JUnit class from a noun phrase about a test that could not be placed, and '
    + 'narrowing the regex to exclude it would exclude real classes too.',
  'src/mocks/':
    'Deleted with the whole mock layer on 2026-08-06 (§107), and `ContractParameterBindingTest` '
    + 'names it because the deletion is what removed the last legitimate reason for a declared '
    + 'operation to have no handler — which is why that test stopped skipping them. The one comment '
    + 'in `backend/` that names the mock layer and is right about it.',

  /*
    The thirteen below are one event: `19376dd6` ("Refresh claude files", 2026-08-28) removed the whole
    historical documentation tree — `docs/audits/`, `docs/agent-context/`, the dated plans and
    `docs/plans/001-unified-step-model/` — deliberately, leaving `docs/specs/`, `docs/plans/003…006`
    and `docs/business-model/` as the authorities. Twenty-odd comments across `backend/` and
    `frontend/` cite one of them.

    THEY ARE ENTERED HERE RATHER THAN STRUCK THROUGH IN THE SOURCES, and the choice is worth stating
    because it is the opposite of what this map's header prefers. Strikethrough is right when the
    ARGUMENT is superseded; here every one of these sentences is still live and still correct — the
    §-numbered reasoning in `FeatureImportService`, the audit finding `DataFreshness` implements, the
    catalogue `shapes.ts` renders. What has gone is the *document*, not the decision. Editing twenty
    files to strike a filename would say the reasoning had been reversed, which is exactly the
    misreading the strikethrough notation exists to prevent.

    Both directions still hold: restoring any of these files fails this map, which is the property
    that stops an entry outliving its absence.

    THE SECOND SIX WERE FOUND BY CI, NOT BY THE SWEEP THAT WROTE THE FIRST SIX, and that is worth
    recording where the next reader will hit it: this rule reads four corpora and `backend/src` is
    the one whose citations are written as `{@code}`, so a sweep done by grepping for backticks
    finds `packages/` and `frontend/` and stops one notation short of the largest population. The
    run that caught them is the packages job, reddened by five files under `backend/src/main` — the
    cross-corpus reach the header claims, doing the thing it claims, one commit late.
  */
  'docs/plans/001-unified-step-model/':
    'The directory the three below belonged to, named by this map\'s own comment so the six entries '
    + 'read as one event rather than six coincidences. Removed in `19376dd6`. It is here because the '
    + 'directory rule treats a trailing slash as the same claim a filename makes, and this file is in '
    + 'the corpus it scans — the rule declining to exempt its own prose is the property that keeps it '
    + 'honest.',
  'api-surface.md':
    'Removed with `docs/plans/001-unified-step-model/` in `19376dd6`. The plan it belonged to is '
    + 'built — the unified step model IS the schema — so the citations are historical attributions '
    + 'for shapes that now live in `docs/specs/openapi.yaml`, and the sentences around them are '
    + 'about code that exists.',
  'data-model.md':
    'The same document set and the same event — see `api-surface.md`. Cited by the feature-import '
    + 'and gherkin-backfill files for where the step model was specified before it was built.',
  'integrations.md':
    'The same document set and the same event — see `api-surface.md`. Cited for the automation-key '
    + 'contract, which `AutomationKeyMapping` now holds outright.',
  'docs/audits/2026-08-15-flow-review-and-improvements.md':
    'Removed with the whole of `docs/audits/` in `19376dd6`. Its findings are implemented — E2 is '
    + '`V66`\'s idempotency key, and `DataFreshness` is the freshness half — so the citations name '
    + 'the origin of a decision rather than a document a reader has to open.',
  'docs/plans/2026-08-18-unplanned-gaps.md':
    'Removed with the dated plans in `19376dd6`. Its gaps are closed: run scheduling is `V68` and '
    + '`RunScheduleEndpointTest` is what checks it, which is the file that cites it.',
  'docs/plans/2026-08-26-authoring-shape-catalogue.md':
    'Removed with the dated plans in `19376dd6`. The catalogue it specified is `core/shapes.ts` and '
    + '`mcp/src/shape.ts`, which are the two files that cite it — the specification became the '
    + 'implementation, and those two are now the authority on it.',
  'docs/audits/':
    'The directory the entry below belonged to, and named by this map\'s own comment above so the '
    + 'removal reads as one event. Removed in `19376dd6`. Here for the same reason as '
    + '`docs/plans/001-unified-step-model/`: the directory rule treats a trailing slash as the claim '
    + 'a filename makes, and this file is in the corpus it scans.',
  'docs/audits/2026-07-30-v1-schema-first-execution.md':
    'Removed with the whole of `docs/audits/` in `19376dd6`. It is where the SYSTEM-scope RLS trap '
    + 'was measured rather than reasoned, and all three citations — `RoleAssignment`, '
    + '`RoleGrantService` and `identity/package-info` — say exactly that about a split that is still '
    + 'how the schema is built: `instance_role_assignment` is a separate table because a nullable '
    + '`company_id` is permanently unreachable under RLS.',
  'docs/handoff/':
    'The directory the entry below belonged to, named by that entry\'s own reason so the removal '
    + 'reads as one event — the same shape as `docs/audits/` above and for the same reason: this '
    + 'file is in the corpus it scans, and a trailing slash is a claim. Removed in `19376dd6`.',
  'docs/handoff/35-m1-s12-case-versions.md':
    'Removed with `docs/handoff/` in `19376dd6`. `CaseController` cites it for a divergence that is '
    + 'still open — the contract declares only a 200 for the versions path and the endpoint serves a '
    + '404 as well — so the sentence describes today\'s behaviour and the missing document is the '
    + 'record of who owns closing it, which is the contract holder either way.',
  'docs/plans/2026-07-30-tcms-invitation-activation-login.md':
    'Removed with the dated plans in `19376dd6`. `SessionTokens` cites it for why the `sys`/`cmp`/'
    + '`prj` grant claims are reserved and not issued — an unmeasured optimization against a second '
    + 'authorization-resolution path — and that is still the token\'s shape, so the reasoning is '
    + 'live and only its transcript is gone.',
  'docs/plans/2026-08-04-m2.5-reporting-slice-plan.md':
    'Removed with the dated plans in `19376dd6`. `reporting/package-info` cites it for the R4–R8 '
    + 'decomposition of §6e\'s ten reports; R3 is what shipped, so the citation names the plan the '
    + 'unbuilt half came from rather than a document a reader has to open to understand the module.',
  'docs/plans/2026-08-08-mcp-server-wip-plan.md':
    'Removed with the dated plans in `19376dd6`. Its phases are built — `@tcms/client` is phase 4, '
    + 'the CLI 5 and 7, the MCP server 8 — and `client/src/index.ts` cites it beside '
    + '`docs/specs/2026-08-08-mcp-tool-and-command-surface.md`, which is the surviving authority on '
    + 'that surface and the one the sentence sends a reader to.',
};

describe('a file a comment names', () => {
  const perCorpus = CORPUS.map((entry) => ({
    root: entry.root,
    patterns: entry.patterns,
    files: filesOf(entry),
  }));
  const files = perCorpus.flatMap((entry) => entry.files);

  const everything = new Set(walk(REPO).map(repoRelative));

  const cited = perCorpus.flatMap((corpus) => corpus.files.flatMap((path) => {
    const text = readFileSync(join(REPO, path), 'utf8');
    const struck = struckSpans(text);

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

    return corpus.patterns.flatMap((pattern) => [...text.matchAll(pattern.find)]
      .filter((match) => !struck.some((span) => match.index >= span.from && match.index < span.to))
      .map((match) => ({ path, root: corpus.root, name: `${match[1] ?? ''}${pattern.suffix}` })));
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
