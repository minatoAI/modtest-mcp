# modtest-bridge/1.0 — file-bridge protocol for local mod-test harnesses

**Status:** Stage 1 specification (frozen for this stage) · **Version:** `1.0` · **Scope:** local, single-player development clients.

This document is the normative reference for the ticket/receipt bridge used by `modtest-mcp`.
It replaces the ad-hoc v0.2 bridge shipped with the development rig (that wire format is **not**
compatible; see §9).

---

## 1. Design goals

1. **Machine-parsable.** Every op is described by JSON Schema; a client can validate a ticket
   before it is sent and can reason about results without reading implementation source.
2. **No daemon required.** The bridge is a **directory**. The game-side executor polls it; the
   agent side only reads/writes files. No sockets, no ports, no extra services.
3. **Crash-safe.** Writes are atomic; a half-written ticket can never be observed.
4. **Auditable.** Every ticket produces exactly one receipt; old receipts are archived, never
   overwritten.
5. **Safe by construction on multiplayer.** The only write-capable operations are gated by
   preconditions that a compliant executor MUST enforce, including a **remote-server refusal**
   requirement (§7.2).

Non-goals: remote control, multi-client orchestration (out of scope for 1.0), streaming
telemetry (polling only), authentication (the bridge is local-only; see §8).

---

## 2. Directory layout

```
<BRIDGE_DIR>/
├── catalog.json                       # optional: op catalog (see §6)
├── inbox/
│   ├── <ticket>.json                  # complete ticket — visible to the executor
│   └── <ticket>.json.tmp              # in-flight write — MUST be ignored by the executor
├── outbox/
│   ├── <ticket>.result.json           # receipt for <ticket>
│   └── archive/
│       └── <ticket>.<runstamp>.result.json
├── done/                              # ticket files that were executed successfully
├── failed/                            # ticket files that produced ok:false
└── rec/                               # optional telemetry recordings (executor-defined)
    └── <run>/...
```

`<BRIDGE_DIR>` is chosen by the caller:

* MCP server: `--dir <path>`, else `$MODTEST_AGENT_DIR`, else `./.modtest-agent`.
* Game-side executor: implementation-defined, but MUST be configurable.

`<ticket>` MUST match `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` (no path separators, no leading dot).
Implementations MUST reject anything else with `E_BAD_TICKET`.

---

## 3. Ticket (agent → executor)

```json
{
  "protocol": "modtest-bridge/1.0",
  "ticket": "t-0001",
  "trial": "t1",
  "created_utc": "2026-09-17T12:00:00Z",
  "timeout_ms": 15000,
  "on_error": "abort",
  "ops": [
    {
      "id": "o1",
      "op": "state.query",
      "params": { "what": ["pose", "light"] },
      "expect": { "pose.yaw": { "op": "gte", "value": 0 } },
      "timeout_ms": 5000,
      "on_error": "abort"
    }
  ]
}
```

| Field | Req | Type | Notes |
|---|---|---|---|
| `protocol` | ✅ | string | MUST be `modtest-bridge/1.0` (see §5) |
| `ticket` | ✅ | string | MUST equal the file name without `.json` |
| `trial` | – | string | free-form attempt label (`t1`, `t2`…). Echoed in the receipt |
| `created_utc` | – | string | ISO-8601 UTC; informational |
| `timeout_ms` | – | int | whole-ticket budget, default `10000`; `0` = no timeout |
| `on_error` | – | enum | `abort` (default) or `continue`; per-op override allowed |
| `ops` | ✅ | array | non-empty; executed **in order** |

### 3.1 Op entry

| Field | Req | Type | Notes |
|---|---|---|---|
| `id` | – | string | unique within the ticket; default `op1…opN` |
| `op` | ✅ | string | op name from the catalog (`domain.verb`) |
| `params` | – | object | MUST validate against the op's `paramsSchema` |
| `expect` | – | object | assertions evaluated after the op (§4.3) |
| `timeout_ms` | – | int | per-op budget, default = remaining ticket budget |
| `on_error` | – | enum | overrides the ticket-level `on_error` |

Unknown keys: a **ticket-level** unknown key MUST be rejected (`E_BAD_TICKET`); an **op-level**
unknown key MUST be rejected (`E_BAD_PARAMS`). Strictness is intentional — silently ignored
fields are how test rigs drift.

