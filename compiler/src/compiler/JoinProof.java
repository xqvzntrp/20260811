package compiler;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks the row identity and functional dependencies introduced by a sequence
 * of declared joins. This is the semantic kernel used to prove grouping and
 * aggregate safety before SQL is emitted.
 */
final class JoinProof {
    private final Set<String> tables = new LinkedHashSet<>();
    private final Set<String> rowKeys = new LinkedHashSet<>();
    private final Map<String, Set<String>> dependencies = new LinkedHashMap<>();

    JoinProof(String initialTable, String initialPrimaryKey) {
        tables.add(initialTable);
        rowKeys.add(initialPrimaryKey);
    }

    boolean hasTable(String table) { return tables.contains(table); }

    boolean revisits(String fromTable, String toTable) {
        return hasTable(fromTable) && hasTable(toTable);
    }

    boolean isDisconnected(String fromTable, String toTable) {
        return !hasTable(fromTable) && !hasTable(toTable);
    }

    /** Applies a validated many-to-one or one-to-one foreign-key traversal. */
    void join(String fromTable, String fromPrimaryKey, String toTable, String toPrimaryKey,
              String cardinality) {
        boolean hasFrom = hasTable(fromTable);
        addDependency(fromPrimaryKey, toPrimaryKey);
        if (cardinality.equals("#ONE_TO_ONE")) addDependency(toPrimaryKey, fromPrimaryKey);
        if (hasFrom) {
            tables.add(toTable);
        } else {
            tables.add(fromTable);
            if (cardinality.equals("#MANY_TO_ONE")) rowKeys.add(fromPrimaryKey);
        }
    }

    Set<String> effectiveRowKeys() { return Set.copyOf(rowKeys); }

    Set<String> closure(Set<String> determinants) {
        Set<String> closure = new LinkedHashSet<>(determinants);
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<String, Set<String>> dependency : dependencies.entrySet()) {
                if (closure.contains(dependency.getKey())) changed |= closure.addAll(dependency.getValue());
            }
        } while (changed);
        return Set.copyOf(closure);
    }

    boolean canGroupBy(String key) { return closure(rowKeys).contains(key); }

    boolean aggregateIsSafe(Set<String> groupKeys, String inputPrimaryKey) {
        Set<String> determinant = new LinkedHashSet<>(groupKeys);
        determinant.add(inputPrimaryKey);
        return closure(determinant).containsAll(rowKeys);
    }

    Set<String> uncontrolledKeys(Set<String> groupKeys, String inputPrimaryKey) {
        Set<String> determinant = new LinkedHashSet<>(groupKeys);
        determinant.add(inputPrimaryKey);
        Set<String> uncontrolled = new LinkedHashSet<>(rowKeys);
        uncontrolled.removeAll(closure(determinant));
        return Set.copyOf(uncontrolled);
    }

    private void addDependency(String determinant, String dependent) {
        dependencies.computeIfAbsent(determinant, ignored -> new LinkedHashSet<>()).add(dependent);
    }
}
