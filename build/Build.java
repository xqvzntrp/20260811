import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

public class Build {
    record Run(int exit, String output) {}

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Path root = Path.of("").toAbsolutePath().normalize();
        require(Files.isRegularFile(root.resolve("VERSION")), "Run from the repository root");
        Path out = root.resolve("out");
        Path dist = root.resolve("dist");
        deleteTree(out);
        deleteTree(dist);
        Files.createDirectories(out);
        Files.createDirectories(dist);

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        require(javac != null, "A JDK is required");
        List<String> sources = Files.walk(root.resolve("compiler/src"))
                .filter(p -> p.toString().endsWith(".java"))
                .map(Path::toString).toList();
        List<String> compileArgs = new ArrayList<>(List.of("--release", "17", "-d", out.toString()));
        compileArgs.addAll(sources);
        require(javac.run(null, System.out, System.err, compileArgs.toArray(String[]::new)) == 0,
                "Compilation failed");

        boolean explainManifest = args.length == 3 && args[0].equals("--manifest") && args[2].equals("--explain");
        if ((args.length == 2 && args[0].equals("--manifest")) || explainManifest) {
            runManifest(root, Path.of(args[1]), explainManifest);
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Usage: java TestRunner.java [--manifest PACKAGE_MANIFEST [--explain]]");
        }

        // Architecture: the join/grain proof is the compiler's semantic kernel.
        Run joinProof = runMain(root, "compiler.JoinProofTest");
        require(joinProof.exit == 0 && joinProof.output.equals("JoinProofTest passed\n"),
                "Join proof kernel failed:\n" + joinProof.output);

        // Contract: executable schemas and their negative cases.
        int conformance = 0;
        Run validProfile = run(root, Map.of(), "validate_profile", "schema/model-schema.tsv");
        require(validProfile.exit == 0 && validProfile.output.startsWith("VALID "),
                "The canonical relation profile should be valid:\n" + validProfile.output);
        conformance++;
        Run malformedProfile = run(root, Map.of(), "validate_profile",
                "conformance/MALFORMED-MODEL-SCHEMA.TSV");
        require(malformedProfile.exit == 2
                        && malformedProfile.output.contains("INVALID DUPLICATE_RELATION_PROFILE ")
                        && malformedProfile.output.toUpperCase().matches("(?s).*\\.TSV:[0-9]+:.*"),
                "Malformed profile should fail with a source location:\n" + malformedProfile.output);
        conformance++;
        Run validSpecSchema = run(root, Map.of(), "validate_spec_schema",
                "schema/spec-schema.tsv");
        require(validSpecSchema.exit == 0 && validSpecSchema.output.startsWith("VALID 12 "),
                "The executable specification schema should be valid:\n" + validSpecSchema.output);
        conformance++;
        Run malformedSpecSchema = run(root, Map.of(), "validate_spec_schema",
                "conformance/MALFORMED-SPEC-SCHEMA.TSV");
        require(malformedSpecSchema.exit == 2
                        && malformedSpecSchema.output.contains("INVALID DUPLICATE_SPEC_FIELD ")
                        && malformedSpecSchema.output.toUpperCase().matches("(?s).*\\.TSV:[0-9]+:.*"),
                "Malformed specification schema should fail with a source location:\n"
                        + malformedSpecSchema.output);
        conformance++;
        Run driftedSpecSchema = run(root, Map.of(), "validate_spec_schema",
                "conformance/DRIFTED-SPEC-SCHEMA.TSV");
        require(driftedSpecSchema.exit == 2
                        && driftedSpecSchema.output.contains("INVALID SPEC_SCHEMA_CORE_FIELDS "),
                "Schema drift should be rejected:\n" + driftedSpecSchema.output);
        conformance++;
        Run canonicalSpec = run(root, Map.of(), "validate_spec", "spec/COMMERCE.SPEC.TSV",
                "schema/spec-schema.tsv");
        require(canonicalSpec.exit == 0 && canonicalSpec.output.startsWith("VALID "),
                "Canonical specification should validate through its executable schema:\n"
                        + canonicalSpec.output);
        conformance++;
        Run reorderedSpec = run(root, Map.of(), "validate_spec",
                "conformance/REORDERED.SPEC.TSV", "conformance/REORDERED-SPEC-SCHEMA.TSV");
        require(reorderedSpec.exit == 0 && reorderedSpec.output.startsWith("VALID "),
                "Parser should follow schema-declared field order:\n" + reorderedSpec.output);
        conformance++;
        List<String> manifest = Files.readAllLines(root.resolve("conformance/manifest.tsv"), StandardCharsets.UTF_8);
        for (int i = 1; i < manifest.size(); i++) {
            if (manifest.get(i).isBlank()) continue;
            String[] row = manifest.get(i).split("\\t");
            Run run = run(root, Map.of(), "validate", "conformance/" + row[0]);
            if (row[1].equals("VALID")) {
                require(run.exit == 0 && run.output.startsWith("VALID "), row[0] + " should be valid:\n" + run.output);
            } else {
                require(run.exit == 2 && run.output.contains("INVALID " + row[2] + " "),
                        row[0] + " should fail with " + row[2] + ":\n" + run.output);
                require(run.output.toUpperCase().matches("(?s).*\\.(MODEL|TSV):[0-9]+:.*"),
                        row[0] + " should report a source location:\n" + run.output);
            }
            conformance++;
        }

