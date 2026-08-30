# `:backend` — the §22 market-data proxy

The service issue 6.5 shipped a client for and could not demonstrate. One endpoint, no user data, no
database, and no reason to exist except that **EXT-001 forbids the app from talking to a data vendor
directly** and §16.1 nonetheless wants gold, crypto and NAVs.

```
GET /v1/market/prices?ids=crypto:btc.inr,gold:inr.gram.24k
→ {"quotes":[{"price_key":"crypto:btc.inr","unit_price_minor":745637000,"as_of":"2026-08-30"}]}
```

`unit_price_minor` is **integer paise** (MNY-001). `as_of` is an ISO date (TIM-002). Fewer quotes
than ids is normal. The shape lives in [`contracts/market-prices-v1.json`](../contracts/README.md),
which this module's suite and `:core:network`'s both read.

---

## What it serves

| Namespace | Example key | Upstream | Key needed | Cached for |
|---|---|---|---|---|
| `crypto:` | `crypto:btc.inr` | CoinGecko `simple/price` | no | 15 min |
| `gold:` | `gold:inr.gram.24k` | goldapi.io `XAU/INR` | **yes** | 24 h |
| `mf:` | `mf:inf109k01z48` | AMFI `NAVAll.txt` | no | 6 h (whole file) |
| `fx:` | `fx:usd.inr` | Frankfurter (ECB reference rates) | no | 24 h |

Two things a reader should know before trusting the table:

- **Without `CFO_GOLD_API_KEY`, `gold:` keys return no quote** and the gold vendor is never contacted.
  That is a supported state, not a misconfiguration — the client keeps its cached price and ages the
  label (P-04), exactly as it does when there is no backend at all (ADR-0030).
- **`fx:` is ECB reference rates, not RBI's.** RBI publishes no stable machine-readable feed. The two
  differ by a few paise on the rupee. See ADR-0032.
- **Policy rates (`repo`, `MCLR`) are not served here.** A rate is basis points (MNY-002); this
  endpoint's only money field is paise. It would need its own shape.

## What it deliberately does not do

- **No request logging.** Ktor's `CallLogging` plugin is not installed, so there is no logger to
  silence, and `logback.xml` carries no MDC in its pattern. No error response echoes a requested
  identifier either — the bodies are constant strings. That is EXT-003's testable half on this side.
- **No user data, no database, no session.** The only state is an in-memory cache of public prices.
- **No `ETag`.** §22.2 mentions it, but ADR-0030 rejected an OkHttp disk cache on the client, so
  nothing will ever send `If-None-Match` back.

---

## Run it locally, over real TLS

This is the half of issue 6.5 that could never be tested: `MockWebServer` serves cleartext and
`NetworkConfig` refuses a cleartext host, so **the pinned handshake had never once run** (ADR-0030).

```bash
./gradlew :backend:devTls          # mints two keypairs, prints two SPKI pins
```

It writes `backend/local/{dev,dev-next}.p12`, exports both certificates to
`app/src/debug/res/raw/`, and generates a debug-only `network_security_config.xml` plus the debug
manifest that points at it. **All of it is gitignored** — a fresh clone has no debug overrides at
all. `<debug-overrides>` applies only when `android:debuggable` is true, so none of this can widen
what a release build trusts, and no `SSLSocketFactory` seam goes into production code.

```bash
CFO_TLS_KEYSTORE=$PWD/backend/local/dev.p12 CFO_TLS_ALIAS=dev CFO_TLS_PASSWORD=cfo-dev-only \
  ./gradlew :backend:run

./gradlew :app:installDebug \
  -Pcfo.market.baseUrl=https://10.0.2.2:8443/ \
  -Pcfo.market.pins=<the two pins devTls printed>
```

`10.0.2.2` is the emulator's route to the host loopback, and it is in the certificate's SAN.

Check it by hand:

```bash
curl --cacert app/src/debug/res/raw/cfo_dev_ca.crt \
  'https://localhost:8443/v1/market/prices?ids=crypto:btc.inr,mf:inf109k01z48,fx:usd.inr'
```

