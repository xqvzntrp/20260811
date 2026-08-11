# Fan-out-safe revenue

This standalone package demonstrates a semantic error that ordinary SQL usually
allows: summing orders after joining both an order branch and a ticket branch
from a customer duplicates each order once per ticket.

`revenue-by-customer.model` is safe and valid. `unsafe-revenue-with-tickets.model`
is intentionally invalid; it must fail with `AGGREGATE_FANOUT`. The compiler
therefore makes the aggregate's grain an enforceable contract rather than a
reviewer convention.

Run the package from the repository root:

```sh
java TestRunner.java --manifest package/fanout-safe-revenue/manifest.tsv
```