        // Contract: the complete compiler pipeline, SQL emission, and export.
        Run valid = run(root, Map.of(), "validate", "examples/customer-revenue.model");
        require(valid.exit == 0, valid.output);
        Run first = run(root, Map.of(), "compile", "examples/customer-revenue.model");
        Run second = run(root, Map.of(), "compile", "examples/customer-revenue.model");
        require(first.exit == 0 && second.exit == 0, "Compilation command failed");
        require(first.output.equals(second.output), "Canonical compilation is not deterministic");

        Run sql = run(root, Map.of(), "emit_sql", "examples/customer-revenue.model");
        String expectedSql = """
                -- Generated PostgreSQL SQL for CUSTOMERREVENUE

                -- TRANSACTIONFACTS
                SELECT
                    TRANSACTION.TRANSACTIONID AS TRANSACTIONKEY,
                    TRANSACTION.NETSALES AS PUBLISHEDNETSALES
                FROM TRANSACTION
                GROUP BY TRANSACTION.TRANSACTIONID
                ;

                -- REVENUEBYCUSTOMER
                SELECT
                    CUSTOMER.CUSTOMER_ID AS CUSTOMERKEY,
                    SUM(TRANSACTION.NETSALES) AS TOTALNETSALES,
                    COUNT(TRANSACTION.TRANSACTIONID) AS QUALIFYINGTRANSACTIONCOUNT
                FROM TRANSACTION
                JOIN CUSTOMER ON TRANSACTION.TRANSACTIONCUSTOMERID = CUSTOMER.CUSTOMER_ID
                WHERE TRANSACTION.ISQUALIFYINGSALE
                GROUP BY CUSTOMER.CUSTOMER_ID
                ORDER BY SUM(TRANSACTION.NETSALES)
                ;
                """;
        require(sql.exit == 0 && sql.output.equals(expectedSql),
                "PostgreSQL SQL generation is not deterministic or has unexpected output:\n" + sql.output);
        Run explanation = run(root, Map.of(), "explain", "examples/customer-revenue.model");
        require(explanation.exit == 0 && explanation.output.contains("06 PROVE GRAIN")
                        && explanation.output.contains("Published 60 canonical assertions."),
                "Compiler explanation does not describe the semantic pipeline:\n" + explanation.output);
        Run unsafeSql = run(root, Map.of(), "emit_sql",
                "package/fanout-safe-revenue/unsafe-revenue-with-tickets.model");
        require(unsafeSql.exit == 2 && unsafeSql.output.contains("INVALID AGGREGATE_FANOUT "),
                "Unsafe aggregates must be rejected before SQL generation:\n" + unsafeSql.output);

        Path utc = dist.resolve("customer-revenue.utc.zip");
        Path tokyo = dist.resolve("customer-revenue.tokyo.zip");
        Path repeated = dist.resolve("customer-revenue.repeated.zip");
        require(run(root, Map.of("TZ", "UTC"), "export", "examples/customer-revenue.model", utc.toString()).exit == 0,
                "UTC export failed");
        require(run(root, Map.of("TZ", "Asia/Tokyo"), "export", "examples/customer-revenue.model", tokyo.toString()).exit == 0,
                "Tokyo export failed");
        require(run(root, Map.of("TZ", "UTC"), "export", "examples/customer-revenue.model", repeated.toString()).exit == 0,
                "Repeated UTC export failed");
        require(Arrays.equals(Files.readAllBytes(utc), Files.readAllBytes(tokyo)),
                "Export ZIP bytes differ across time zones");
        require(Arrays.equals(Files.readAllBytes(utc), Files.readAllBytes(repeated)),
                "Repeated export ZIP bytes differ in the same environment");
        try (ZipFile export = new ZipFile(utc.toFile())) {
            ZipEntry sqlEntry = export.getEntry("SQL/POSTGRESQL.SQL");
            require(sqlEntry != null, "Export ZIP is missing generated PostgreSQL SQL");
            String exportedSql = new String(export.getInputStream(sqlEntry).readAllBytes(), StandardCharsets.UTF_8);
            require(exportedSql.equals(expectedSql), "Export ZIP SQL does not match emit_sql output");
        }
        Path canonical = dist.resolve("customer-revenue.export.zip");
        Files.copy(utc, canonical, StandardCopyOption.REPLACE_EXISTING);
        Path project = dist.resolve("milestone-2004.zip");
        packageProject(root, project);