---

## 4. Receipt (executor → agent)

```json
{
  "protocol": "modtest-bridge/1.0",
  "ticket": "t-0001",
  "trial": "t1",
  "ok": false,
  "executor": { "id": "vanilla-client", "version": "1.0.0", "impl": "example-mod 0.1" },
  "started_utc": "2026-09-17T12:00:00.120Z",
  "finished_utc": "2026-09-17T12:00:01.870Z",
  "duration_ms": 1750,
  "error": { "code": "E_PRECONDITION", "message": "op 'world.place' needs singleplayer" },
  "ops": [
    { "id": "o1", "op": "state.query", "ok": true, "result": { "pose": { "yaw": 12.5 } }, "duration_ms": 3 },
    { "id": "o2", "op": "world.place", "ok": false,
      "error": { "code": "E_PRECONDITION", "message": "not a single-player session" } },
    { "id": "o3", "op": "shot.capture", "ok": false, "skipped": true }
  ]
}
```

* Exactly **one** receipt per ticket, at `outbox/<ticket>.result.json`.
* `ok` is `true` **iff** every non-skipped op is `ok` and no `expect` block failed.
* Ops that did not run because of `abort` MUST be present with `"skipped": true`.
* `result` MUST validate against the op's `resultSchema` (when the catalog is present).
* A receipt MUST NOT be written for a ticket that was never observed, and MUST never be
  overwritten: implementations archive first (`outbox/archive/<ticket>.<runstamp>.result.json`).

### 4.1 Error object

```json
{ "code": "E_BAD_PARAMS", "message": "human readable", "detail": { "path": "params.what" } }
```

| Code | Meaning |
|---|---|
| `E_PROTOCOL` | unsupported `protocol` value (see §5) |
| `E_BAD_TICKET` | ticket id/file mismatch, malformed JSON, unknown ticket-level field |
| `E_BAD_OP_ID` | duplicate or malformed op `id` |
| `E_UNKNOWN_OP` | op not in the executor's catalog |
| `E_BAD_PARAMS` | `params` failed `paramsSchema` (or op-level unknown key) |
| `E_PRECONDITION` | a declared precondition is not satisfied (incl. remote-server refusal) |
| `E_TIMEOUT` | per-op or per-ticket budget exceeded |
| `E_BUSY` | executor is already processing another ticket |
| `E_EXEC` | the op ran and failed (world/state/vanilla error) |
| `E_ASSERT` | an `expect` assertion failed |
| `E_UNSUPPORTED` | op exists but is not available in this build/mode |

Codes are **stable strings**; new codes may be added in a minor version, existing ones never
change meaning.

### 4.2 Ops array length

`receipt.ops` MUST have the same length as `ticket.ops`, in the same order, with matching `id`s.
This makes diffs between ticket and receipt mechanical.

### 4.3 `expect` assertion grammar (v1.0 subset)

```
"<dot.path>": { "op": "eq|ne|gt|gte|lt|lte|exists|matches|in", "value": <any> }
```
* The path is resolved against the op's `result` object (`pose.yaw`, `light.on`, …).
* Each entry is evaluated independently; the first failure yields `E_ASSERT` on that op with
  `detail.failed = "<path>"` and does **not** change the other ops' results.
* Implementations MAY add ops in a minor version; clients MUST ignore unknown assertion ops
  (treat the whole receipt as `ok:false` with `E_UNSUPPORTED` instead — see §5).

---

## 5. Versioning and negotiation

* The wire format is identified by `protocol` (required in both ticket and receipt).
* `modtest-bridge/1.x` MUST keep the field meanings and the directory layout of `1.0`.
  Additive changes (new op names, new error codes, new optional fields) are minor.
* A receiver that cannot honour `protocol` MUST write a receipt with `ok:false`,
  `error.code = "E_PROTOCOL"`, `error.detail.supported = ["modtest-bridge/1.0"]`, and MUST NOT
  execute any op.
* The MCP server writes the ticket's `protocol` from `--protocol-version` (default `1.0`) and
  advertises it in `initialize` → `capabilities.experimental.modtestBridge.protocol`.
* The executor's supported set is advertised in `catalog.json` → `protocols`.

---

