package compiler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Verifies declared JoinProof state, not merely a final valid/invalid outcome. */
public final class JoinProofCorpusTest {
    private JoinProofCorpusTest() {}

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of("proof/join-proof-corpus.tsv");
        List<String> rows = Files.readAllLines(corpus, StandardCharsets.UTF_8);
        require(!rows.isEmpty() && rows.get(0).startsWith("CASE\t"), "Invalid JoinProof corpus header");
        int cases = 0;
        for (int line = 1; line < rows.size(); line++) {
            if (rows.get(line).isBlank()) continue;
            String[] row = rows.get(line).split("\\t", -1);
            require(row.length == 10, "Malformed JoinProof corpus row " + (line + 1));
            JoinProof proof = new JoinProof(row[1], row[2]);
            for (String join : row[3].split(";")) {
                String[] parts = join.split(",", -1);
                require(parts.length == 5, "Malformed join in " + row[0]);
                proof.join(parts[0], parts[1], parts[2], parts[3], parts[4]);
            }
            Set<String> groupKeys = set(row[4]);
            boolean safe = proof.aggregateIsSafe(groupKeys, row[5]);
            boolean expectedSafe = row[6].equals("VALID");
            require(safe == expectedSafe, row[0] + " expected " + row[6]);
            require(proof.effectiveRowKeys().equals(set(row[7])), row[0] + " effective-row-key drift");
            Set<String> determinant = new LinkedHashSet<>(groupKeys);
            determinant.add(row[5]);
            require(proof.closure(determinant).equals(set(row[8])), row[0] + " closure drift");
            require(proof.uncontrolledKeys(groupKeys, row[5]).equals(set(row[9])),
                    row[0] + " uncontrolled-key drift");
            cases++;
        }
        System.out.println("JoinProofCorpusTest passed: " + cases + " cases");
    }

    private static Set<String> set(String value) {
        return value.equals("-") ? Set.of() : Set.copyOf(Arrays.asList(value.split("\\|", -1)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
