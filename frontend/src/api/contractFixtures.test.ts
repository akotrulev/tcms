/**
 * **No object literal in `src/` may be asserted to a contract type.** §79's rule, made computable.
 *
 * ## The rule already existed, in seven comments, and nothing read them
 *
 * §79 added `comments` to `Review`, and every one of `ReviewPanel.test.tsx`'s eleven tests went red
 * at *runtime* (`comments.length` of `undefined`) with **zero type errors**, because the fixture
 * ended `} as Review`. The repair was to delete the cast, and it worked: §80 measured the next
 * required field arriving as one build error in one place. Seven files then wrote the rule down in
 * prose — *"Declared as `Condition[]` and NOT cast — §79's rule"*, *"no `as BoardCard`, which is
 * §79's repair applied here"* — and there it stopped, because **a rule that lives in comments is
 * enforced by whoever happens to read one.**
 *
 * At §196 the corpus said what that is worth: **57 object literals asserted to a contract type
 * across 31 files, of which 39 were missing at least one required field** — 31 at the top level and
 * 8 more nested inside a cast, because an assertion at the top of an object turns the checker off
 * all the way down. Three of them were in
 * `CaseEditorPage.test.tsx`, which states §79's rule three times in its first hundred lines, once
 * ten lines *below* a cast fixture. Two more were in `PlanFreshness.test.tsx` and
 * `PlanLifecycle.test.tsx`, whose comments name this exact cast as the cause of a mount-time crash
 * that cost three red runs in §97 — and left the cast in place both times, because the fix that
 * presents itself when the failure is in front of you is to add the field.
 *
 * What that cost, measured rather than argued: `TestPlan.keySeq` arrived on the contract in §165
 * and **eight plan fixtures never gained it**, unobservably, for thirty sections. §195 hit the
 * same class from the other end — `declinedPreconditions` was absent from `CaseCloneDialog`'s
 * fixture, which was fine until the day a component spread it and a green test file threw.
 *
 * ## Why the rule is "no cast" rather than "no missing field"
 *
 * A cast that is complete today is complete *today*. It is the arrival of the next required field
 * that this is about, and on that day the two rules give opposite answers: the "no missing field"
 * rule passes every cast in the tree right up to the commit that breaks them, and then reports the
 * damage. The rule here refuses the mechanism, so the failure lands on `npm run build` in the file
 * that has to change anyway. **The pins below encode exactly this**: a *complete* cast is a
 * violation, and if someone later weakens this check into a missing-field check, that pin reddens.
 *
 * The message still names the missing fields, because a person who has just been told to delete a
 * cast wants to know what it was hiding.
 *
 * ## What this cannot see
 *
 * `mockResolvedValue({ … } as never)` — the standard trick for spying a generic `api.get` — escapes
 * every check in this directory, and there are dozens of them. `never` carries no shape, so there
 * is nothing to compare a literal against; a response mocked that way is unconstrained by the
 * contract and no instrument in this repo can say otherwise. It is named here rather than left as
 * an unstated limit: the population this file governs is the one where the author *did* name a
 * contract type.
 */
import { readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(HERE, '..');
const TYPES = resolve(HERE, 'types.ts');
const SCHEMA = resolve(HERE, 'schema.d.ts');

/**
 * A file that is **not on disk**, compiled alongside the real tree so the pins below run through
 * the same analyzer as the corpus.
 *
 * It has to be a program member rather than a fixture in `src/test/`, because a fixture in `src/`
 * carrying deliberate violations would be reported by the check itself — and exempting it by path
 * is how an ignore list starts. Being virtual, it can hold the violations the corpus must not.
 */
const PIN = resolve(HERE, '__contractFixturePins.virtual.ts');

const PIN_SOURCE = `
import type { TagUsage, TestCase } from './types'

// A cast that hides thirty-six missing required fields — the §195 shape.
export const incomplete = { id: 'c1' } as TestCase

// A cast that hides NOTHING today. Still a violation: see the header.
export const complete = { tag: 'smoke', caseCount: 1 } as TagUsage

// The double cast, which also silences a wrong-typed member.
export const doubled = { tag: 'smoke' } as unknown as TagUsage

// §79's repair. Not a violation — this is what the corpus is supposed to look like.
export const declared: TagUsage = { tag: 'smoke', caseCount: 1 }

// Not a violation either: \`as const\` narrows, it does not silence a required field.
export const narrowed = { tag: 'smoke' } as const
`;

function walk(directory: string, out: string[] = []): string[] {
  for (const entry of readdirSync(directory)) {
    const full = join(directory, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (full.endsWith('.ts') || full.endsWith('.tsx')) out.push(full);
  }
  return out;
}

const sources = walk(SRC);

const options: ts.CompilerOptions = {
  target: ts.ScriptTarget.ESNext,
  module: ts.ModuleKind.ESNext,
  moduleResolution: ts.ModuleResolutionKind.Bundler,
  jsx: ts.JsxEmit.ReactJSX,
  strict: true,
  skipLibCheck: true,
  noEmit: true,
};

/**
 * The program is built here rather than taken from `contractIntrospection.ts`, whose root set is
 * `api/queries/` — the call sites. This check's subject is the fixtures, which live everywhere, so
 * it needs the whole tree and a second program is the honest way to say so.
 */
const host = ts.createCompilerHost(options, true);
const realGetSourceFile = host.getSourceFile.bind(host);
host.getSourceFile = (fileName, languageVersion, onError, shouldCreate) =>
  fileName === PIN
    ? ts.createSourceFile(PIN, PIN_SOURCE, languageVersion, true)
    : realGetSourceFile(fileName, languageVersion, onError, shouldCreate);
host.fileExists = ((original) => (fileName: string) =>
  fileName === PIN || original(fileName))(host.fileExists.bind(host));

const program = ts.createProgram([...sources, PIN], options, host);
const checker = program.getTypeChecker();

/**
 * The name of the contract type a type node refers to, or undefined when it is not one.
 *
 * Resolved through the *symbol* rather than the type: `TestCase` is an alias for an indexed access
 * into `schema.d.ts`, and the resolved type carries neither an alias symbol nor a declaration that
 * names a file — so a resolver written the obvious way silently answers "not a contract type" for
 * every one of them, and this whole check reports nothing. That is the failure mode the corpus
 * guard below exists to catch, and it is not hypothetical: the first draft of this file had it.
 */
function contractName(node: ts.TypeNode): string | undefined {
  if (!ts.isTypeReferenceNode(node) || !ts.isIdentifier(node.typeName)) return undefined;
  let symbol = checker.getSymbolAtLocation(node.typeName);
  if (symbol && symbol.flags & ts.SymbolFlags.Alias) symbol = checker.getAliasedSymbol(symbol);
  const declared = symbol?.declarations?.some((declaration) => {
    const file = declaration.getSourceFile().fileName;
    return file === TYPES || file === SCHEMA;
  });
  return declared ? node.typeName.text : undefined;
}

/** The literal an assertion applies to, seen through `as unknown as T` and through parentheses. */
function assertedLiteral(node: ts.AssertionExpression): ts.ObjectLiteralExpression | undefined {
  let expression: ts.Expression = node.expression;
  while (
    ts.isAsExpression(expression)
    || ts.isTypeAssertionExpression(expression)
    || ts.isParenthesizedExpression(expression)
  ) {
    expression = expression.expression;
  }
  return ts.isObjectLiteralExpression(expression) ? expression : undefined;
}

/** Required members of `type` the literal does not supply, counting what a spread contributes. */
function missingFrom(literal: ts.ObjectLiteralExpression, type: ts.Type): string[] {
  const present = new Set<string>();
  for (const property of literal.properties) {
    if (ts.isSpreadAssignment(property)) {
      for (const member of checker.getTypeAtLocation(property.expression).getProperties()) {
        if (!(member.flags & ts.SymbolFlags.Optional)) present.add(member.name);
      }
    } else if (
      property.name
      && (ts.isIdentifier(property.name) || ts.isStringLiteral(property.name))
    ) {
      present.add(property.name.text);
    }
  }
  return type
    .getProperties()
    .filter((member) => !(member.flags & ts.SymbolFlags.Optional))
    .map((member) => member.name)
    .filter((name) => !present.has(name));
}

interface Cast {
  where: string;
  type: string;
  /** The double cast, which silences a wrong *type* as well as a missing field. */
  doubled: boolean;
  missing: string[];
}

interface Scan {
  casts: Cast[];
  /** Every `as`/`<T>` expression seen, whatever its target — the walker's own vacuity guard. */
  assertions: number;
  /** Type references naming a contract type anywhere, which proves `contractName` still resolves. */
  contractReferences: number;
}

function scan(files: readonly string[]): Scan {
  const result: Scan = { casts: [], assertions: 0, contractReferences: 0 };

  for (const file of files) {
    const source = program.getSourceFile(file);
    if (!source) throw new Error(`TypeScript did not load ${file}`);

    const visit = (node: ts.Node): void => {
      if (ts.isTypeReferenceNode(node) && contractName(node)) result.contractReferences += 1;

      if (ts.isAsExpression(node) || ts.isTypeAssertionExpression(node)) {
        result.assertions += 1;
        const name = contractName(node.type);
        const literal = name ? assertedLiteral(node) : undefined;
        if (name && literal) {
          const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
          result.casts.push({
            where: `${file.slice(SRC.length + 1)}:${line}`,
            type: name,
            doubled: ts.isAsExpression(node.expression)
              || ts.isTypeAssertionExpression(node.expression),
            missing: missingFrom(literal, checker.getTypeFromTypeNode(node.type)),
          });
        }
      }
      ts.forEachChild(node, visit);
    };

    visit(source);
  }

  return result;
}

const corpus = scan(sources);
const pins = scan([PIN]);

function describeCast(cast: Cast): string {
  const hiding = cast.missing.length
    ? `It is currently hiding ${cast.missing.length} missing required `
      + `${cast.missing.length === 1 ? 'field' : 'fields'}: ${cast.missing.join(', ')}.`
    : 'It hides nothing today, which is not the point — see the header of this file.';
  return `${cast.where} asserts an object literal to \`${cast.type}\``
    + `${cast.doubled ? ' through `unknown`' : ''}. ${hiding}`;
}

describe('contract fixtures — a literal standing in for a contract type is declared, never cast', () => {
  /**
   * The guard that keeps the assertion below from passing vacuously — and here it carries more
   * weight than usual, because **the corpus is empty by design**: the repairs that landed with this
   * file removed all 57 casts, so "found nothing" is both the expected result and what this check
   * looks like when it has stopped working. The three counts below are what tells those apart, and
   * the pins are what prove the analyzer still reports a violation when there is one.
   */
  it('inspected the tree it is written to inspect', () => {
    expect(
      sources.length,
      'almost no source files found — has the tree moved relative to this file?',
    ).toBeGreaterThanOrEqual(250);

    expect(
      corpus.assertions,
      'no type assertions found anywhere in src/ — the AST walk is not seeing `as` expressions, so '
        + 'an empty finding list means nothing',
    ).toBeGreaterThanOrEqual(100);

    expect(
      corpus.contractReferences,
      'no type reference in src/ resolved to a contract type, so `contractName` is answering "not a '
        + 'contract type" for everything and this check cannot fail. Most likely api/types.ts moved '
        + 'or stopped aliasing schema.d.ts.',
    ).toBeGreaterThanOrEqual(100);
  });

  /**
   * The pins. Each one is a shape the analyzer must classify a particular way, and between them
   * they say what the rule *is* — including the two that must NOT be reported, without which
   * "reports three" is satisfied by a check that reports everything.
   */
  it('reports a cast to a contract type, complete or not, and nothing else', () => {
    expect(pins.casts.map((cast) => cast.type).sort()).toEqual(['TagUsage', 'TagUsage', 'TestCase']);

    const incomplete = pins.casts.find((cast) => cast.type === 'TestCase');
    expect(incomplete?.missing.length, 'the missing-field diagnostic reported nothing for a '
      + 'literal that supplies one field of a thirty-seven-field type').toBeGreaterThan(30);

    // The pin that distinguishes this rule from a missing-field rule. If someone weakens the check
    // so that only incomplete casts are reported, this is what reddens.
    const complete = pins.casts.find((cast) => cast.type === 'TagUsage' && !cast.doubled);
    expect(complete, 'a COMPLETE cast to a contract type was not reported').toBeDefined();
    expect(complete?.missing).toEqual([]);

    expect(
      pins.casts.some((cast) => cast.doubled),
      '`as unknown as T` was not reported — the double cast silences more than the single one, not '
        + 'less',
    ).toBe(true);
  });

  it('is not a rule any file in src/ breaks', () => {
    expect(
      corpus.casts.map(describeCast),
      'An object literal is asserted to a contract type. Delete the assertion and let the type bind '
        + '— annotate the variable, the parameter or the function\'s return type instead:\n\n'
        + '    function caseBody(over: Partial<TestCase> = {}): TestCase {\n'
        + '      return { …every required field…, ...over }\n'
        + '    }\n\n'
        + 'The cast tells the compiler the object already is the type, so the next required field '
        + 'the contract gains is a runtime failure in every test that reads it rather than one '
        + 'build error here (§79, §97, §165, §195).\n\n'
        + 'If the fixture is deliberately malformed — a response the client must survive — assert '
        + 'the malformed member rather than the whole object, the way `DocBlockBody.test.tsx` does '
        + "with `'diagram' as DocBlock['blockType']`.",
    ).toEqual([]);
  });
});
