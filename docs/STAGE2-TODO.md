# Stage 2 backlog — the game-side executor and its safety rails

Stage 1 shipped the **agent side**: the protocol (`docs/PROTOCOL.md`), the MCP server, the packaging
and the mod-agnostic analysis tools. Stage 2 ships the **executor** that makes a real client
answerable to that protocol, split into modules so that "generic core" and "mod-specific plugin"
stay separable.

**Status: implemented and real-machine verified.** The executor, the guard and both build variants
exist in this tree (`core/` + `forge/`), all `core` unit tests are green, and the end-to-end loop was
verified on a real 1.20.1 client in single-player and on a LAN session. The tables below are kept as
the acceptance record: each row states the criterion that made the item checkable, and the rows were
closed against those criteria rather than against "it compiles".

The **terminal state — artifact identity, the honest verification list, the `pose.set` three-state
semantics and the known limitations — is documented in
[`README.md` §9](../README.md#9-current-state-verification-status-and-known-limitations)**. Read
that before drawing conclusions from this backlog, and note in particular:

* **no byte-reproducible jar claim** (zip timestamps) — integrity rests on entry counts, the four
  guardrails and content;
* `pose.set` reports **`applied` / `notClientVerifiable` / `skipped`** — one word must never carry
  two meanings; single-player position is reverted (< 0.57 s) while a remote LAN position applied and
  persisted (16.4 s, no revert);
* the five ops that still answer `E_UNSUPPORTED` are a **scope decision, not a defect**;
* official-launcher parity, pixel-level reading, `localhost` ↔ `127.0.0.1` matching and
  `wait.frames` remain **unverified / limited** (see README §9.4).

---

## M1. `core-relay` (generic, no mod imports)

**Responsibility:** the protocol half of the executor — poll, validate, dispatch, answer, archive.

| # | Item | Acceptance |
|---|---|---|
| 1 | `BridgeConfig` (bridge dir, poll interval, executor id/version, `allow-mutate`, log sink) | dir and flags are constructor-injected; no hardcoded paths |
| 2 | Ticket reader: enumerate `inbox/*.json`, skip `.tmp`, skip files that fail to parse, order deterministically | unit test: a `.tmp` and a malformed file never block a valid ticket |
| 3 | Validator: §3/§5 rules + strict unknown-field rejection | unit test: one case per `E_*` code in §4.1 |
| 4 | Atomic receipt writer (`*.result.json.tmp` → rename, `fsync`) + archive-before-overwrite | unit test: no partial receipt is ever observable |
| 5 | `receipt.ops` alignment (same order/ids/`skipped` flags) | property test over randomly generated tickets |
| 6 | Busy policy (documented: `E_BUSY` immediately or queue) | one behaviour, documented in the code and the catalog |
| 7 | Structured local log (ticket id, op id, name, outcome, duration) | log lines greppable for a full ticket replay |

## M2. `input-injection` (generic, vanilla-only targets)

**Responsibility:** the only module allowed to write player input, plus its gates. This is the
module that makes the harness useful for lighting/rendering reproducibility (deterministic walks and
looks) and the one that must be impossible to misuse.

| # | Item | Acceptance |
|---|---|---|
| 1 | Port the input writer (post-`Input.tick` write inside `aiStep`) as a self-contained unit | zero mod-specific imports; compiles against a vanilla dev workspace |
| 2 | **Default OFF + explicit activation token** (not just a probe flag): a dev-build flag *and* a token with an expiry, issued out-of-band by the operator | without both, an op touching input fails `E_PRECONDITION`; unit test proves the refusal |
| 3 | **Guard tier 1 — read-only ops are never gated** by the injection policy | a read-only op is allowed on any host and performs zero writes |
| 4 | **Guard tier 2 — writing input requires the armed state** (dev flag + unexpired token, §7.2) | missing/expired token ⇒ `E_PRECONDITION` and no write; a token that expires mid-session stops working |
| 5 | **Guard tier 3 — default deny + explicit host allow-list**: single-player, or a host the operator declared as theirs (`localhost`/`127.0.0.1`/own dev server); **undeclared hosts are refused before any write**; **every allowance is audited loudly** (who / host / time / token *fingerprint* / op — never the token value) | fake an undeclared remote session ⇒ zero writes (counter in the writer); declared host ⇒ exactly one write plus an `ALLOWED-INPUT …` audit line |
| 6 | Rate/amplitude clamps (human-speed envelope) documented as *safety* limits, not features | a ticket asking for superhuman speed is clamped or rejected, and the receipt says which |
| 7 | No-op when the client is paused / not in a world / in handshake | unit tests for all three states |
| 8 | **Two variants from one source tree**: guarded (default, the only released one) and unguarded (`-Punguarded`, self-compiled) | artifact self-identifies: manifest `Modtest-Guard-Variant`, version line `guard=…`, startup warning; an unstamped artifact counts as guarded; every release artifact is guarded |

## M3. `ticket-executor` (generic primitives)

**Responsibility:** vanilla-level op primitives — inventory, item use, world queries, telemetry —
each declared with `sideEffects` and `preconditions`.

| # | Item | Acceptance |
|---|---|---|
| 1 | `state.query` (pose/health/armor/held/light-provider fields) | result validates against the catalog `resultSchema` |
| 2 | `pose.set` / `look.set` with settle verification (closed loop on readback) | receipt reports `settled: true` only after the readback matches |
| 3 | `inv.*` primitives (open/select/click/drag/toss) with the "release using-item before inventory mutation" hook | hook is a **table-driven** `beforeOp` hook, not a hardcoded branch |
| 4 | `use.item` primitives with explicit trap/guard tests | every guard has a negative test that proves the guard fires |
| 5 | `shot.capture`, `bench.read`, `wait.frames` | artifacts land under the bridge dir; no absolute paths |
| 6 | `expect` assertion engine (§4.3 subset) | 9 comparison ops × pass/fail cases |

## M4. `op-provider` SPI (the extension point)

**Responsibility:** make "add an op for your own mod" a data + interface task instead of a fork.

| # | Item | Acceptance |
|---|---|---|
| 1 | `OpSpec` record (name/title/paramsSchema/resultSchema/preconditions/sideEffects/executorId/divergence/since/llmSummary) with catalog serialisation | round-trips through `catalog.json` byte-identically for a fixed fixture |
| 2 | Runtime registration (`ServiceLoader` or explicit `register(provider)`) replacing today's static table | a test provider lives in a separate source set and registers without touching core code |
| 3 | Precondition evaluator with the §6.3 kinds | one negative test per kind |
| 4 | Side-effect gate: any non-`none` op is refused unless `allow-mutate` **and** single-player | the two refusals are distinguishable in the receipt (`detail.reason`) |
| 5 | Catalog versioning + `protocols` advertisement | a client with an unknown op gets `E_UNKNOWN_OP`, not a crash |
| 6 | Optional JSON-Schema validation of params/results (small in-repo validator; no new dependency) | schema violations produce `E_BAD_PARAMS` with `detail.path` |

## M5. `examples/` — the mod-specific plugin

**Responsibility:** prove the SPI by re-implementing the originating project's needs as a plugin.

| # | Item | Acceptance |
|---|---|---|
| 1 | Example provider: light/spotlight state + setting knobs as ops | the example lives entirely under `examples/`; core has no reference to it |
| 2 | Example command relay (the "knobs with no op" escape hatch) documented, read-only by default | relay writes are opt-in and logged |
| 3 | Example ticket set (`examples/tickets/*.json`) that runs against a test world | a scripted run produces receipts that validate against the example catalog |
| 4 | "Bring your own mod" guide: 60 lines, one screenshot, one working op | a reader can add an op without reading core source |

## M6. Release rails (must land before any binary ships)

| # | Item | Acceptance |
|---|---|---|
| 1 | **CI assertion: debug/harness entries in the release artifact == 0** | build job greps the artifact entry list for `debug`/`harness`/`ticket`/`inject*` and fails on any hit; the check is a required status |
| 2 | Separate build variant for the harness (dev-only) — the released variant must not even contain the classes | entry-list assertion above plus a manifest assertion (`MixinConfigs` = core only) |
| 3 | Harness bundles must not be installable by normal players: no release to game-mod platforms, GitHub only | release checklist item; README first screen keeps the disclaimer |
| 4 | Signed artifacts + provenance (checksums published) | checksum file attached to each release |
| 5 | Protocol changelog: `1.0` → next minor documented per §5 rules | a test that fails when the wire format changes without a version bump |

## M7. Stage 1 loose ends (small, do them while touching the files)

| # | Item | Acceptance |
|---|---|---|
| 1 | `imgdiff.js` / `lumastats.js`: hoist the shared PNG codec into one module | both self-tests still pass |
| 2 | `rec-analyze.js`: document the recording CSV column contract in `tools/README.md` | the contract is written down, not just parsed |
| 3 | `paired-analyze.ps1`: replace the spec-JSON coupling with a documented minimal schema | an example spec ships in `examples/` |
| 4 | Cross-platform parity: either rewrite the three GDI+ scripts in Node, or keep them labelled Windows-only in every doc | no tool is silently Windows-only |
| 5 | Rotate any RCON password that was ever committed in a source tree | the old value is invalid on the dev server; no credential literal exists in any repo |
| 6 | English-only pass over the remaining CJK comment lines (Stage 1 ships 124 lines across 13 analyzer files; headers and public docs are already English) | every public-facing comment and `--help` string is English; behaviour unchanged; both self-tests still pass |
| 7 | Test coverage for the analyzers that have none (`lumastats.js`, `move-flicker.js`, `tp-space-solve.js`, the three `.ps1` image tools) | each analyzer has at least a fixture-based test; `tools/README.md` says which are covered |
