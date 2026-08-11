# Milestone 2004 — executable contracts and ordered operations

Milestone 2004 is a clean, dependency-free Java 17 prototype for publishing
semantic properties at explicitly governed grains.

The design has two authoring surfaces:

1. a TSV structural specification declares tables, columns, primary keys, and
   foreign keys;
2. a compact block model declares named groups and properties.

`GROUP_BY` accepts only a named primary key from the TSV specification. It
never accepts an arbitrary column or expression.

Milestone 2004 adds a compositional join proof. Each ordered `JOIN` updates:

- the effective joined row identity;
- functional dependencies between primary keys; and
- the set of identities that may multiply an input fact.

Direct properties and aggregates are checked against that proof rather than
against table-local keys alone.

Milestone 2004 closes three contract boundaries:

- `LANGUAGE_VERSION` must exactly match the repository `VERSION`;
- `MIN` and `MAX` accept only `Identifier`, `String`, `Number`, or `Date`;
- `schema/spec-schema.tsv` is loaded and enforced by the compiler rather than
  serving as documentation beside hardcoded parsing rules.

## Structural specification

[`spec/COMMERCE.SPEC.TSV`](spec/COMMERCE.SPEC.TSV) contains one row per source
column. Primary-key and foreign-key participation are explicit columns in that
same row.

```text
SPEC_ID  TABLE_NAME  COLUMN_NAME  VALUE_TYPE  NULLABLE  PRIMARY_KEY_NAME ...
COMMERCE CUSTOMER    CUSTOMER_ID  IDENTIFIER  FALSE     CUSTOMERKEY       ...
```

The exact TSV contract is executable data in
[`schema/spec-schema.tsv`](schema/spec-schema.tsv):

```text
ORDINAL  FIELD             REQUIRED_WHEN    DOMAIN
1        SPEC_ID           ALWAYS           @IDENTIFIER
7        PRIMARY_KEY_PART  PRIMARY_KEY_NAME @POSITIVE_INTEGER
```

The schema controls header order, conditional presence, and field domains.
Cross-row key and relationship rules remain coordinated semantic validation.

## Business model

```text
+ PACKAGE CustomerRevenue
    LANGUAGE_VERSION 2004.0.0
    SPEC_FILE spec/COMMERCE.SPEC.TSV

+ GROUP RevenueByCustomer
    FROM Transaction
    JOIN TransactionCustomer
    WHERE Transaction.IsQualifyingSale
    GROUP_BY CustomerKey
    ORDER_BY TotalNetSales

+ PROPERTY TotalNetSales
    AT RevenueByCustomer
    AS SUM(Transaction.NetSales)

+ PROPERTY QualifyingTransactionCount
    AT RevenueByCustomer
    AS COUNT(Transaction.TransactionId)
```

This reads as three claims:

- `CustomerKey` identifies each output group;
- `TotalNetSales` is established at that group;
- `QualifyingTransactionCount` is established at that group.

The compiler expands both input formats into one canonical relation:

```text
(package_id, ordinal, subject, relation, object)
```

Every language token, identifier, reference, and path is case-insensitive.
Canonical assertions are normalized to upper case. Upper case remains the
recommended SQL-style convention.

## Run

```sh
java TestRunner.java
```

Then:

```sh
java -cp out compiler.Compiler validate examples/customer-revenue.model
java -cp out compiler.Compiler compile examples/customer-revenue.model
java -cp out compiler.Compiler emit_sql examples/customer-revenue.model
java -cp out compiler.Compiler export examples/customer-revenue.model dist/customer-revenue.export.zip
```

`emit_sql` writes deterministic PostgreSQL-compatible SQL only after the model
passes structural and aggregate-grain validation. Give it an optional output
path to write the SQL to a file.

## Architecture

The compiler is organized around named stages: `ModelParser` turns source blocks
into assertions; the executable schemas and specification build the catalog;
`JoinProof` proves row-grain and aggregate safety; `PostgresEmitter` produces
SQL only for validated models; and `ExportWriter` creates the deterministic ZIP.
`java TestRunner.java` checks this semantic kernel before running the broader
conformance and export contracts.

`proof/join-proof-corpus.tsv` records the expected effective row keys,
functional-dependency closure, and uncontrolled keys for safe, unsafe, and
intentionally incomplete boundary cases. The compiler is sound before it is
complete: it may reject a model whose safety is not yet provable.

## Model packages

A standalone model package contains its model, structural specification, schema
contracts, version marker, and a test manifest. Run the bundled Commerce package:

```sh
java TestRunner.java --manifest package/commerce/manifest.tsv
```

Add `--explain` to show the numbered validation pipeline for each model. An
expected-invalid model reports the diagnostic where validation stopped:

```sh
java TestRunner.java --manifest package/commerce/manifest.tsv --explain
```

The manifest columns are `MODEL`, `EXPECTED`, and `CODE`. Model paths are
relative to the manifest; `CODE` is `-` for a valid model or the expected
compiler error code for an invalid one.

The runner copies the standalone package to `output/<package-name>/`. For each
valid model, it writes compiled assertions, generated PostgreSQL SQL, and an
export ZIP beside the model files.

The export includes the model, its TSV specification, both schema contracts,
canonical assertions, generated PostgreSQL SQL, and a deterministic ZIP of the
original inputs.

Additional contract checks are available directly:

```sh
java -cp out compiler.Compiler validate_profile schema/model-schema.tsv
java -cp out compiler.Compiler validate_spec_schema schema/spec-schema.tsv
java -cp out compiler.Compiler validate_spec spec/COMMERCE.SPEC.TSV schema/spec-schema.tsv
```

## A familiar query that is rejected

Suppose `CUSTOMER` has many `ORDERROW` records and many `TICKET` records:

```sql
SELECT C.CUSTOMER_ID, SUM(O.AMOUNT)
FROM CUSTOMER C
JOIN ORDERROW O ON O.CUSTOMER_ID = C.CUSTOMER_ID
JOIN TICKET T ON T.CUSTOMER_ID = C.CUSTOMER_ID
GROUP BY C.CUSTOMER_ID;
```

SQL can execute this, but the two one-to-many branches form an order-by-ticket
product. Each order amount may be summed once per ticket. Milestone 2004 rejects
the corresponding property with `AGGREGATE_FANOUT` because:

```text
CustomerKey + OrderKey
    does not functionally determine
TicketKey
```

The author must aggregate each branch independently or declare another
semantically justified transformation. Physical joinability does not prove
aggregate correctness.
