package compiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Emits deterministic PostgreSQL-compatible SQL from a semantically valid model. */
final class PostgresEmitter {
    private PostgresEmitter() {}

    static String emit(Compiler.Compilation compilation) {
        Compiler.Index ix = new Compiler.Index(compilation.combined());
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
            Map<String, String> properties = new LinkedHashMap<>();
            for (String property : ix.subjects("PROPERTY")) {
                if (!ix.one(property, "AT").equals(group)) continue;
                String expression = ix.one(property, "AS");
                properties.put(property, expression);
                select.add(expression + " AS " + property);
            }
            sql.append('\n').append("-- ").append(group).append('\n')
                    .append("SELECT\n    ").append(String.join(",\n    ", select)).append('\n')
                    .append("FROM ").append(ix.one(group, "FROM")).append('\n');
            Set<String> tables = new LinkedHashSet<>();
            tables.add(ix.one(group, "FROM"));
            for (Compiler.Assertion join : ix.assertions(group, "JOIN")) {
                String foreignKey = join.object();
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
            for (String property : ix.values(group, "HAVING")) having.add(properties.get(property));
            if (!having.isEmpty()) sql.append("HAVING ").append(String.join(" AND ", having)).append('\n');
            List<String> order = new ArrayList<>();
            for (String value : ix.values(group, "ORDER_BY")) order.add(properties.getOrDefault(value, value));
            if (!order.isEmpty()) sql.append("ORDER BY ").append(String.join(", ", order)).append('\n');
            sql.append(";\n");
        }
        return sql.toString();
    }
}
