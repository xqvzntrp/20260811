package compiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Compiler {
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final Pattern QUALIFIED = Pattern.compile("[A-Z_][A-Z0-9_]*\\.[A-Z_][A-Z0-9_]*");
    private static final Pattern AGGREGATE = Pattern.compile("([A-Z_][A-Z0-9_]*)\\(([^()]+)\\)");
    private static final Set<String> SPEC_CORE_FIELDS = Set.of(
            "SPEC_ID", "TABLE_NAME", "COLUMN_NAME", "VALUE_TYPE", "NULLABLE",
            "PRIMARY_KEY_NAME", "PRIMARY_KEY_PART", "FOREIGN_KEY_NAME", "FOREIGN_KEY_PART",
            "REFERENCES_TABLE", "REFERENCES_COLUMN", "CARDINALITY");
    private static final List<String> SPEC_SCHEMA_HEADER = List.of(
            "ORDINAL", "FIELD", "REQUIRED_WHEN", "DOMAIN");
    private static final List<String> PROFILE_HEADER = List.of(
            "RELATION", "SUBJECT_KINDS", "OBJECT_ROLE", "OBJECT_CONSTRAINT", "MIN", "MAX");
    private static final Set<String> ORDERED_TYPES = Set.of(
            "#IDENTIFIER", "#STRING", "#NUMBER", "#DATE");

    private record Location(String file, int line) {
        String prefix() { return file + ":" + line + ": "; }
    }

    public record Assertion(String packageId, int ordinal, String subject, String relation,
                            String object, Location location) {}
    private record Profile(String relation, Set<String> subjectKinds, String role,
                           Set<String> constraints, int min, int max, Location location) {}
    private record Model(List<Assertion> assertions, Map<String, String> kinds) {}
    private record Compilation(Model authored, SpecCatalog spec, Model combined, Path specPath,
                               String languageVersion) {}
    private record KeyPart(int position, String table, String column, Location location) {}
    private record ForeignPart(int position, String fromTable, String fromColumn,
                               String toTable, String toColumn, String cardinality,
                               Location location) {}
    private record ColumnRow(String table, String column, String type, String nullable,
                             Location location) {}
    private record PropertyScope(String group, String property) {}
    private record SpecCatalog(Model model, Map<String, String> primaryKeyByTable) {}
    private record SpecField(int ordinal, String name, String requiredWhen,
                             Set<String> domain, Location location) {}
    private record SpecSchema(List<SpecField> fields, Map<String, SpecField> byName) {
        List<String> header() { return fields.stream().map(SpecField::name).toList(); }
    }

    public static final class ModelError extends RuntimeException {
        public final String code;
        ModelError(String code, String message) { super(message); this.code = code; }
    }

    private Compiler() {}

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            if (args.length == 2 && upper(args[0]).equals("VALIDATE_PROFILE")) {
                List<Profile> profiles = loadProfiles(Path.of(args[1]));
                System.out.println("VALID " + profiles.size() + " RELATION PROFILES");
                return;
            }
            if (args.length == 2 && upper(args[0]).equals("VALIDATE_SPEC_SCHEMA")) {
                SpecSchema schema = loadSpecSchema(Path.of(args[1]));
                System.out.println("VALID " + schema.fields.size() + " SPEC FIELDS");
                return;
            }
            if (args.length == 3 && upper(args[0]).equals("VALIDATE_SPEC")) {
                SpecSchema schema = loadSpecSchema(Path.of(args[2]));
                SpecCatalog spec = parseSpec(Path.of(args[1]), schema);
                System.out.println("VALID " + spec.model.assertions.size() + " SPEC ASSERTIONS");
                return;
            }
            if (args.length < 2) throw new ModelError("USAGE", "Compiler validate|compile|emit_sql|export MODEL [OUTPUT]");
            Path source = Path.of(args[1]);
            Path root = locateRoot(source.toAbsolutePath());
            Compilation compilation = validate(root, source);
            switch (upper(args[0])) {
                case "VALIDATE" -> System.out.println("VALID " + compilation.combined.assertions.size() + " ASSERTIONS");
                case "COMPILE" -> System.out.print(render(compilation.combined.assertions));
                case "EMIT_SQL" -> {
                    if (args.length > 3) throw new ModelError("USAGE", "EMIT_SQL accepts MODEL [OUTPUT]");
                    String sql = emitPostgresSql(compilation);
                    if (args.length == 3) {
                        Path output = Path.of(args[2]);
                        Path parent = output.toAbsolutePath().getParent();
                        if (parent != null) Files.createDirectories(parent);
                        Files.writeString(output, sql, StandardCharsets.UTF_8);
                        System.out.println("EMITTED " + output.toAbsolutePath());
                    } else {
                        System.out.print(sql);
                    }
                }
                case "EXPORT" -> {
                    if (args.length != 3) throw new ModelError("USAGE", "EXPORT requires an output ZIP path");
                    export(root, source, Path.of(args[2]), compilation);
                    System.out.println("EXPORTED " + Path.of(args[2]).toAbsolutePath());
                }
                default -> throw new ModelError("USAGE", "Unknown command: " + args[0]);
            }
        } catch (ModelError e) {
            System.err.println("INVALID " + e.code + " " + e.getMessage());
            System.exit(2);
        }
    }

    private static Path locateRoot(Path path) {
        Path p = Files.isDirectory(path) ? path : path.getParent();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("schema/model-schema.tsv"))) return p;
            p = p.getParent();
        }
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("schema/model-schema.tsv"))) return cwd;
        throw new ModelError("SCHEMA_NOT_FOUND", "Could not locate schema/model-schema.tsv");
    }

    private static Compilation validate(Path root, Path source) throws IOException {
        Model authored = parseBlocks(source);
        String supportedVersion = Files.readString(root.resolve("VERSION"), StandardCharsets.UTF_8).trim();
        Assertion languageVersion = singleAssertion(authored, "PACKAGE", "LANGUAGE_VERSION");
        if (!languageVersion.object.equals(upper(supportedVersion))) {
            throw error("UNSUPPORTED_LANGUAGE_VERSION", languageVersion,
                    "Expected " + supportedVersion + ", received " + languageVersion.object);
        }
        Assertion specFileAssertion = singleAssertion(authored, "PACKAGE", "SPEC_FILE");
        Path specPath = resolveCaseInsensitive(root, specFileAssertion.object);
        if (specPath == null || !specPath.startsWith(root) || !Files.isRegularFile(specPath)) {
            throw error("SPEC_NOT_FOUND", specFileAssertion,
                    "SPEC_FILE does not resolve to a file inside the repository: " + specFileAssertion.object);
        }
        SpecSchema specSchema = loadSpecSchema(root.resolve("schema/spec-schema.tsv"));
        SpecCatalog spec = parseSpec(specPath, specSchema);
        Model combined = combine(spec.model, authored);
        validateBlockSchema(authored, combined, loadProfiles(root.resolve("schema/model-schema.tsv")));
        validateGroups(combined, spec);
        return new Compilation(authored, spec, combined, specPath, supportedVersion);
    }

    private static Path resolveCaseInsensitive(Path root, String relative) throws IOException {
        Path requested = Path.of(relative);
        if (requested.isAbsolute()) return null;
        Path current = root;
        for (Path component : requested) {
            String name = component.toString();
            if (name.equals(".") || name.equals("..")) return null;
            List<Path> matches;
            try (var children = Files.list(current)) {
                matches = children.filter(p -> p.getFileName().toString().equalsIgnoreCase(name)).toList();
            }
            if (matches.size() > 1) throw new ModelError("AMBIGUOUS_CASE_PATH", relative + " has case-only path duplicates");
            if (matches.isEmpty()) return null;
            current = matches.get(0);
        }
        return current.normalize();
    }

    private static Model parseBlocks(Path source) throws IOException {
        List<Assertion> assertions = new ArrayList<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        Set<String> exact = new HashSet<>();
        String active = null;
        String packageId = null;
        int ordinal = 10;
        int lineNumber = 0;
        for (String raw : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            lineNumber++;
            Location location = new Location(source.getFileName().toString(), lineNumber);
            int comment = raw.indexOf("//");
            String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("+")) {
                String[] p = line.substring(1).trim().split("\\s+", 2);
                if (p.length != 2) throw error("INVALID_SUBJECT_INTRODUCTION", location, "Expected + KIND SUBJECT");
                String kind = identifier(p[0], "kind", location);
                String subject = identifier(p[1], "subject", location);
                if (kinds.putIfAbsent(subject, kind) != null) {
                    throw error("DUPLICATE_SUBJECT", location, "Subject already introduced: " + subject);
                }
                if (packageId == null) {
                    if (!kind.equals("PACKAGE")) throw error("PACKAGE_REQUIRED_FIRST", location, "The first subject must be PACKAGE");
                    packageId = subject;
                } else if (kind.equals("PACKAGE")) {
                    throw error("MULTIPLE_PACKAGES", location, "A model contains exactly one PACKAGE");
                }
                active = subject;
                assertions.add(new Assertion(packageId, ordinal, subject, "KIND", kind, location));
                exact.add(subject + "\u0000KIND\u0000" + kind);
                ordinal += 10;
            } else {
                if (active == null) throw error("ORPHAN_ASSERTION", location, "Assertion precedes a subject");
                String[] p = line.split("\\s+", 2);
                if (p.length != 2) throw error("INVALID_ASSERTION", location, "Expected RELATION OBJECT");
                String relation = identifier(p[0], "relation", location);
                String object = upper(p[1]);
                String key = active + "\u0000" + relation + "\u0000" + object;
                if (!exact.add(key)) throw error("DUPLICATE_ASSERTION", location, "Duplicate assertion");
                assertions.add(new Assertion(packageId, ordinal, active, relation, object, location));
                ordinal += 10;
            }
        }
        if (packageId == null) throw new ModelError("PACKAGE_REQUIRED", source.getFileName() + ": model has no PACKAGE");
        return new Model(List.copyOf(assertions), Map.copyOf(kinds));
    }

    private static SpecSchema loadSpecSchema(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String file = path.getFileName().toString();
        if (lines.isEmpty()) throw error("INVALID_SPEC_SCHEMA_HEADER", new Location(file, 1),
                "Specification schema is empty");
        List<String> header = List.of(lines.get(0).split("\\t", -1)).stream()
                .map(Compiler::upper).toList();
        if (!header.equals(SPEC_SCHEMA_HEADER)) throw error("INVALID_SPEC_SCHEMA_HEADER",
                new Location(file, 1), "Expected: " + String.join("\\t", SPEC_SCHEMA_HEADER));
        List<SpecField> fields = new ArrayList<>();
        Map<String, SpecField> byName = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            Location location = new Location(file, i + 1);
            String[] raw = lines.get(i).split("\\t", -1);
            if (raw.length != 4) throw error("INVALID_SPEC_SCHEMA_ROW", location,
                    "Expected 4 fields, found " + raw.length);
            for (int j = 0; j < raw.length; j++) raw[j] = upper(raw[j]);
            int ordinal = positive(raw[0], "ORDINAL", location);
            requireIdentifier(raw[1], "FIELD", location);
            if (raw[2].isEmpty() || raw[3].isEmpty()) throw error("INVALID_SPEC_SCHEMA_ROW",
                    location, "REQUIRED_WHEN and DOMAIN must be present");
            Set<String> domain = splitDomain(raw[3], location);
            SpecField field = new SpecField(ordinal, raw[1], raw[2], domain, location);
            if (byName.putIfAbsent(field.name, field) != null) throw error("DUPLICATE_SPEC_FIELD",
                    location, field.name);
            fields.add(field);
        }
        fields.sort(Comparator.comparingInt(SpecField::ordinal));
        contiguous("SPEC_SCHEMA", fields.stream().map(SpecField::ordinal).toList(),
                fields.isEmpty() ? new Location(file, 1) : fields.get(0).location);
        if (!byName.keySet().equals(SPEC_CORE_FIELDS)) throw error("SPEC_SCHEMA_CORE_FIELDS",
                new Location(file, 1), "Expected exactly: " + SPEC_CORE_FIELDS
                        + ", received: " + byName.keySet());
        for (SpecField field : fields) {
            if (!(field.requiredWhen.equals("ALWAYS") || field.requiredWhen.equals("OPTIONAL"))) {
                SpecField controller = byName.get(field.requiredWhen);
                if (controller == null) throw error("UNKNOWN_REQUIRED_WHEN", field.location,
                        field.requiredWhen + " on " + field.name);
                if (controller.ordinal >= field.ordinal) throw error("REQUIRED_WHEN_ORDER", field.location,
                        field.requiredWhen + " must precede " + field.name);
            }
        }
        return new SpecSchema(List.copyOf(fields), Map.copyOf(byName));
    }

    private static Set<String> splitDomain(String value, Location location) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split("\\|", -1)) {
            String token = upper(part);
            if (token.isEmpty() || !result.add(token)) throw error("INVALID_SPEC_DOMAIN", location, value);
        }
        for (String token : result) if (token.startsWith("@")
                && !(result.size() == 1 && Set.of("@IDENTIFIER", "@POSITIVE_INTEGER").contains(token))) {
            throw error("INVALID_SPEC_DOMAIN", location,
                    "Meta-domains @IDENTIFIER and @POSITIVE_INTEGER must appear alone");
        }
        return Set.copyOf(result);
    }

    private static void validateSpecRow(Map<String, String> row, SpecSchema schema,
                                        Location location) {
        for (SpecField field : schema.fields) {
            String value = row.get(field.name);
            boolean present = !value.isEmpty();
            boolean required = switch (field.requiredWhen) {
                case "ALWAYS" -> true;
                case "OPTIONAL" -> false;
                default -> !row.get(field.requiredWhen).isEmpty();
            };
            if (field.requiredWhen.equals("OPTIONAL")) {
                // Optional fields are independently optional.
            } else if (present != required) {
                throw error("SPEC_FIELD_PRESENCE", location, field.name + " must be present exactly when "
                        + field.requiredWhen + " is present");
            }
            if (!present) continue;
            if (field.domain.equals(Set.of("@IDENTIFIER"))) {
                requireIdentifier(value, field.name, location);
            } else if (field.domain.equals(Set.of("@POSITIVE_INTEGER"))) {
                positive(value, field.name, location);
            } else if (!field.domain.contains(value)) {
                throw error("SPEC_VALUE_OUTSIDE_DOMAIN", location,
                        field.name + " does not accept " + value + "; expected " + field.domain);
            }
        }
    }

    private static SpecCatalog parseSpec(Path path, SpecSchema schema) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String file = path.getFileName().toString();
        if (lines.isEmpty()) throw error("INVALID_SPEC_HEADER", new Location(file, 1), "Specification is empty");
        List<String> header = List.of(lines.get(0).split("\\t", -1)).stream().map(Compiler::upper).toList();
        if (!header.equals(schema.header())) throw error("INVALID_SPEC_HEADER", new Location(file, 1),
                "Expected from executable schema: " + String.join("\\t", schema.header()));

        LinkedHashMap<String, ColumnRow> columns = new LinkedHashMap<>();
        LinkedHashMap<String, List<KeyPart>> keys = new LinkedHashMap<>();
        LinkedHashMap<String, List<ForeignPart>> foreignKeys = new LinkedHashMap<>();
        LinkedHashSet<String> tableOrder = new LinkedHashSet<>();
        Map<String, Location> tableLocations = new LinkedHashMap<>();
        String specId = null;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            Location location = new Location(file, i + 1);
            String[] raw = lines.get(i).split("\\t", -1);
            if (raw.length != schema.fields.size()) throw error("INVALID_SPEC_ROW", location,
                    "Expected " + schema.fields.size() + " fields, found " + raw.length);
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < raw.length; j++) row.put(schema.fields.get(j).name, upper(raw[j]));
            validateSpecRow(row, schema, location);
            String rowSpecId = row.get("SPEC_ID");
            String table = row.get("TABLE_NAME");
            String column = row.get("COLUMN_NAME");
            if (specId == null) specId = rowSpecId;
            else if (!specId.equals(rowSpecId)) throw error("MULTIPLE_SPEC_IDS", location,
                    "Every row must use " + specId);
            String qualified = table + "." + column;
            if (columns.putIfAbsent(qualified,
                    new ColumnRow(table, column, "#" + row.get("VALUE_TYPE"),
                            "#" + row.get("NULLABLE"), location)) != null) {
                throw error("DUPLICATE_SPEC_COLUMN", location, "Duplicate column after case normalization: " + qualified);
            }
            tableOrder.add(table);
            tableLocations.putIfAbsent(table, location);
            boolean hasKey = !row.get("PRIMARY_KEY_NAME").isEmpty();
            if (hasKey) {
                String key = row.get("PRIMARY_KEY_NAME");
                keys.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new KeyPart(
                        positive(row.get("PRIMARY_KEY_PART"), "PRIMARY_KEY_PART", location),
                        table, qualified, location));
            }
            boolean hasForeign = !row.get("FOREIGN_KEY_NAME").isEmpty();
            if (hasForeign) {
                String foreignKey = row.get("FOREIGN_KEY_NAME");
                String toTable = row.get("REFERENCES_TABLE");
                foreignKeys.computeIfAbsent(foreignKey, ignored -> new ArrayList<>()).add(new ForeignPart(
                        positive(row.get("FOREIGN_KEY_PART"), "FOREIGN_KEY_PART", location),
                        table, qualified, toTable, toTable + "." + row.get("REFERENCES_COLUMN"),
                        "#" + row.get("CARDINALITY"), location));
            }
        }
        if (specId == null) throw error("EMPTY_SPEC", new Location(file, 1), "Specification has no data rows");
        Map<String, String> primaryKeyByTable = validateKeys(tableOrder, columns, keys, tableLocations);
        validateForeignKeys(columns, keys, foreignKeys, primaryKeyByTable);
        return buildSpecModel(specId, tableOrder, tableLocations, columns, keys, foreignKeys, primaryKeyByTable);
    }

    private static Map<String, String> validateKeys(Set<String> tables, Map<String, ColumnRow> columns,
                                                     Map<String, List<KeyPart>> keys,
                                                     Map<String, Location> tableLocations) {
        Map<String, String> keyByTable = new LinkedHashMap<>();
        for (Map.Entry<String, List<KeyPart>> e : keys.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(KeyPart::position));
            contiguous(e.getKey(), e.getValue().stream().map(KeyPart::position).toList(), e.getValue().get(0).location);
            String table = e.getValue().get(0).table;
            Set<String> columnsSeen = new HashSet<>();
            for (KeyPart part : e.getValue()) {
                if (!part.table.equals(table)) throw error("PRIMARY_KEY_TABLE_MISMATCH", part.location,
                        e.getKey() + " spans multiple tables");
                if (!columnsSeen.add(part.column)) throw error("DUPLICATE_PRIMARY_KEY_COLUMN", part.location,
                        part.column + " occurs more than once in " + e.getKey());
                if (!columns.get(part.column).nullable.equals("#FALSE")) throw error("NULLABLE_KEY", part.location,
                        part.column + " must be non-nullable");
            }
            if (keyByTable.putIfAbsent(table, e.getKey()) != null) throw error("MULTIPLE_PRIMARY_KEYS",
                    e.getValue().get(0).location, table + " has more than one primary key");
        }
        for (String table : tables) if (!keyByTable.containsKey(table)) {
            throw error("MISSING_PRIMARY_KEY", tableLocations.get(table), table + " has no primary key");
        }
        return Map.copyOf(keyByTable);
    }

    private static void validateForeignKeys(Map<String, ColumnRow> columns,
                                            Map<String, List<KeyPart>> keys,
                                            Map<String, List<ForeignPart>> foreignKeys,
                                            Map<String, String> primaryKeyByTable) {
        Map<String, List<String>> keyColumns = new HashMap<>();
        for (Map.Entry<String, List<KeyPart>> e : keys.entrySet()) {
            keyColumns.put(e.getKey(), e.getValue().stream().map(KeyPart::column).toList());
        }
        for (Map.Entry<String, List<ForeignPart>> e : foreignKeys.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(ForeignPart::position));
            ForeignPart first = e.getValue().get(0);
            contiguous(e.getKey(), e.getValue().stream().map(ForeignPart::position).toList(), first.location);
            Set<String> sources = new HashSet<>();
            for (ForeignPart p : e.getValue()) {
                if (!p.fromTable.equals(first.fromTable) || !p.toTable.equals(first.toTable)
                        || !p.cardinality.equals(first.cardinality)) {
                    throw error("FOREIGN_KEY_INCONSISTENT", p.location,
                            e.getKey() + " has inconsistent endpoint metadata");
                }
                if (!sources.add(p.fromColumn)) throw error("DUPLICATE_FOREIGN_KEY_COLUMN", p.location,
                        p.fromColumn + " occurs more than once in " + e.getKey());
                ColumnRow from = columns.get(p.fromColumn);
                ColumnRow to = columns.get(p.toColumn);
                if (to == null) throw error("UNKNOWN_SPEC_REFERENCE", p.location, p.toColumn + " is not declared");
                if (!from.type.equals(to.type)) throw error("FOREIGN_KEY_TYPE_MISMATCH", p.location,
                        p.fromColumn + " and " + p.toColumn + " have different value types");
            }
            List<String> targets = e.getValue().stream().map(ForeignPart::toColumn).toList();
            List<String> targetKey = keyColumns.get(primaryKeyByTable.get(first.toTable));
            if (!targets.equals(targetKey)) throw error("FOREIGN_KEY_TARGET_NOT_PRIMARY_KEY", first.location,
                    e.getKey() + " must reference the complete primary key of " + first.toTable);
            if (first.cardinality.equals("#ONE_TO_ONE")) {
                List<String> sourceColumns = e.getValue().stream().map(ForeignPart::fromColumn).toList();
                List<String> sourceKey = keyColumns.get(primaryKeyByTable.get(first.fromTable));
                if (!sourceColumns.equals(sourceKey)) throw error("ONE_TO_ONE_SOURCE_NOT_UNIQUE", first.location,
                        e.getKey() + " declares ONE_TO_ONE but its source columns are not the complete "
                                + first.fromTable + " primary key");
            }
        }
    }

    private static SpecCatalog buildSpecModel(String specId, Set<String> tables,
                                               Map<String, Location> tableLocations,
                                               Map<String, ColumnRow> columns,
                                               Map<String, List<KeyPart>> keys,
                                               Map<String, List<ForeignPart>> foreignKeys,
                                               Map<String, String> primaryKeyByTable) {
        List<Assertion> out = new ArrayList<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        int ordinal = 10;
        ordinal = add(out, kinds, specId, ordinal, specId, "SPEC", List.of(),
                new Location(specId + ".SPEC.TSV", 1));
        for (String table : tables) {
            ordinal = add(out, kinds, specId, ordinal, table, "TABLE", List.of(), tableLocations.get(table));
        }
        for (Map.Entry<String, ColumnRow> e : columns.entrySet()) {
            ColumnRow c = e.getValue();
            ordinal = add(out, kinds, specId, ordinal, e.getKey(), "COLUMN",
                    List.of(Map.entry("TABLE", c.table), Map.entry("VALUE_TYPE", c.type),
                            Map.entry("NULLABLE", c.nullable)), c.location);
        }
        for (Map.Entry<String, List<KeyPart>> e : keys.entrySet()) {
            String table = e.getValue().get(0).table;
            List<Map.Entry<String, String>> relations = new ArrayList<>();
            relations.add(Map.entry("TABLE", table));
            for (KeyPart part : e.getValue()) relations.add(Map.entry("KEY_PART", part.column));
            ordinal = add(out, kinds, specId, ordinal, e.getKey(), "PRIMARY_KEY", relations,
                    e.getValue().get(0).location);
        }
        for (Map.Entry<String, List<ForeignPart>> e : foreignKeys.entrySet()) {
            ForeignPart first = e.getValue().get(0);
            List<Map.Entry<String, String>> relations = new ArrayList<>();
            relations.add(Map.entry("FROM_TABLE", first.fromTable));
            for (ForeignPart part : e.getValue()) relations.add(Map.entry("FROM_COLUMN", part.fromColumn));
            relations.add(Map.entry("TO_TABLE", first.toTable));
            for (ForeignPart part : e.getValue()) relations.add(Map.entry("TO_COLUMN", part.toColumn));
            relations.add(Map.entry("CARDINALITY", first.cardinality));
            ordinal = add(out, kinds, specId, ordinal, e.getKey(), "FOREIGN_KEY", relations, first.location);
        }
        return new SpecCatalog(new Model(List.copyOf(out), Map.copyOf(kinds)), primaryKeyByTable);
    }

    private static int add(List<Assertion> out, Map<String, String> kinds, String packageId,
                           int ordinal, String subject, String kind,
                           List<Map.Entry<String, String>> relations, Location location) {
        if (kinds.putIfAbsent(subject, kind) != null) throw error("DUPLICATE_SPEC_SUBJECT", location, subject);
        out.add(new Assertion(packageId, ordinal, subject, "KIND", kind, location));
        ordinal += 10;
        for (Map.Entry<String, String> relation : relations) {
            out.add(new Assertion(packageId, ordinal, subject, relation.getKey(), relation.getValue(), location));
            ordinal += 10;
        }
        return ordinal;
    }

    private static Model combine(Model spec, Model authored) {
        Map<String, String> kinds = new LinkedHashMap<>(spec.kinds);
        for (Map.Entry<String, String> e : authored.kinds.entrySet()) {
            if (kinds.putIfAbsent(e.getKey(), e.getValue()) != null) {
                Assertion declaration = authored.assertions.stream()
                        .filter(a -> a.subject.equals(e.getKey()) && a.relation.equals("KIND")).findFirst().orElseThrow();
                throw error("DUPLICATE_SUBJECT", declaration,
                        e.getKey() + " exists in both specification and model");
            }
        }
        List<Assertion> assertions = new ArrayList<>(spec.assertions);
        assertions.addAll(authored.assertions);
        return new Model(List.copyOf(assertions), Map.copyOf(kinds));
    }

    private static List<Profile> loadProfiles(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String file = path.getFileName().toString();
        if (lines.isEmpty()) throw error("INVALID_PROFILE_HEADER", new Location(file, 1), "Profile is empty");
        List<String> header = List.of(lines.get(0).split("\\t", -1)).stream().map(Compiler::upper).toList();
        if (!header.equals(PROFILE_HEADER)) throw error("INVALID_PROFILE_HEADER", new Location(file, 1),
                "Expected: " + String.join("\\t", PROFILE_HEADER));
        List<Profile> result = new ArrayList<>();
        Set<String> relations = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            Location location = new Location(file, i + 1);
            String[] p = lines.get(i).split("\\t", -1);
            if (p.length != 6) throw error("INVALID_PROFILE_ROW", location, "Expected 6 fields, found " + p.length);
            for (int j = 0; j < p.length; j++) p[j] = upper(p[j]);
            requireIdentifier(p[0], "RELATION", location);
            if (!relations.add(p[0])) throw error("DUPLICATE_RELATION_PROFILE", location, p[0]);
            Set<String> subjectKinds = splitSet(p[1], location);
            if (subjectKinds.isEmpty()) throw error("INVALID_PROFILE_SUBJECT_KINDS", location, p[1]);
            for (String kind : subjectKinds) requireIdentifier(kind, "SUBJECT_KIND", location);
            if (!Set.of("REFERENCE", "TEXT", "VOCABULARY").contains(p[2])) {
                throw error("INVALID_PROFILE_ROLE", location, p[2]);
            }
            Set<String> constraints = splitSet(p[3], location);
            if (p[2].equals("REFERENCE")) {
                if (constraints.isEmpty()) throw error("MISSING_PROFILE_CONSTRAINT", location, p[0]);
                for (String kind : constraints) requireIdentifier(kind, "OBJECT_CONSTRAINT", location);
            } else if (p[2].equals("TEXT") && !constraints.isEmpty()) {
                throw error("UNEXPECTED_PROFILE_CONSTRAINT", location, p[0]);
            }
            int min = nonnegative(p[4], "MIN", location);
            int max = p[5].equals("*") ? Integer.MAX_VALUE : nonnegative(p[5], "MAX", location);
            if (max < min) throw error("INVALID_PROFILE_CARDINALITY", location, "MAX is less than MIN");
            result.add(new Profile(p[0], subjectKinds, p[2], constraints, min, max, location));
        }
        if (result.isEmpty()) throw error("EMPTY_PROFILE", new Location(file, 1), "No relation profiles");
        return List.copyOf(result);
    }

    private static void validateBlockSchema(Model authored, Model combined, List<Profile> profiles) {
        Map<String, Profile> byRelation = new HashMap<>();
        Set<String> allowedKinds = new HashSet<>();
        for (Profile profile : profiles) {
            byRelation.put(profile.relation, profile);
            allowedKinds.addAll(profile.subjectKinds);
        }
        Map<String, Map<String, Integer>> counts = new HashMap<>();
        for (Map.Entry<String, String> e : authored.kinds.entrySet()) if (!allowedKinds.contains(e.getValue())) {
            Assertion declaration = authored.assertions.stream()
                    .filter(a -> a.subject.equals(e.getKey()) && a.relation.equals("KIND")).findFirst().orElseThrow();
            throw error("UNKNOWN_SUBJECT_KIND", declaration, e.getKey() + " has kind " + e.getValue());
        }
        for (Assertion assertion : authored.assertions) {
            if (assertion.relation.equals("KIND")) continue;
            Profile profile = byRelation.get(assertion.relation);
            if (profile == null) throw error("UNKNOWN_RELATION", assertion,
                    assertion.relation + " on " + assertion.subject);
            String subjectKind = authored.kinds.get(assertion.subject);
            if (!profile.subjectKinds.contains(subjectKind)) throw error("INVALID_SUBJECT_KIND", assertion,
                    assertion.relation + " is not valid on " + subjectKind);
            counts.computeIfAbsent(assertion.subject, ignored -> new HashMap<>())
                    .merge(assertion.relation, 1, Integer::sum);
            if (profile.role.equals("REFERENCE")) {
                String kind = combined.kinds.get(assertion.object);
                if (kind == null) throw error("UNKNOWN_REFERENCE", assertion,
                        assertion.subject + " " + assertion.relation + " " + assertion.object);
                if (!profile.constraints.contains(kind)) {
                    String code = assertion.relation.equals("GROUP_BY")
                            ? "GROUP_BY_REQUIRES_PRIMARY_KEY" : "INVALID_REFERENCE_KIND";
                    throw error(code, assertion, assertion.relation + " requires "
                            + profile.constraints + " but " + assertion.object + " is " + kind);
                }
            }
        }
        for (Map.Entry<String, String> subject : authored.kinds.entrySet()) {
            Assertion declaration = authored.assertions.stream()
                    .filter(a -> a.subject.equals(subject.getKey()) && a.relation.equals("KIND")).findFirst().orElseThrow();
            for (Profile profile : profiles) {
                if (!profile.subjectKinds.contains(subject.getValue())) continue;
                int count = counts.getOrDefault(subject.getKey(), Map.of())
                        .getOrDefault(profile.relation, 0);
                if (count < profile.min || count > profile.max) throw error("SCHEMA_CARDINALITY", declaration,
                        subject.getValue() + " " + subject.getKey() + " requires " + profile.min + ".."
                                + (profile.max == Integer.MAX_VALUE ? "*" : profile.max) + " " + profile.relation);
            }
        }
    }

    private static void validateGroups(Model combined, SpecCatalog spec) {
        Index ix = new Index(combined);
        Map<PropertyScope, String> propertyTypes = new HashMap<>();
        for (String group : ix.subjects("GROUP")) {
            Assertion fromAssertion = ix.oneAssertion(group, "FROM");
            String fromTable = fromAssertion.object;
            JoinState state = new JoinState();
            state.tables.add(fromTable);
            state.rowKeys.add(spec.primaryKeyByTable.get(fromTable));

            for (Assertion joinAssertion : ix.assertions(group, "JOIN")) {
                String fk = joinAssertion.object;
                String from = ix.one(fk, "FROM_TABLE");
                String to = ix.one(fk, "TO_TABLE");
                String cardinality = ix.one(fk, "CARDINALITY");
                boolean hasFrom = state.tables.contains(from);
                boolean hasTo = state.tables.contains(to);
                if (hasFrom && hasTo) throw error("AMBIGUOUS_TABLE_ROLE", joinAssertion,
                        fk + " revisits tables already present in " + group
                                + "; aliases and role-playing joins are not yet modeled");
                if (!hasFrom && !hasTo) throw error("DISCONNECTED_JOIN", joinAssertion,
                        fk + " does not connect to the established row space of " + group);

                String fromKey = spec.primaryKeyByTable.get(from);
                String toKey = spec.primaryKeyByTable.get(to);
                state.addDependency(fromKey, toKey);
                if (cardinality.equals("#ONE_TO_ONE")) state.addDependency(toKey, fromKey);

                if (hasFrom) {
                    state.tables.add(to); // Forward many-to-one/one-to-one preserves row identity.
                } else {
                    state.tables.add(from);
                    if (cardinality.equals("#MANY_TO_ONE")) {
                        // Reverse traversal is one-to-many and introduces the many-side identity.
                        state.rowKeys.add(fromKey);
                    }
                }
            }

            Set<String> availableColumns = new HashSet<>();
            for (String column : ix.subjects("COLUMN")) {
                if (state.tables.contains(ix.one(column, "TABLE"))) availableColumns.add(column);
            }
            for (Assertion where : ix.assertions(group, "WHERE")) {
                requireAvailable(availableColumns, group, where);
                if (!ix.one(where.object, "VALUE_TYPE").equals("#BOOLEAN")) {
                    throw error("WHERE_NOT_BOOLEAN", where, where.object + " is not Boolean");
                }
            }

            Set<String> groupKeys = new LinkedHashSet<>();
            for (Assertion groupBy : ix.assertions(group, "GROUP_BY")) {
                if (!groupKeys.add(groupBy.object)) throw error("DUPLICATE_GROUP_KEY", groupBy,
                        groupBy.object + " appears more than once");
                if (!state.closure(state.rowKeys).contains(groupBy.object)) {
                    throw error("GROUP_KEY_NOT_DETERMINED", groupBy,
                            groupBy.object + " is not functionally determined by joined row identity " + state.rowKeys);
                }
            }

            List<String> properties = ix.subjects("PROPERTY").stream()
                    .filter(property -> ix.one(property, "AT").equals(group)).toList();
            if (properties.isEmpty()) throw error("GROUP_PUBLISHES_NOTHING", ix.kindAssertion(group),
                    group + " has no PROPERTY AT this group");
            PropertyChecker checker = new PropertyChecker(ix, group, availableColumns, groupKeys,
                    state, spec.primaryKeyByTable, propertyTypes);
            for (String property : properties) checker.typeOf(property);

            for (Assertion having : ix.assertions(group, "HAVING")) {
                if (!ix.one(having.object, "AT").equals(group)) throw error("PROPERTY_GRAIN_MISMATCH", having,
                        having.object + " is not AT " + group);
                if (!checker.typeOf(having.object).equals("#BOOLEAN")) throw error("HAVING_NOT_BOOLEAN", having,
                        having.object + " is not Boolean");
            }
            Set<String> sortable = new HashSet<>(properties);
            for (String key : groupKeys) sortable.addAll(ix.values(key, "KEY_PART"));
            for (Assertion order : ix.assertions(group, "ORDER_BY")) {
                if (!sortable.contains(order.object)) throw error("INVALID_ORDER_BY", order,
                        order.object + " is not grouped or published by " + group);
            }
        }
    }

    private static final class JoinState {
        final Set<String> tables = new LinkedHashSet<>();
        final Set<String> rowKeys = new LinkedHashSet<>();
        final Map<String, Set<String>> dependencies = new HashMap<>();
        void addDependency(String determinant, String dependent) {
            dependencies.computeIfAbsent(determinant, ignored -> new LinkedHashSet<>()).add(dependent);
        }

        Set<String> closure(Set<String> determinants) {
            Set<String> closure = new LinkedHashSet<>(determinants);
            boolean changed;
            do {
                changed = false;
                for (Map.Entry<String, Set<String>> dependency : dependencies.entrySet()) {
                    if (closure.contains(dependency.getKey())) changed |= closure.addAll(dependency.getValue());
                }
            } while (changed);
            return closure;
        }
    }

    private static final class PropertyChecker {
        final Index ix;
        final String group;
        final Set<String> columns;
        final Set<String> groupKeys;
        final JoinState state;
        final Map<String, String> primaryKeyByTable;
        final Map<PropertyScope, String> cache;

        PropertyChecker(Index ix, String group, Set<String> columns, Set<String> groupKeys,
                        JoinState state, Map<String, String> primaryKeyByTable,
                        Map<PropertyScope, String> cache) {
            this.ix = ix;
            this.group = group;
            this.columns = columns;
            this.groupKeys = groupKeys;
            this.state = state;
            this.primaryKeyByTable = primaryKeyByTable;
            this.cache = cache;
        }

        String typeOf(String property) {
            PropertyScope scope = new PropertyScope(group, property);
            if (cache.containsKey(scope)) return cache.get(scope);
            Assertion at = ix.oneAssertion(property, "AT");
            if (!at.object.equals(group)) throw error("PROPERTY_GRAIN_MISMATCH", at,
                    property + " is not AT " + group);
            Assertion as = ix.oneAssertion(property, "AS");
            String expression = as.object;
            String result;
            Matcher aggregate = AGGREGATE.matcher(expression);
            if (aggregate.matches()) {
                String operation = aggregate.group(1);
                String input = upper(aggregate.group(2));
                requireColumn(input, as);
                String sourceTable = ix.one(input, "TABLE");
                String sourceKey = primaryKeyByTable.get(sourceTable);
                Set<String> determinant = new LinkedHashSet<>(groupKeys);
                determinant.add(sourceKey);
                Set<String> closure = state.closure(determinant);
                if (!closure.containsAll(state.rowKeys)) {
                    Set<String> uncontrolled = new LinkedHashSet<>(state.rowKeys);
                    uncontrolled.removeAll(closure);
                    throw error("AGGREGATE_FANOUT", as, property + " aggregates " + input
                            + " after join expansion by " + uncontrolled
                            + "; the same source fact may occur more than once per output group");
                }
                String type = ix.one(input, "VALUE_TYPE");
                result = switch (operation) {
                    case "SUM", "AVG" -> {
                        requireType(property, operation, type, "#NUMBER", as);
                        yield "#NUMBER";
                    }
                    case "COUNT" -> "#NUMBER";
                    case "COUNT_TRUE" -> {
                        requireType(property, operation, type, "#BOOLEAN", as);
                        yield "#NUMBER";
                    }
                    case "MIN", "MAX" -> {
                        if (!ORDERED_TYPES.contains(type)) throw error("OPERATION_INPUT_TYPE", as,
                                property + " " + operation + " expects Ordered " + ORDERED_TYPES
                                        + " but received " + type);
                        yield type;
                    }
                    default -> throw error("UNKNOWN_OPERATION", as, operation);
                };
            } else if (QUALIFIED.matcher(expression).matches()) {
                requireColumn(expression, as);
                String table = ix.one(expression, "TABLE");
                String sourceKey = primaryKeyByTable.get(table);
                if (!state.closure(groupKeys).contains(sourceKey)) {
                    throw error("UNGROUPED_INPUT", as, property + " uses " + expression
                            + " but GROUP_BY keys do not functionally determine " + sourceKey);
                }
                result = ix.one(expression, "VALUE_TYPE");
            } else {
                throw error("INVALID_PROPERTY_EXPRESSION", as,
                        property + " AS accepts Qualified.Column or AGGREGATE(Qualified.Column)");
            }
            cache.put(scope, result);
            return result;
        }

        void requireColumn(String input, Assertion context) {
            if (!columns.contains(input)) throw error("COLUMN_NOT_AVAILABLE", context,
                    input + " is not available to " + group);
        }

        void requireType(String property, String operation, String actual, String expected,
                         Assertion context) {
            if (!actual.equals(expected)) throw error("OPERATION_INPUT_TYPE", context,
                    property + " " + operation + " expects " + expected + " but received " + actual);
        }
    }

    private static final class Index {
        final Model model;
        final Map<String, Map<String, List<Assertion>>> assertions = new HashMap<>();

        Index(Model model) {
            this.model = model;
            for (Assertion assertion : model.assertions) {
                assertions.computeIfAbsent(assertion.subject, ignored -> new HashMap<>())
                        .computeIfAbsent(assertion.relation, ignored -> new ArrayList<>()).add(assertion);
            }
        }

        Set<String> subjects(String kind) {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Assertion assertion : model.assertions) {
                if (assertion.relation.equals("KIND") && assertion.object.equals(kind)) result.add(assertion.subject);
            }
            return result;
        }

        List<Assertion> assertions(String subject, String relation) {
            return assertions.getOrDefault(subject, Map.of()).getOrDefault(relation, List.of());
        }

        List<String> values(String subject, String relation) {
            return assertions(subject, relation).stream().map(Assertion::object).toList();
        }

        Assertion oneAssertion(String subject, String relation) {
            List<Assertion> values = assertions(subject, relation);
            if (values.size() != 1) throw new ModelError("INTERNAL_CARDINALITY",
                    subject + " expected exactly one " + relation);
            return values.get(0);
        }

        Assertion kindAssertion(String subject) { return oneAssertion(subject, "KIND"); }
        String one(String subject, String relation) { return oneAssertion(subject, relation).object; }
    }

    private static Assertion singleAssertion(Model model, String kind, String relation) {
        List<Assertion> values = model.assertions.stream()
                .filter(a -> model.kinds.get(a.subject).equals(kind) && a.relation.equals(relation)).toList();
        if (values.size() != 1) throw new ModelError("SPEC_FILE_REQUIRED",
                "PACKAGE requires exactly one SPEC_FILE");
        return values.get(0);
    }

    private static String render(List<Assertion> assertions) {
        StringBuilder out = new StringBuilder("PACKAGE_ID\tORDINAL\tSUBJECT\tRELATION\tOBJECT\n");
        for (Assertion assertion : assertions) {
            out.append(assertion.packageId).append('\t').append(assertion.ordinal).append('\t')
                    .append(assertion.subject).append('\t').append(assertion.relation).append('\t')
                    .append(assertion.object).append('\n');
        }
        return out.toString();
    }

    /** Emits deterministic PostgreSQL-compatible SQL from an already validated model. */
    private static String emitPostgresSql(Compilation compilation) {
        Index ix = new Index(compilation.combined);
        StringBuilder sql = new StringBuilder("-- Generated PostgreSQL SQL for ")
                .append(ix.subjects("PACKAGE").iterator().next()).append('\n');
        for (String group : ix.subjects("GROUP")) {
            List<String> groupColumns = new ArrayList<>();
            List<String> select = new ArrayList<>();
            for (String key : ix.values(group, "GROUP_BY")) {
                List<String> parts = ix.values(key, "KEY_PART");
                for (String part : parts) {
                    groupColumns.add(part);
                    String alias = parts.size() == 1 ? key : key + "_" + part.substring(part.indexOf('.') + 1);
                    select.add(part + " AS " + alias);
                }
            }

            Map<String, String> propertyExpressions = new LinkedHashMap<>();
            for (String property : ix.subjects("PROPERTY")) {
                if (!ix.one(property, "AT").equals(group)) continue;
                String expression = ix.one(property, "AS");
                propertyExpressions.put(property, expression);
                select.add(expression + " AS " + property);
            }

            sql.append('\n').append("-- ").append(group).append('\n')
                    .append("SELECT\n    ").append(String.join(",\n    ", select)).append('\n')
                    .append("FROM ").append(ix.one(group, "FROM")).append('\n');
            Set<String> tables = new LinkedHashSet<>();
            tables.add(ix.one(group, "FROM"));
            for (Assertion join : ix.assertions(group, "JOIN")) {
                String foreignKey = join.object;
                String from = ix.one(foreignKey, "FROM_TABLE");
                String to = ix.one(foreignKey, "TO_TABLE");
                String introduced = tables.contains(from) ? to : from;
                List<String> fromColumns = ix.values(foreignKey, "FROM_COLUMN");
                List<String> toColumns = ix.values(foreignKey, "TO_COLUMN");
                List<String> predicates = new ArrayList<>();
                for (int i = 0; i < fromColumns.size(); i++) {
                    predicates.add(fromColumns.get(i) + " = " + toColumns.get(i));
                }
                sql.append("JOIN ").append(introduced).append(" ON ")
                        .append(String.join(" AND ", predicates)).append('\n');
                tables.add(introduced);
            }
            List<String> where = ix.values(group, "WHERE");
            if (!where.isEmpty()) sql.append("WHERE ").append(String.join(" AND ", where)).append('\n');
            sql.append("GROUP BY ").append(String.join(", ", groupColumns)).append('\n');
            List<String> having = new ArrayList<>();
            for (String property : ix.values(group, "HAVING")) having.add(propertyExpressions.get(property));
            if (!having.isEmpty()) sql.append("HAVING ").append(String.join(" AND ", having)).append('\n');
            List<String> order = new ArrayList<>();
            for (String value : ix.values(group, "ORDER_BY")) {
                order.add(propertyExpressions.getOrDefault(value, value));
            }
            if (!order.isEmpty()) sql.append("ORDER BY ").append(String.join(", ", order)).append('\n');
            sql.append(";\n");
        }
        return sql.toString();
    }

    private static void export(Path root, Path source, Path output, Compilation compilation)
            throws IOException {
        LinkedHashMap<String, byte[]> originals = new LinkedHashMap<>();
        originals.put("ORIGINAL/" + upper(source.getFileName().toString()), Files.readAllBytes(source));
        originals.put("ORIGINAL/" + upper(compilation.specPath.getFileName().toString()),
                Files.readAllBytes(compilation.specPath));
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("MANIFEST.TXT", ("MILESTONE 2004\nLANGUAGE_VERSION "
                + compilation.languageVersion + "\nMODEL "
                + upper(source.getFileName().toString()) + "\nSPEC "
                + upper(compilation.specPath.getFileName().toString()) + "\nASSERTIONS "
                + compilation.combined.assertions.size() + "\nSQL_DIALECT POSTGRESQL\n")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("ASSERTIONS.TSV", render(compilation.combined.assertions).getBytes(StandardCharsets.UTF_8));
        entries.put("SQL/POSTGRESQL.SQL", emitPostgresSql(compilation).getBytes(StandardCharsets.UTF_8));
        entries.put("SCHEMA/MODEL-SCHEMA.TSV", Files.readAllBytes(root.resolve("schema/model-schema.tsv")));
        entries.put("SCHEMA/SPEC-SCHEMA.TSV", Files.readAllBytes(root.resolve("schema/spec-schema.tsv")));
        entries.put("SOURCE/" + upper(source.getFileName().toString()), Files.readAllBytes(source));
        entries.put("SOURCE/" + upper(compilation.specPath.getFileName().toString()),
                Files.readAllBytes(compilation.specPath));
        entries.put("SOURCE/ORIGINAL-INPUT.ZIP", zip(originals));
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(output, zip(entries));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(ZIP_TIME);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Set<String> splitSet(String value, Location location) {
        if (value.equals("-") || value.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : value.split("\\|", -1)) {
            if (part.isEmpty() || !result.add(upper(part))) {
                throw error("INVALID_PROFILE_SET", location, value);
            }
        }
        return Set.copyOf(result);
    }

    private static void contiguous(String name, List<Integer> parts, Location location) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i) != i + 1) throw error("NONCONTIGUOUS_PARTS", location,
                    name + " parts must be the unique sequence 1..n");
        }
    }

    private static int positive(String value, String field, Location location) {
        int number = nonnegative(value, field, location);
        if (number < 1) throw error("INVALID_ORDINAL", location, field + " must be positive: " + value);
        return number;
    }

    private static int nonnegative(String value, String field, Location location) {
        try {
            int number = Integer.parseInt(value);
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) {
            throw error("INVALID_INTEGER", location, field + " must be a nonnegative integer: " + value);
        }
    }

    private static String identifier(String value, String role, Location location) {
        String normalized = upper(value);
        requireIdentifier(normalized, role, location);
        return normalized;
    }

    private static void requireIdentifier(String value, String field, Location location) {
        if (!IDENTIFIER.matcher(value).matches()) throw error("INVALID_IDENTIFIER", location,
                field + " must be an unquoted SQL identifier: " + value);
    }

    private static void requireAvailable(Set<String> available, String group, Assertion column) {
        if (!available.contains(column.object)) throw error("COLUMN_NOT_AVAILABLE", column,
                column.object + " is not available to " + group);
    }

    private static ModelError error(String code, Assertion assertion, String message) {
        return error(code, assertion.location, message);
    }

    private static ModelError error(String code, Location location, String message) {
        return new ModelError(code, location.prefix() + message);
    }

    private static String upper(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
