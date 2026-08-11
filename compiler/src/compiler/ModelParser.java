package compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses authored block notation into the compiler's canonical model relation. */
final class ModelParser {
    private ModelParser() {}

    static Compiler.Model parse(Path source) throws IOException {
        List<Compiler.Assertion> assertions = new ArrayList<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        Set<String> exact = new HashSet<>();
        String active = null;
        String packageId = null;
        int ordinal = 10;
        int lineNumber = 0;
        for (String raw : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            lineNumber++;
            Compiler.Location location = new Compiler.Location(source.getFileName().toString(), lineNumber);
            int comment = raw.indexOf("//");
            String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("+")) {
                String[] p = line.substring(1).trim().split("\\s+", 2);
                if (p.length != 2) throw Compiler.error("INVALID_SUBJECT_INTRODUCTION", location,
                        "Expected + KIND SUBJECT");
                String kind = Compiler.identifier(p[0], "kind", location);
                String subject = Compiler.identifier(p[1], "subject", location);
                if (kinds.putIfAbsent(subject, kind) != null) throw Compiler.error("DUPLICATE_SUBJECT", location,
                        "Subject already introduced: " + subject);
                if (packageId == null) {
                    if (!kind.equals("PACKAGE")) throw Compiler.error("PACKAGE_REQUIRED_FIRST", location,
                            "The first subject must be PACKAGE");
                    packageId = subject;
                } else if (kind.equals("PACKAGE")) {
                    throw Compiler.error("MULTIPLE_PACKAGES", location, "A model contains exactly one PACKAGE");
                }
                active = subject;
                assertions.add(new Compiler.Assertion(packageId, ordinal, subject, "KIND", kind, location));
                exact.add(subject + "\u0000KIND\u0000" + kind);
                ordinal += 10;
            } else {
                if (active == null) throw Compiler.error("ORPHAN_ASSERTION", location, "Assertion precedes a subject");
                String[] p = line.split("\\s+", 2);
                if (p.length != 2) throw Compiler.error("INVALID_ASSERTION", location,
                        "Expected RELATION OBJECT");
                String relation = Compiler.identifier(p[0], "relation", location);
                String object = Compiler.upper(p[1]);
                String key = active + "\u0000" + relation + "\u0000" + object;
                if (!exact.add(key)) throw Compiler.error("DUPLICATE_ASSERTION", location, "Duplicate assertion");
                assertions.add(new Compiler.Assertion(packageId, ordinal, active, relation, object, location));
                ordinal += 10;
            }
        }
        if (packageId == null) throw new Compiler.ModelError("PACKAGE_REQUIRED",
                source.getFileName() + ": model has no PACKAGE");
        return new Compiler.Model(List.copyOf(assertions), Map.copyOf(kinds));
    }
}
