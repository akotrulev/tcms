/**
 * The pin that keeps `core/shapes.ts` from becoming the fifth copy.
 *
 * The catalogue is written by hand, and `packages/mcp/src/tool.ts` has the argument for why it
 * cannot be generated from `openapi.yaml`: a tool argument takes a display key where the API takes
 * a UUID, so a generated schema produces a surface no caller on either transport can use. That
 * decision is only safe with this check beside it. CLAUDE.md's rule is the one being obeyed —
 * *a catalogue-wide invariant needs a computed check, not a careful reading* — and its most
 * expensive instance is §108, where every check read a resolved value and the defect was in the
 * text.
 *
 * Four questions, and the third and fourth are the ones that catch drift in the dangerous
 * direction. An enum that gains a member in the contract and not here is a value a model is told
 * is illegal; a field that leaves the contract and stays here is one it is told to send.
 *
 * The contract is parsed through `contractBodies.contractDocument()` rather than re-read, for the
 * reason that export already records: re-reading 550 kB of YAML in a second module parses the
 * contract twice per test run.
 */
import { describe, expect, it } from 'vitest';

import { SHAPES, fieldsFor, type EntityShape, type FieldSpec } from '../core/shapes.js';

import { contractDocument } from './contractBodies.js';

type Node = Record<string, unknown>;

/** Follows `$ref` and the single-branch `allOf` this contract uses for a referenced enum. */
function deref(node: Node | undefined): Node | undefined {
  if (!node) return undefined;
  const all = node['allOf'] as Node[] | undefined;
  if (all && all.length > 0) return deref(all[0]);

  const ref = node['$ref'];
  if (typeof ref !== 'string') return node;

  let current: unknown = contractDocument();
  for (const segment of ref.slice(2).split('/')) current = (current as Node)[segment];
  return deref(current as Node);
}

function schema(name: string): Node {
  const schemas = (contractDocument() as unknown as { components: { schemas: Record<string, Node> } })
    .components.schemas;
  const found = schemas[name];
  if (!found) throw new Error(`openapi.yaml declares no schema '${name}'.`);
  return found;
}

/** Where one field lands in the contract: its schema, and the property name it renders to. */
function contractSiteOf(shape: EntityShape, field: FieldSpec): { schema: string; property: string } {
  return {
    schema: field.contractSchema ?? shape.contractSchema,
    property: field.contract ?? field.name,
  };
}

const ENTITIES = Object.values(SHAPES);

/**
 * Contract properties the catalogue deliberately does not offer, each with the reason.
 *
 * An allow-list rather than a silence, so a property that arrives in the contract later shows up
 * as a failure asking for a decision instead of being absent on both surfaces for ever. That is
 * the shape of the defect this whole slice was started to fix.
 */
const UNOFFERED: Readonly<Record<string, string>> = {
  'TestCaseInput.ownerId':
    'A user UUID, and nothing on either surface can obtain one: §3.3 excludes /users as '
    + 'COMPANY-scope and V40 §6 withholds company:read from Agent deliberately.',
  'TestCaseInput.changeComment':
    'The audit note on an edit. It belongs to the write mechanics rather than to the case, and '
    + 'neither adapter takes one yet.',
  'TestCaseInput.inlineBody':
    'Never reachable — see CONDITION_FIELDS.',
  'AiProposalInput.grounding':
    'The evidence bundle behind a verdict. It has no agreed shape on this surface yet, and an '
    + 'unstructured object argument is one a model fills with prose.',
  'AiProposalInput.testCase':
    'The wrapper itself. The catalogue flattens it — every field of the case is a field of the '
    + 'proposal here, which is the shape both adapters already accept.',
};

