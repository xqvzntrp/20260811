# JoinProof corpus

This corpus tests proof state as well as final acceptance. Each row in
`join-proof-corpus.tsv` declares the effective row keys, closure from the group
and input keys, and any uncontrolled keys after the declared joins.

| Case | Status | Why |
|---|---|---|
| `FORWARD_DIMENSION` | valid | An order determines its customer through a forward many-to-one join. |
| `REVERSE_REVENUE` | valid | Customer-to-order expansion adds `ORDERKEY`, which the aggregate input controls. |
| `BRANCH_FANOUT` | rejected | The ticket branch adds uncontrolled `TICKETKEY`, so an order amount can repeat. |
| `ONE_TO_ONE_REVERSE` | valid | A one-to-one traversal preserves row identity. |
| `IDEMPOTENT_MIN_BOUNDARY` | intentionally rejected | `MIN(Customer.CreditLimit)` is duplicate-insensitive, but the current general aggregate proof does not special-case it after branch expansion. |

The paired conformance models use the same safe, unsafe, and boundary pattern.
The boundary case documents incompleteness without weakening the soundness
invariant: accepted always means proved safe.
