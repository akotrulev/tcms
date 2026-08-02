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
     * @param ref a local {@code $ref}, e.g. {@code #/components/schemas/TestCase}
     */
    Set<String> requiredOf(String ref) {
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
