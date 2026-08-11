# Milestone 2004 specification

## 0. Language-version boundary

The `LANGUAGE_VERSION` assertion is mandatory and semantic. Its object must
equal the normalized contents of the repository `VERSION` file.

```text
LANGUAGE_VERSION 2004.0.0
```

Any other value fails with `UNSUPPORTED_LANGUAGE_VERSION` before specification
loading or semantic validation. Export manifests use the same validated value;
there is no independent hardcoded export version.

## 1. Foundational rule

Only a declared primary key may establish a `GROUP_BY` coordinate.

```text
GROUP_BY object kind = PRIMARY_KEY
```

The grouping grain is the ordered product of the referenced complete keys:

```text
GROUP_BY CustomerKey
GROUP_BY FiscalYearKey

grain = CustomerKey × FiscalYearKey
```

A composite primary key is one indivisible identity. Authors cannot group by a
partial key through this language.

## 2. Executable TSV structural specification

`schema/spec-schema.tsv` has the machine-readable form:

```text
ORDINAL
FIELD
REQUIRED_WHEN
DOMAIN
```

`REQUIRED_WHEN` is `ALWAYS`, `OPTIONAL`, or the name of an earlier controlling
field. A controlled field is present exactly when its controller is present.

Domains are either enumerations or one of these schema terminals:

```text
@IDENTIFIER
@POSITIVE_INTEGER
```

The compiler loads this schema before reading a structural specification. It
derives the required header order, field presence, identifier checks, ordinal
checks, and enumerated domains from the schema rows. Reordering schema fields
and applying the corresponding specification header is supported and tested.

The schema must declare exactly the twelve core fields needed by coordinated
key and relationship semantics. Missing, duplicate, noncontiguous, or otherwise
malformed schema declarations are rejected.

In a conforming structural specification, one row declares one column.
Optional key fields associate that column with a primary key or foreign key.

The compiler validates:

- a single case-insensitive `SPEC_ID`;
- unique `(TABLE_NAME, COLUMN_NAME)` identities;
- exactly one primary key per table;
- contiguous `1..n` primary-key parts;
- non-nullable primary-key columns;
- contiguous `1..n` foreign-key parts;
- type-compatible foreign-key correspondences;
- foreign keys targeting the complete primary key of the referenced table;
- consistent endpoint and cardinality metadata across composite foreign keys.
- foreign-key direction declared only as `MANY_TO_ONE` or `ONE_TO_ONE`;
- `ONE_TO_ONE` source columns equal to the complete source primary key.

TSV rows are deterministically transcribed into canonical assertions. The TSV
is source notation, not a second semantic carrier.

## 3. Block model

`+ KIND SUBJECT` introduces a subject. Indented `RELATION OBJECT` lines assert
facts about the active subject. `//` starts a comment.

The entire project is case-insensitive. Canonical normalization uses
`Locale.ROOT` upper case. Case-only duplicates are errors, including file-path
components.

The model contains only three author-facing subject kinds:

```text
PACKAGE
GROUP
PROPERTY
```

### PACKAGE

```text
+ PACKAGE CustomerRevenue
    LANGUAGE_VERSION 2004.0.0
    SPEC_FILE spec/COMMERCE.SPEC.TSV
```

`SPEC_FILE` is mandatory and is resolved case-insensitively within the project.

### GROUP

```text
+ GROUP RevenueByCustomer
    FROM Transaction
    JOIN TransactionCustomer
    WHERE Transaction.IsQualifyingSale
    GROUP_BY CustomerKey
```

SQL-derived phase order is:

```text
FROM -> JOIN -> WHERE -> GROUP_BY -> aggregate -> HAVING -> ORDER_BY
```

`FROM` establishes the initial row population. `JOIN` extends it through a
declared foreign key. `WHERE` accepts an available Boolean column and restricts
input rows before grouping.