## 6. Op catalog (`catalog.json`)

Optional but recommended: produced by the game-side plugin, consumed by agents and by
`modtest-mcp catalog`.

```json
{
  "protocol": "modtest-bridge/1.0",
  "protocols": ["modtest-bridge/1.0"],
  "catalog_version": "1.0.0",
  "executor": { "id": "vanilla-client", "version": "1.0.0", "impl": "example-mod 0.1" },
  "ops": [
    {
      "name": "state.query",
      "title": "Read player/world state",
      "paramsSchema": {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": { "what": { "type": "array", "items": { "enum": ["pose", "health", "armor", "light", "held", "all"] } } },
        "required": ["what"],
        "additionalProperties": false
      },
      "resultSchema": {
        "type": "object",
        "properties": { "pose": { "type": "object" }, "light": { "type": "object" } },
        "additionalProperties": true
      },
      "preconditions": [{ "kind": "singleplayer" }],
      "sideEffects": ["none"],
      "executorId": "vanilla-client",
      "divergence": { "vanilla": "none", "fakePlayer": "n/a (real player)" },
      "since": "1.0"
    }
  ]
}
```

### 6.1 Op naming

`domain.verb`, lowercase, dot-separated: `state.query`, `pose.set`, `input.set`, `inv.click`,
`use.item`, `world.place`, `shot.capture`, `rec.start`, `bench.read`, `cmd.run`.
Domains are open; providers MUST document theirs in the catalog.

### 6.2 OpSpec fields

| Field | Req | Type | Meaning |
|---|---|---|---|
| `name` | ✅ | string | `domain.verb` |
| `title` | – | string | one-line human title |
| `paramsSchema` | ✅ | JSON Schema | validated before execution |
| `resultSchema` | ✅ | JSON Schema | receipt `result` must validate |
| `preconditions` | ✅ | array | see §6.3; empty array = unconditional (read-only ops) |
| `sideEffects` | ✅ | array | see §6.4; `["none"]` = read-only |
| `executorId` | ✅ | string | which executor implements it |
| `divergence` | – | object | `{"vanilla": …, "fakePlayer": …}` — known behavioural differences |
| `since` | – | string | protocol/minor version that introduced the op |
| `llmSummary` | – | string | optional one-liner for agent consumption |