        System.out.println("Clean build passed");
        System.out.println("Architecture checks passed: parser, join proof, SQL emitter, export writer");
        System.out.println(conformance + " conformance cases passed");
        System.out.println(valid.output.trim());
        System.out.println("Deterministic cross-time-zone export: " + canonical);
        System.out.println("Project package: " + project);
    }

    static Run run(Path root, Map<String, String> environment, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(root.resolve("out").toString());
        command.add("compiler.Compiler");
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        pb.environment().putAll(environment);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), output);
    }

    static Run runMain(Path root, String mainClass, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(root.resolve("out").toString());
        command.add(mainClass);
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), output);
    }

    static void runManifest(Path root, Path manifest, boolean explain) throws Exception {
        Path absoluteManifest = manifest.toAbsolutePath().normalize();
        require(Files.isRegularFile(absoluteManifest), "Manifest not found: " + manifest);
        List<String> rows = Files.readAllLines(absoluteManifest, StandardCharsets.UTF_8);
        require(!rows.isEmpty() && rows.get(0).equals("MODEL\tEXPECTED\tCODE"),
                "Manifest header must be MODEL<TAB>EXPECTED<TAB>CODE");
        Path packageRoot = absoluteManifest.getParent();
        String packageName = packageRoot.getFileName().toString();
        Path packageOutput = root.resolve("output").resolve(packageName);
        deleteTree(packageOutput);
        copyDirectory(packageRoot, packageOutput);
        int cases = 0;
        List<String> validatedModels = new ArrayList<>();
        List<String> validModels = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).isBlank()) continue;
            String[] row = rows.get(i).split("\\t", -1);
            require(row.length == 3, "Malformed manifest row " + (i + 1));
            Path model = packageRoot.resolve(row[0]).normalize();
            require(model.startsWith(packageRoot) && Files.isRegularFile(model),
                    "Model is outside the package or missing: " + row[0]);
            Run result = run(root, Map.of(), "validate", model.toString());
            boolean valid = row[1].equals("VALID") && result.exit == 0 && result.output.startsWith("VALID ");
            boolean invalid = row[1].equals("INVALID") && result.exit == 2
                    && result.output.contains("INVALID " + row[2] + " ");
            require(valid || invalid, "Manifest case failed: " + row[0] + "\n" + result.output);
            if (valid) {
                Run compilation = run(root, Map.of(), "compile", model.toString());
                require(compilation.exit == 0, "Could not compile " + row[0] + "\n" + compilation.output);
                String modelName = model.getFileName().toString().replaceFirst("\\.model$", "");
                Files.writeString(packageOutput.resolve(modelName + ".assertions.tsv"), compilation.output,
                        StandardCharsets.UTF_8);
                Run sql = run(root, Map.of(), "emit_sql", model.toString());
                require(sql.exit == 0, "Could not generate SQL for " + row[0] + "\n" + sql.output);
                Files.writeString(packageOutput.resolve(modelName + ".postgresql.sql"), sql.output,
                        StandardCharsets.UTF_8);
                Run export = run(root, Map.of(), "export", model.toString(),
                        packageOutput.resolve(modelName + ".export.zip").toString());
                require(export.exit == 0, "Could not export " + row[0] + "\n" + export.output);
                validModels.add(row[0]);
                if (explain) {
                    Run trace = run(root, Map.of(), "explain", model.toString());
                    require(trace.exit == 0, "Could not explain " + row[0] + "\n" + trace.output);
                    System.out.println("\nValidation trace: " + row[0]);
                    System.out.print(trace.output);
                }
            } else if (explain) {
                System.out.println("\nValidation stopped: " + row[0]);
                System.out.print(result.output);
            }
            cases++;
            validatedModels.add(row[0]);
        }
        require(cases > 0, "Manifest has no cases: " + manifest);
        System.out.println("Package check passed: " + cases + " of " + cases + " model"
                + (cases == 1 ? "" : "s") + " behaved as expected.");
        System.out.println("Package: " + packageRoot);
        System.out.println("Checked: " + String.join(", ", validatedModels));
        System.out.println("The model can use its local schema and specification files successfully.");
        System.out.println("Package export directory: " + packageOutput);
        for (String validModel : validModels) {
            String modelName = validModel.replaceFirst("\\.model$", "");
            System.out.println("Compiled assertions: " + packageOutput.resolve(modelName + ".assertions.tsv"));
            System.out.println("Generated PostgreSQL SQL: " + packageOutput.resolve(modelName + ".postgresql.sql"));
            System.out.println("Export ZIP: " + packageOutput.resolve(modelName + ".export.zip"));
        }
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    static void packageProject(Path root, Path output) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(root.resolve(".git")))
                    .filter(p -> !p.startsWith(root.resolve("out")))
                    .filter(p -> !p.startsWith(root.resolve("dist")))
                    .sorted(Comparator.comparing(p -> root.relativize(p).toString()))
                    .toList();
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output), StandardCharsets.UTF_8)) {
            for (Path file : files) {
                String name = "milestone-2004/" + root.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    static void copyDirectory(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path target = destination.resolve(source.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
