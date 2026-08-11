package compiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes the deterministic, self-contained export artifact for a validated model. */
final class ExportWriter {
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    private ExportWriter() {}

    static void write(Path root, Path source, Path output, Compiler.Compilation compilation)
            throws IOException {
        LinkedHashMap<String, byte[]> originals = new LinkedHashMap<>();
        originals.put("ORIGINAL/" + upper(source.getFileName().toString()), Files.readAllBytes(source));
        originals.put("ORIGINAL/" + upper(compilation.specPath().getFileName().toString()),
                Files.readAllBytes(compilation.specPath()));
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("MANIFEST.TXT", ("MILESTONE 2004\nLANGUAGE_VERSION "
                + compilation.languageVersion() + "\nMODEL "
                + upper(source.getFileName().toString()) + "\nSPEC "
                + upper(compilation.specPath().getFileName().toString()) + "\nASSERTIONS "
                + compilation.combined().assertions().size() + "\nSQL_DIALECT POSTGRESQL\n")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("ASSERTIONS.TSV", Compiler.render(compilation.combined().assertions())
                .getBytes(StandardCharsets.UTF_8));
        entries.put("SQL/POSTGRESQL.SQL", PostgresEmitter.emit(compilation).getBytes(StandardCharsets.UTF_8));
        entries.put("SCHEMA/MODEL-SCHEMA.TSV", Files.readAllBytes(root.resolve("schema/model-schema.tsv")));
        entries.put("SCHEMA/SPEC-SCHEMA.TSV", Files.readAllBytes(root.resolve("schema/spec-schema.tsv")));
        entries.put("SOURCE/" + upper(source.getFileName().toString()), Files.readAllBytes(source));
        entries.put("SOURCE/" + upper(compilation.specPath().getFileName().toString()),
                Files.readAllBytes(compilation.specPath()));
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

    private static String upper(String value) { return value.trim().toUpperCase(java.util.Locale.ROOT); }
}