Allowed JSON Schema keywords in 1.0 (validators may reject others):
`$schema`, `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `const`,
`oneOf`, `anyOf`, `default`, `description`, `title`, `minimum`, `maximum`, `minLength`,
`maxLength`, `pattern`, `minItems`, `maxItems`.

### 6.3 Preconditions

| Kind | Payload | Satisfied when |
|---|---|---|
| `singleplayer` | – | the client owns an integrated server (`hasSingleplayerServer()`) |
| `flag` | `{"name": "allow-mutate"}` | the executor was started with that flag |
| `op` | `{"name": "state.query", "ok": true}` | a previously executed op succeeded |
| `permission` | `{"node": "..."}` | the caller holds the node (server-side executors) |
| `dimension` | `{"id": "minecraft:overworld"}` | player is in that dimension |
| `item` | `{"id": "..."}` | player holds that item |

### 6.4 Side effects (the safety vocabulary)

| Value | Meaning | Mutating? |
|---|---|---|
| `none` | pure read | no |
| `telemetry.recording` | writes recording files under the bridge dir | no (does not touch the game) |
| `player.input` | writes player input (movement/keys) | **yes** |
| `player.inventory` | moves items | **yes** |
| `player.state` | pose/health/gamemode/etc. | **yes** |
| `world.blocks` | places/breaks blocks | **yes** |
| `world.entities` | spawns/removes/kills entities | **yes** |
| `server.command` | runs a server command | **yes** |
| `render.pipeline` | changes renderer/shader state | implementation-defined |

An executor MUST treat any value other than `none` / `telemetry.recording` as **mutating**.

---

## 7. Execution semantics

### 7.1 Ordering, concurrency, timeouts

* Ops run sequentially in array order; a ticket is the unit of concurrency.
* At most one ticket is processed at a time per executor; a second ticket observed while busy is
  answered with `E_BUSY` (and left in `inbox/` for the next poll) — implementations MAY instead
  queue, but MUST document which.
* On per-op timeout: that op gets `E_TIMEOUT`; remaining ops follow `on_error`.
* On per-ticket timeout: pending ops MUST be marked `skipped` and the receipt MUST carry
  `E_TIMEOUT`.
* Polling interval is implementation-defined; 2 Hz is a known-good default for a client tick loop.

### 7.2 Safety requirements (normative)

A compliant executor **MUST**:

1. **Refuse remote sessions.** If the client is connected to a multiplayer/remote server
   (`mc.getCurrentServer() != null` or `hasSingleplayerServer() == false`), any op whose
   `sideEffects` are mutating MUST fail with `E_PRECONDITION` and MUST NOT run. Read-only ops
   (`sideEffects: ["none"]`) MAY run.
2. **Default to read-only.** Mutating ops MUST additionally require the executor to have been
   started with an explicit opt-in flag (convention: `allow-mutate`); without it they fail with
   `E_PRECONDITION`.
3. **Refuse unsupported ops** with `E_UNKNOWN_OP` / `E_UNSUPPORTED` rather than silently skipping.
4. **Never bypass authentication or server checks**, and never modify the client's network layer.
5. **Leave a trace:** every executed op writes a line to the executor's log with ticket, op id,
   name and outcome.

These requirements implement the project's positioning: a **local, single-player development
harness**, not a gameplay automation tool.

### 7.3 Crash and restart behaviour

* A `.tmp` file older than the poll interval SHOULD be ignored (never renamed by the reader).
* Tickets in `inbox/` that were never answered are retried after restart; implementations SHOULD
  answer with `E_BUSY`/`E_TIMEOUT` if they cannot guarantee completion.
* Receipts are immutable once written.

---

## 8. Security model (read this before exposing anything)

The bridge is a **local file protocol**. It has no authentication by design: anyone who can write
into `<BRIDGE_DIR>/inbox/` can ask a running game client to execute ops, subject to §7.2.

Consequences:

* Do **not** place `<BRIDGE_DIR>` on a network share, in a synced folder, or anywhere another local
  user can write.
* Executors MUST apply §7.2 regardless of who wrote the file.
* The MCP server itself performs **file I/O only**: no sockets, no subprocesses, no shell.

---

## 9. Migration from the v0.2 rig bridge

The original rig used a v0.2 layout (a `<rig>-agent/` root, free-text `params` strings, no
`protocol` field, no `expect`, no `sideEffects`). Those tickets are **not** accepted by this
protocol; the rig keeps running on its own frozen copy (no changes were made to it).

| v0.2 | 1.0 |
|---|---|
| `<RIG>_AGENT_DIR` (v0.2) | `--dir` / `MODTEST_AGENT_DIR` |
| ticket `{ticket, trial, ops:[{op, ...op-specific}]}` | adds `protocol`, op `id`, `expect`, `timeout_ms`, `on_error` |
| op params described in prose (`"action= click|drag"`) | `paramsSchema` (JSON Schema) |
| no side-effect metadata | `sideEffects` + `preconditions` (§6.3/§6.4) |
| no assertion support | `expect` (§4.3) |
| receipt `{ticket, ok, trial, ops:[{op, ok, …}]}` | adds `protocol`, `executor`, timestamps, `duration_ms`, structured `error` |
| no version negotiation | `protocol` + `E_PROTOCOL` + `catalog.protocols` |

---

## 10. Minimal conformance checklist

An implementation claiming `modtest-bridge/1.0` conformance MUST:

- [ ] reject bad ticket names/JSON/unknown fields with the codes in §4.1
- [ ] write tickets atomically and ignore `.tmp`
- [ ] answer with exactly one receipt per ticket, never overwrite, archive instead
- [ ] keep `receipt.ops` aligned (same order/length/ids) with `ticket.ops`
- [ ] mark unrun ops `skipped`
- [ ] enforce §7.2 (remote-server refusal + read-only default)
- [ ] include `protocol` in both directions and answer `E_PROTOCOL` on mismatch
- [ ] (recommended) publish `catalog.json` and validate `params`/`result` against it

The reference implementation of the *agent side* is `src/modtest_mcp/server.py`; the offline
conformance smoke test is `src/modtest_mcp/smoke.py` (`MCP-SMOKE-ALL-PASS`).
