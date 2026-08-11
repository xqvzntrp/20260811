# SQL concepts and their semantic adaptation

| SQL concept | Authoring representation | Constraint |
|---|---|---|
| schema catalog | structural TSV | One validated, case-insensitive specification |
| table and column | `TABLE_NAME`, `COLUMN_NAME` | Column identity is qualified by its table |
| primary key | `PRIMARY_KEY_NAME`, `PRIMARY_KEY_PART` | Exactly one complete ordered key per table |
| foreign key | foreign-key TSV columns | Directed `MANY_TO_ONE` or proven `ONE_TO_ONE`; complete target key required |
| `FROM` | `FROM` on `GROUP` | Establishes input row population |
| inner `JOIN` | ordered `JOIN` named foreign key | Direction determines preservation or one-to-many row expansion |
| `WHERE` | qualified Boolean column | Filters before grouping |
| `GROUP BY` | `GROUP_BY` named primary key | Only governed identities are legal grouping coordinates |
| aggregate | `AS OP(Table.Column)` | Rejected when an uncontrolled join branch can duplicate the input fact |
| select expression and alias | named `PROPERTY ... AT ... AS ...` | Publishes a semantic property at the group grain |
| `HAVING` | Boolean property at the group | Filters after property establishment |
| `ORDER BY` | grouped column or published property | Presentation only; never grain |

The restriction to primary keys is intentional. A business coordinate such as
fiscal year, product category, or scenario becomes groupable only after it is
modeled as an identifiable table with a declared primary key. The TSV therefore
acts as the governed catalog of legal grains.
