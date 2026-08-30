# `contracts/` — wire shapes both sides of the network read

A contract that lives in two places drifts. These files live in one, and **two test suites read the
same bytes**, so renaming a field on either side turns one of them red.

| File | Endpoint | Server | Client |
|------|----------|--------|--------|
| `market-prices-v1.json` | `GET /v1/market/prices?ids=` (§22.2) | `:backend` | `:core:network` |

Both modules' test tasks receive `-Dcfo.contracts.dir=<this directory>`, set in their
`build.gradle.kts`, so the tests find these files from any working directory.

## `market-prices-v1.json`

Four quotes, one per namespace the proxy serves. The values are illustrative; **the shapes are not.**

- **`unit_price_minor` is an integer number of paise (MNY-001).** This is the single most important
  thing about the whole contract. If the server ever sent `7890.12`, parsing it would route money
  through a `Double` and every derived figure — value, gain, XIRR, allocation share — would inherit a
  rounding error that no test in the app would catch, because every test would read the same wrong
  number. The backend suite asserts the emitted literal contains no decimal point.
- **`price_key` is echoed exactly**, and must satisfy `PriceKey`'s charset — `[a-z0-9._:-]{1,64}`,
  lowercase. AMFI publishes ISINs in uppercase; the server lowercases them when indexing. A quote for
  a key the client did not ask for is dropped client-side, so the echo is load-bearing.
- **`as_of` is an ISO `yyyy-MM-dd` date-only string (TIM-002)**, never a timestamp — it is the day the
  *market* priced the instrument, which is not the day this device heard about it. The app stores
  those two separately (`priced_on_iso_date` vs `price_fetched_at_utc_millis`).
- **Fewer quotes than ids is normal**, not an error: an unrecognised instrument simply has no quote.

The client's reader sets `ignoreUnknownKeys = true`, so the server may add fields without a client
release. It may not rename or retype one.