`GROUP_BY` partitions the remaining rows by equality of the complete key
values. It establishes output identity and does not imply ordering. Each key's
table must be reachable through `FROM` and `JOIN`.

`HAVING` accepts a Boolean property at the same group. `ORDER_BY` affects only
presentation and accepts a grouping-key column or a property at the group.

### PROPERTY

```text
+ PROPERTY TotalNetSales
    AT RevenueByCustomer
    AS SUM(Transaction.NetSales)
```

`AT` names the property's complete output grain by referencing a `GROUP`.
`AS` gives the value definition. Source columns are always table-qualified.

Milestone 2004 accepts:

```text
Table.Column
SUM(Table.Column)
AVG(Table.Column)
COUNT(Table.Column)
COUNT_TRUE(Table.Column)
MIN(Table.Column)
MAX(Table.Column)
```

A direct `Table.Column` is legal only when functional-dependency closure from
the group's keys contains that table's complete primary key. This dependency
may be established through the chosen directional joins.

Aggregate signatures are:

```text
SUM        Number  -> Number
AVG        Number  -> Number
COUNT      Any     -> Number
COUNT_TRUE Boolean -> Number
MIN        Ordered -> Same
MAX        Ordered -> Same
```

`Ordered` is an explicit closed constraint:

```text
Ordered = Identifier | String | Number | Date
```

Boolean is not ordered. Therefore `MIN(Boolean)` and `MAX(Boolean)` fail with
`OPERATION_INPUT_TYPE`. Both operations preserve the accepted input type.

## 4. Directional join proof

A foreign key is declared from its referencing table to one referenced row:

```text
OrderRow.Customer_Id -> Customer.Customer_Id
cardinality = MANY_TO_ONE
```

Traversal is interpreted relative to the table already present:

```text
OrderRow -> Customer
    forward MANY_TO_ONE
    row identity preserved
    OrderKey functionally determines CustomerKey

Customer -> OrderRow
    reverse ONE_TO_MANY
    row identity expands by OrderKey
```

`ONE_TO_ONE` establishes functional determination in both directions and does
not expand row identity. A join is rejected when neither endpoint is already
present. Revisiting a table is rejected until aliases and role-playing joins
have their own explicit semantics.

The compiler carries:

```text
JoinState =
    available tables
    effective row keys
    directed key dependencies
```

`GROUP_BY` key `G` is valid only when:

```text
G in closure(effective row keys)
```

For a direct input at natural key `N`:

```text
N in closure(group keys)
```

For an aggregate input at natural key `N`, every final row-identity coordinate
must be controlled by the group and input identity together:

```text
effective row keys subset-of closure(group keys union {N})
```

Failure of the last judgment is `AGGREGATE_FANOUT`. It means one occurrence of
the source fact may appear multiple times inside one output group.

## 5. Source-aware diagnostics

Every authored assertion retains its source filename and line number through
schema and semantic validation. Synthesized specification assertions retain
the originating TSV row. Diagnostics therefore have the stable form:

```text
INVALID AGGREGATE_FANOUT invalid-branch-fanout.model:11: ...
```

Source locations are diagnostic metadata; they do not add fields to the
canonical semantic relation.

## 6. Canonical output

Both sources compile into:

```text
(package_id, ordinal, subject, relation, object)
```

Specification assertions retain the specification package identity; business
assertions retain the model package identity. Ordinals are deterministic within
each package. No validity conclusion depends on physical query structure.

## 7. Deliberate restrictions

This language uses SQL's grouping meaning but is intentionally narrower than
SQL:

- arbitrary grouping columns and expressions are rejected;
- partial composite keys are unrepresentable;
- grouping sets, rollup, cube, windows, and `DISTINCT` are deferred;
- joins are inner joins; outer joins require missing-row semantics;
- complete `NULL` and empty-group aggregate behavior remains to be specified;
- expressions are typed operations, never opaque embedded SQL.
- table aliases and role-playing joins are deferred and ambiguous revisits are
  rejected.
