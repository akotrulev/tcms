package com.tcms.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/**
 * A parsed view of {@code docs/specs/openapi.yaml} for the tests that check the contract
 * against itself.
 *
 * <p>The contract is the one authoritative artifact in this repo with <strong>no computed
 * check over it at all</strong>. Every other catalogue-wide invariant here is machine-checked
 * — {@code company_id} by {@code SchemaConventionTest}, the role grants by
 * {@code SeedDataTest}, the module seams by ArchUnit — and each of those exists because
 * reading the artifact top to bottom missed violations that scripting the comparison found
 * in seconds. The working agreement states the general form: <em>a catalogue-wide invariant
 * needs a computed check, not a careful reading.</em>
 *
 * <p>This class deliberately parses the YAML <em>as written</em> rather than through an
 * OpenAPI object model. A model normalises — it fills in defaults, collapses
 * {@code $ref} indirection and silently drops keywords it does not recognise — and every
 * defect {@link ContractConventionTest} looks for is a property of the source text: an
 * absent {@code required} array, an absent {@code 4xx}, a {@code nullable} in a position
 * where the generator ignores it. A parser that repairs those on the way in cannot see them.
 */
public final class OpenApiContract {

    /** HTTP methods, as spelled in an OpenAPI path item. Anything else there is metadata. */
    private static final Set<String> OPERATION_KEYS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    private final Map<String, Object> root;
    private final Path source;

    private OpenApiContract(Map<String, Object> root, Path source) {
        this.root = root;
        this.source = source;
    }

    /**
     * Locates and parses the contract.
     *
     * <p>The spec lives at the <em>repo</em> root while Gradle runs tests with the working
     * directory set to {@code backend/}, so the path is found by walking up rather than
     * hard-coded as {@code ../docs/…}. A relative literal breaks the moment a test is run
     * from an IDE with a different working directory, and it breaks as a
     * {@code NoSuchFileException} that reads like the file is missing.
     */
    public static OpenApiContract load() {
        Path spec = locateSpec();
        try (InputStream in = Files.newInputStream(spec)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new Yaml().loadAs(in, Map.class);
            return new OpenApiContract(parsed, spec);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the OpenAPI contract at " + spec, e);
        }
    }