### The four drills

| Drill | Do this | Expect |
|---|---|---|
| Prices land | add a holding with `price_key = crypto:btc.inr`, unlock | value and "as of" update — a **real pinned handshake** happened |
| Backend absent (P-04) | stop the server, refresh | cached price unchanged, no crash, no error UI |
| Rotation | restart with `CFO_TLS_KEYSTORE=backend/local/dev-next.p12 CFO_TLS_ALIAS=dev-next` | still works, **without rebuilding the app** — this is why two pins ship |
| Pin is real | reinstall with one wrong pin | the fetch fails and the cached price stands |

---

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `CFO_PORT` | `8080` | cleartext port |
| `CFO_TLS_PORT` | `8443` | HTTPS port, only if the three below are all set |
| `CFO_TLS_KEYSTORE` | — | PKCS#12 file |
| `CFO_TLS_ALIAS` | — | key alias inside it |
| `CFO_TLS_PASSWORD` | — | store and key password |
| `CFO_GOLD_API_KEY` | — | goldapi.io token; absent means `gold:` is unpriced |

A **partial** TLS triple is treated as no TLS rather than as an error, so a half-set environment
starts a working cleartext server instead of refusing to boot behind a terminator that was going to
provide the certificate anyway.

---

## Deploying it

**Not done, and deliberately open.** It needs a hosting account and a domain, which are the user's to
choose. The steps, in order:

1. `docker build -f backend/Dockerfile -t cfo-market-proxy .` — **from the repository root**; the
   module depends on `:core:model`, `build-logic` and the version catalog.
2. Push it to any platform that terminates TLS 1.3 in front of a container (Fly.io, Cloud Run,
   Render). Set `CFO_GOLD_API_KEY` if gold is wanted. Set nothing else.
3. **Get the pins.** §22.1 wants at least two — the current certificate and its replacement — because
   a rotation with one pin bricks every installed copy until the app is updated. On a managed
   platform the practical answer is to pin the **issuing CA's intermediates**, which are published
   and long-lived, rather than the leaf, which rotates on the platform's schedule and not yours:

   ```bash
   openssl s_client -showcerts -servername HOST -connect HOST:443 </dev/null 2>/dev/null \
     | openssl x509 -noout -pubkey \
     | openssl pkey -pubin -outform der \
     | openssl dgst -sha256 -binary | base64
   ```

   Repeat for each certificate in the chain you intend to pin. `:backend:devTls` computes the same
   value in-process for the local certificates, if openssl is not to hand.
4. Build the app against it: `-Pcfo.market.baseUrl=https://HOST/ -Pcfo.market.pins=sha256/…,sha256/…`.
   `NetworkConfig` refuses a non-https host and a host with no pins, so a half-configured release
   cannot be produced.
5. Re-run the four drills above against the deployed host.

**Watch the rotation.** The point of two pins is that you can replace the certificate behind one of
them. If both pins ever come from certificates that rotate together, the pin set is one pin wearing
a hat.

---

## Known, and accepted

- **The first `mf:` request after a restart takes about 3.5 seconds** — it pulls AMFI's whole 1.5 MB
  file. API-001 gives the client five seconds, so on a slow network that first request can time out.
  The client's answer to a timeout is to keep the cached price (P-04) and try again on the next daily
  refresh, by which time the index is warm and the same request takes 0.2 s. Not worth pre-warming
  the index at boot, which would download the file on every deploy whether anyone wants a NAV or not.
- **AMFI serves an incomplete certificate chain.** `Main.kt` turns on the JDK's AIA CA-issuer
  fetching so the missing intermediate is retrieved. This does not weaken verification — the chain
  still has to reach a trusted root — and the alternative was bundling an intermediate that expires
  on somebody else's schedule.
- **AMFI's published header understates its own column count** (six named, eight served). The parser
  reads NAV and date from the *end* of each row for that reason. It was written the other way first,
  and it silently priced nothing.