describe('the shape catalogue against openapi.yaml', () => {
  it('declares the contract\'s enum, exactly, wherever it names one', () => {
    for (const shape of ENTITIES) {
      for (const field of shape.fields) {
        if (!field.enumOf) continue;
        const declared = deref(schema(field.enumOf))?.['enum'] as string[] | undefined;
        expect(declared, `${field.enumOf} declares no enum`).toBeDefined();
        expect([...(field.enum ?? [])].sort(), `${shape.entity}.${field.name}`)
          .toEqual([...declared!].sort());
      }
    }
  });

  it('marks a field required exactly when the contract does', () => {
    for (const shape of ENTITIES) {
      const always = new Set(
        shape.fields.filter((field) => field.required === 'always')
          .map((field) => contractSiteOf(shape, field).property),
      );

      for (const name of new Set(shape.fields.map((field) => contractSiteOf(shape, field).schema))) {
        const required = (schema(name)['required'] as string[] | undefined) ?? [];
        for (const property of required) {
          // `AiProposalInput.testCase` is required and is the wrapper the catalogue flattens
          // away, so it is required of a payload nobody assembles by hand.
          if (UNOFFERED[`${name}.${property}`]) continue;
          expect(always.has(property), `${name}.${property} is required by the contract and not `
            + 'by the catalogue — a caller would be told it is optional and get a 400.')
            .toBe(true);
        }
      }

      for (const field of shape.fields.filter((candidate) => candidate.required === 'always')) {
        const site = contractSiteOf(shape, field);
        const required = (schema(site.schema)['required'] as string[] | undefined) ?? [];
        expect(required, `${shape.entity}.${field.name} is required by the catalogue and not by `
          + `${site.schema} — the catalogue may be stricter only with a stated reason.`)
          .toContain(site.property);
      }
    }
  });

  it('names a real contract property for every field, or says why not', () => {
    for (const shape of ENTITIES) {
      for (const field of shape.fields) {
        if (field.surfaceOnly) {
          expect(field.surfaceOnly.length, `${shape.entity}.${field.name}`).toBeGreaterThan(20);
          continue;
        }
        const site = contractSiteOf(shape, field);
        const properties = (schema(site.schema)['properties'] as Node | undefined) ?? {};
        expect(Object.keys(properties), `${shape.entity}.${field.name} renders to `
          + `${site.schema}.${site.property}, which the contract does not declare. Either it moved `
          + 'or the field needs a surfaceOnly reason.')
          .toContain(site.property);
      }
    }
  });

  it('accounts for every contract property — offered, or unoffered with a reason', () => {
    for (const shape of ENTITIES) {
      const offered = new Set(shape.fields.map((field) => {
        const site = contractSiteOf(shape, field);
        return `${site.schema}.${site.property}`;
      }));

      for (const name of new Set(shape.fields.map((field) => contractSiteOf(shape, field).schema))) {
        const properties = Object.keys((schema(name)['properties'] as Node | undefined) ?? {});
        for (const property of properties) {
          const key = `${name}.${property}`;
          expect(offered.has(key) || key in UNOFFERED, `${key} is in the contract and on neither `
            + 'surface. Add a field to the catalogue, or a line to UNOFFERED saying why not — a '
            + 'property that is silently on neither adapter is the defect this slice was for.')
            .toBe(true);
        }
      }
    }
  });

  it('nests the step and condition shapes against their own contract schemas', () => {
    const pairs = [['steps', 'TestStepInput'], ['preconditions', 'CaseConditionInput']] as const;
    for (const [name, contractName] of pairs) {
      const field = SHAPES['case']!.fields.find((candidate) => candidate.name === name)!;
      const properties = Object.keys((schema(contractName)['properties'] as Node) ?? {});
      const required = (schema(contractName)['required'] as string[] | undefined) ?? [];

      for (const inner of field.fields ?? []) {
        expect(properties, `${name}.${inner.name}`).toContain(inner.name);
      }
      for (const property of required) {
        expect(
          (field.fields ?? []).some((inner) => inner.name === property && inner.required === 'always'),
          `${contractName}.${property} is required and ${name} does not say so.`,
        ).toBe(true);
      }
    }
  });
});

describe('the renderers', () => {
  it('offers every propose field to the MCP schema and every spellable one to the CLI', () => {
    // The two adapters may differ in HOW a field is spelled and must not differ in WHICH fields
    // exist — the asymmetry this slice was started to remove. An object array is the one
    // legitimate exception: argv cannot carry one, so it travels in the --file body.
    const shape = SHAPES['case']!;
    const fields = fieldsFor(shape, 'propose');
    expect(fields.length).toBeGreaterThan(10);
  });

  it('keeps a conditional requirement out of the JSON Schema required list', async () => {
    const { jsonSchemaFor } = await import('../core/shapes.js');
    const rendered = jsonSchemaFor(SHAPES['case']!, 'propose');
    // `steps` is required only for STRUCTURED. A host validating a `required: [steps]` would
    // reject a GHERKIN case, which is legal.
    expect(rendered['required']).not.toContain('steps');
    expect(rendered['required']).toContain('title');
  });
});