    private static Path locateSpec() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs/specs/openapi.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "docs/specs/openapi.yaml not found above " + Path.of("").toAbsolutePath());
    }

    /** The spec's location, for assertion messages that have to be actionable. */
    Path source() {
        return source;
    }

    /** Every operation in the document, in declaration order. */
    List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        map(root, "paths").forEach((path, item) -> {
            Map<String, Object> pathItem = asMap(item);
            pathItem.forEach((key, value) -> {
                if (OPERATION_KEYS.contains(key)) {
                    operations.add(new Operation(path, key, asMap(value)));
                }
            });
        });
        return operations;
    }

    /**
     * The query-parameter names {@code path} accepts, across both the path-level
     * {@code parameters} block and every operation's own, with
     * {@code #/components/parameters/*} references resolved.
     *
     * <p>Resolving the references is the point: {@code ProjectId}, {@code Page} and
     * {@code Size} are shared, so a check that only read inline parameters would report
     * them as undeclared and be wrong about the three most-used parameters in the contract.
     */
    public Set<String> queryParameterNames(String path) {
        Map<String, Object> pathItem = asMap(map(root, "paths").get(path));
        if (pathItem.isEmpty()) {
            throw new IllegalArgumentException("No such path in the contract: " + path);
        }
        Set<String> names = new LinkedHashSet<>();
        collectQueryParameters(pathItem.get("parameters"), names);
        pathItem.forEach((key, value) -> {
            if (OPERATION_KEYS.contains(key)) {
                collectQueryParameters(asMap(value).get("parameters"), names);
            }
        });
        return names;
    }

    /**
     * The query parameters declared on the path item itself, per path — i.e. the ones every
     * operation under that path inherits whether it binds them or not. Paths with none are
     * absent from the map, so an empty map is the passing state.
     */
    Map<String, Set<String>> pathLevelQueryParameters() {
        Map<String, Set<String>> offenders = new LinkedHashMap<>();
        map(root, "paths").forEach((path, item) -> {
            Set<String> names = new LinkedHashSet<>();
            collectQueryParameters(asMap(item).get("parameters"), names);
            if (!names.isEmpty()) {
                offenders.put(path, names);
            }
        });
        return offenders;
    }

    /** The HTTP methods declared under a path, uppercased — for a failure message. */
    List<String> methodsOf(String path) {
        return asMap(map(root, "paths").get(path)).keySet().stream()
                .filter(OPERATION_KEYS::contains)
                .map(key -> key.toUpperCase(java.util.Locale.ROOT))
                .toList();
    }

    /**
     * The query parameters one operation accepts: its own plus the path item's, since the
     * server sees no difference between the two.
     *
     * <p>Distinct from {@link #queryParameterNames(String)}, which unions every operation under
     * a path — the right question for "would this path accept the key at all", and the wrong one
     * for "must this handler bind it".
     */
    Set<String> queryParametersOf(Operation operation) {
        Set<String> names = new LinkedHashSet<>();
        collectQueryParameters(asMap(map(root, "paths").get(operation.path())).get("parameters"), names);
        collectQueryParameters(operation.body().get("parameters"), names);
        return names;
    }

    private void collectQueryParameters(Object parameters, Set<String> into) {
        if (!(parameters instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            Map<String, Object> parameter = resolve(asMap(entry));
            if ("query".equals(parameter.get("in")) && parameter.get("name") instanceof String name) {
                into.add(name);
            }
        }
    }

    /** Follows a local {@code $ref} one hop; anything else is returned unchanged. */
    Map<String, Object> resolve(Map<String, Object> node) {
        if (!(node.get("$ref") instanceof String ref)) {
            return node;
        }
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("Only local $refs are supported, got: " + ref);
        }
        Object current = root;
        for (String segment : ref.substring(2).split("/")) {
            current = asMap(current).get(segment);
            if (current == null) {
                throw new IllegalArgumentException("Dangling $ref: " + ref);
            }
        }
        return resolve(asMap(current));
    }

    /**
     * Every property a schema requires, <strong>collected through {@code allOf}</strong>.
     *
     * <p>The traversal is the whole value. {@code required} does not descend, so a schema written
     * as {@code allOf: [Summary, {required: [...]}]} states its obligations in two places that no
     * reader holds in mind at once — which is exactly how a property required by the first branch
     * came to be missing from the record serving the whole thing. Reading either branch alone
     * yields a list that looks complete.
     *
     * <p>Public, unlike most of this class: {@code CaseUpdateEndpointTest} compares the declared
     * set against what the server actually refuses to work without, which is the one direction
     * a contract test inside this package cannot check — §143 found the contract requiring one
     * field where the server had required four since M0, and nothing here could have seen it.
     *
     * @param ref a local {@code $ref}, e.g. {@code #/components/schemas/TestCase}
     */
    public Set<String> requiredOf(String ref) {
        Set<String> required = new java.util.LinkedHashSet<>();
        collectRequired(resolve(Map.of("$ref", ref)), required);
        return required;
    }

    private void collectRequired(Map<String, Object> schema, Set<String> into) {
        if (schema.get("required") instanceof List<?> declared) {
            declared.forEach(name -> into.add(String.valueOf(name)));
        }
        if (schema.get("allOf") instanceof List<?> branches) {
            for (Object branch : branches) {
                collectRequired(resolve(asMap(branch)), into);
            }
        }
    }

    /**
     * Every property a schema declares, <strong>collected through {@code allOf}</strong> —
     * {@link #requiredOf}'s superset, and the one a record's components are comparable to.
     *
     * <p>{@code required} was the only half {@link ContractConventionTest} compared until §165, and
     * a subset comparison can only fail in one direction: a property the record lacks. The other —
     * a component the schema does not declare — is a value Jackson writes that no generated union
     * can name, which is the direction {@code Problem.instance} went for sixteen sections, and it is
     * invisible to a rule that only walks the {@code required} array. {@code TestPlan.keySeq} was
     * the instance here, on seven operations.
     *
     * <p>Optional properties are in, and that is the point rather than an oversight: an optional
     * property with no component is a promise nothing keeps — the server cannot send the key under
     * any circumstances — and the contract does not distinguish "optional" from "not implemented".
     * §148's finding, arriving at the schema instead of at the response.
     *
     * @param ref a local {@code $ref}, e.g. {@code #/components/schemas/TestCase}
     */
    Set<String> propertiesOf(String ref) {
        Set<String> properties = new LinkedHashSet<>();
        collectProperties(resolve(Map.of("$ref", ref)), properties);
        return properties;
    }

    private void collectProperties(Map<String, Object> schema, Set<String> into) {
        if (schema.get("properties") instanceof Map<?, ?> declared) {
            declared.keySet().forEach(name -> into.add(String.valueOf(name)));
        }
        if (schema.get("allOf") instanceof List<?> branches) {
            for (Object branch : branches) {
                collectProperties(resolve(asMap(branch)), into);
            }
        }
    }

    /**
     * The parts a {@code multipart/form-data} request body declares, each mapped to whether the
     * schema <strong>requires</strong> it. Empty for an operation with no multipart body, which is
     * every operation but four.
     *
     * <p>This is the one body position with no record behind it, and
     * {@code ContractConventionTest.BOUND_AS_FORM_PARTS} says why: Spring binds a form to a
     * handler's parameter list, so the "shape" is the signature. That left the parts themselves
     * compared to nothing until §171 — the gap that map recorded — and the required flag travels
     * with them because it is half the comparison: a part the contract calls optional and the
     * handler demands is a 400 on a legal request, and the reverse is a null reaching code written
     * for a value.
     */
    Map<String, Boolean> formPartsOf(Operation operation) {
        Map<String, Object> schema = asMap(asMap(asMap(asMap(operation.body().get("requestBody"))
                .get("content")).get("multipart/form-data")).get("schema"));
        if (schema.isEmpty()) {
            return Map.of();
        }
        Set<String> declared = new LinkedHashSet<>();
        collectProperties(schema, declared);
        Set<String> required = new LinkedHashSet<>();
        collectRequired(schema, required);
        Map<String, Boolean> parts = new LinkedHashMap<>();
        declared.forEach(name -> parts.put(name, required.contains(name)));
        return parts;
    }

    /**
     * The {@code components.schemas} entries that declare an object shape — anything with
     * {@code properties} of its own or an {@code allOf} that reaches some.
     *
     * <p>The counterpart of {@link #enumSchemas()} and narrowed the same way and for the same
     * reason: <strong>component-level only</strong>. The document holds many more object shapes
     * inline on a property — {@code RoleAssignment.team} and {@code CurrentUser.teams} are two, and
     * both are served by a Java record — but an anonymous shape has no name to correspond by, so
     * there is nothing for a pairing map to key on. A named schema is a named generated type, which
     * is what makes it a thing a client can be wrong about by name.
     */
    Set<String> objectSchemas() {
        Set<String> names = new LinkedHashSet<>();
        map(map(root, "components"), "schemas").forEach((name, schema) -> {
            Map<String, Object> node = asMap(schema);
            if (node.get("properties") instanceof Map<?, ?> properties && !properties.isEmpty()) {
                names.add(name);
            } else if (node.get("allOf") instanceof List<?> branches && !branches.isEmpty()) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * Every <strong>anonymous</strong> object shape in the document, by a location a human can
     * find it at, mapped to the properties it declares (through {@code allOf}).
     *
     * <p>{@link #objectSchemas()} is the named half and says why it stops there: <em>"an anonymous
     * shape has no name to correspond by, so there is nothing for a pairing map to key on"</em>.
     * That is true of the <em>name</em> and false of the <em>correspondence</em> — §170. A shape
     * declared at {@code TestCaseSummary.lastResult} has a location, the location is as stable as
     * the schema around it, and a record serves it exactly as if it had been named. What the
     * generator does with it is the reason to care: an inline object becomes an anonymous inlined
     * TypeScript type rather than a named union, so a consumer reads the shape off the property and
     * never off a type it can import — which makes a divergence here <em>less</em> visible
     * downstream, not more.
     *
     * <p>Three exclusions, each of them a shape already compared somewhere:
     *
     * <ul>
     *   <li><strong>{@code allOf} branches of a named schema</strong> — folded into their parent by
     *       {@link #propertiesOf}, so {@code TestCase}'s second branch is compared as part of
     *       {@code TestCase}. Recording it separately would report every property of the
     *       <em>first</em> branch as missing from a record that has them all.</li>
     *   <li><strong>A property that is a {@code $ref}</strong> — that is the named lane.</li>
     *   <li><strong>A shape with no {@code properties} at all</strong> — the free-form maps
     *       ({@code AiProposal.grounding}, {@code Configuration.attributes},
     *       {@code ReportDefinition.parameters}, the dashboard {@code filter}s). They are
     *       {@code additionalProperties} objects, they travel as a {@code JsonNode} or a
     *       {@code Map}, and a record-component comparison has nothing to say about one.</li>
     * </ul>
     *
     * <p>An <em>inline body or response</em> is included and keyed by its operation, because that
     * is the position with no name at all — the two paged list responses, the two auth bodies, the
     * four multipart forms, the case history and the report request.
     */
    Map<String, Set<String>> inlineObjectSchemas() {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        map(map(root, "components"), "schemas")
                .forEach((name, schema) -> inlineFrom(asMap(schema), name, false, found));
        for (Operation operation : operations()) {
            asMap(asMap(operation.body().get("requestBody")).get("content"))
                    .forEach((mediaType, node) -> inlineBody(
                            node, operation.describe() + " (request)", found));
            asMap(operation.body().get("responses")).forEach((code, response) ->
                    asMap(resolve(asMap(response)).get("content")).forEach((mediaType, node) ->
                            inlineBody(node, operation.describe() + " (" + code + ")", found)));
        }
        return found;
    }

    private void inlineBody(Object mediaTypeNode, String location, Map<String, Set<String>> into) {
        Map<String, Object> schema = asMap(asMap(mediaTypeNode).get("schema"));
        if (schema.containsKey("$ref")) {
            return;
        }
        inlineFrom(schema, location, true, into);
    }

    /**
     * @param anonymous whether {@code schema} is itself an unnamed shape to record, as opposed to a
     *                  named one being descended into for the unnamed shapes hanging off it
     */
    private void inlineFrom(
            Map<String, Object> schema,
            String location,
            boolean anonymous,
            Map<String, Set<String>> into) {
        if (anonymous && declaresPropertiesItself(schema)) {
            Set<String> properties = new LinkedHashSet<>();
            collectProperties(schema, properties);
            into.put(location, properties);
        }
        for (Map<String, Object> part : inlineParts(schema)) {
            if (!(part.get("properties") instanceof Map<?, ?> declared)) {
                continue;
            }
            declared.forEach((name, value) -> {
                Map<String, Object> child = asMap(value);
                if (child.containsKey("$ref")) {
                    return;
                }
                if (child.get("items") instanceof Map<?, ?> items) {
                    Map<String, Object> item = asMap(items);
                    if (!item.containsKey("$ref")) {
                        inlineFrom(item, location + "." + name + "[]", true, into);
                    }
                    return;
                }
                inlineFrom(child, location + "." + name, true, into);
            });
        }
    }

    /**
     * Whether a node is a shape of its own rather than a wrapper around somebody else's.
     *
     * <p><strong>This is the whole difference between an anonymous object and the nullable-$ref
     * idiom</strong>, and getting it wrong is not subtle: {@code nullableRefIsWrappedInAllOf}
     * (above) requires every nullable reference in this document to be written
     * {@code allOf: [{$ref: UserRef}], nullable: true}, so a rule that judged by the properties a
     * node <em>resolves</em> to would see UserRef's four members at all twenty-two of those
     * positions and demand a record for each. They are one schema referenced twenty-two times, and
     * {@code UserRef} is already paired.
     *
     * <p>So the test is what the node and its <em>inline</em> branches declare themselves. A paged
     * response — {@code allOf: [{$ref: PageMeta}, {properties: {items, facets}}]} — passes it on
     * the second branch, and its recorded member set is still the union through {@code allOf},
     * because the record serving it carries the paging fields too.
     */
    private boolean declaresPropertiesItself(Map<String, Object> schema) {
        return inlineParts(schema).stream()
                .anyMatch(part -> part.get("properties") instanceof Map<?, ?> declared
                        && !declared.isEmpty());
    }

    /** A schema and its <em>inline</em> {@code allOf} branches — never the {@code $ref}'d ones. */
    private List<Map<String, Object>> inlineParts(Map<String, Object> schema) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(schema);
        if (schema.get("allOf") instanceof List<?> branches) {
            for (Object branch : branches) {
                Map<String, Object> node = asMap(branch);
                if (!node.containsKey("$ref")) {
                    parts.addAll(inlineParts(node));
                }
            }
        }
        return parts;
    }

    /**
     * The named schemas some part of the document {@code $ref}s <strong>other than as an
     * {@code allOf} branch</strong> — i.e. the ones something sends or accepts whole.
     *
     * <p>The complement is what {@code ContractConventionTest.BRANCH_ONLY_SCHEMAS} needs: a schema
     * reached only as a branch has no record of its own, because no handler returns it — the
     * composition is what travels. Derived here rather than asserted there so the exemption is
     * re-checked against the document on every run instead of being taken on trust (§161).
     *
     * <p>The flag is recomputed per map key rather than inherited down the whole subtree, and that
     * is the part worth reading twice: a {@code $ref} <em>inside</em> an inline {@code allOf}
     * branch — {@code TestCase}'s branch referencing {@code TestStep}, say — is an ordinary
     * reference and must count as one. Only the branch positions themselves are excluded.
     */
    Set<String> schemasReferencedOutsideAllOf() {
        Set<String> names = new LinkedHashSet<>();
        collectRefs(root, false, names);
        return names;
    }

    private void collectRefs(Object node, boolean asAllOfBranch, Set<String> into) {
        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> node0 = asMap(raw);
            if (!asAllOfBranch
                    && node0.get("$ref") instanceof String ref
                    && ref.startsWith(SCHEMA_REF_PREFIX)) {
                into.add(ref.substring(SCHEMA_REF_PREFIX.length()));
            }
            node0.forEach((key, value) -> collectRefs(value, "allOf".equals(key), into));
        } else if (node instanceof List<?> list) {
            list.forEach(element -> collectRefs(element, asAllOfBranch, into));
        }
    }

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    /**
     * The named enum types — every {@code components.schemas} entry that declares an
     * {@code enum}, mapped to its members in declaration order.
     *
     * <p><strong>Component-level only, and the narrowing is measured rather than assumed.</strong>
     * The document holds 44 more enums inline on a property ({@code TestRun.status},
     * {@code DocBlock.blockType}, …). Those are the same kind of value and a rule over them would
     * be the same rule, but they have no name to correspond by: the owner map
     * {@link ContractEnumTest} needs would be 64 hand-written lines instead of 20, and two thirds
     * of it would be guesses about which entity's field an anonymous list belongs to. A named
     * schema is a named server type, which is what makes the correspondence checkable at all —
     * and it is also what the generator emits as a named union, so it is the population a client
     * can be wrong about by name.
     */
    public Map<String, List<String>> enumSchemas() {
        Map<String, List<String>> enums = new LinkedHashMap<>();
        map(map(root, "components"), "schemas").forEach((name, schema) -> {
            if (asMap(schema).get("enum") instanceof List<?> members) {
                enums.put(name, members.stream().map(String::valueOf).toList());
            }
        });
        return enums;
    }

    /**
     * Visits every mapping node in the document, deepest-last, handing the visitor a
     * JSON-Pointer-ish location so a failure message names the line a human has to edit.
     */
    void walk(NodeVisitor visitor) {
        walk(root, "", visitor);
    }

    private void walk(Object node, String location, NodeVisitor visitor) {
        if (node instanceof Map<?, ?> map) {
            visitor.visit(location.isEmpty() ? "(root)" : location, asMap(map));
            map.forEach((key, value) -> walk(value, location + "." + key, visitor));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), location + "[" + i + "]", visitor);
            }
        }
    }

    private Map<String, Object> map(Map<String, Object> owner, String key) {
        return asMap(owner.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : new LinkedHashMap<>();
    }

    /** One HTTP operation: where it lives, what it is, and its body. */
    record Operation(String path, String method, Map<String, Object> body) {

        /** As it should read in a failure message — {@code GET /projects/{projectId}}. */
        String describe() {
            return method.toUpperCase(java.util.Locale.ROOT) + " " + path;
        }

        Set<String> responseCodes() {
            Object responses = body.get("responses");
            return responses instanceof Map<?, ?> map
                    ? map.keySet().stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                    : Set.of();
        }
    }

    /** Receives every mapping node the document contains. */
    @FunctionalInterface
    interface NodeVisitor {
        void visit(String location, Map<String, Object> node);
    }
}
