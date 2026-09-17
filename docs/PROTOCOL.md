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
| `E_TIMEOUT` | per-op or per-ticket budget exceeded — also the code for "a frame / a sampling window was waited for and did not arrive" (§6.2c). A timeout is a failure and is never reported `ok:true`. |
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

**Example — `input.set`, the one op that writes player input** (see §7.2 for its gates):

```json
{
  "name": "input.set",
  "title": "Queue a player-input command",
  "paramsSchema": {
    "type": "object",
    "additionalProperties": false,
    "properties": {
      "forward":    { "type": "number" },
      "strafe":     { "type": "number" },
      "yawDelta":   { "type": "number" },
      "pitchDelta": { "type": "number" },
      "jump":       { "type": "boolean" },
      "sneak":      { "type": "boolean" },
      "sprint":     { "type": "boolean" },
      "ticks":      { "type": "integer" }
    }
  },
  "resultSchema": { "type": "object" },
  "preconditions": [{ "kind": "permitted-session" }],
  "sideEffects": ["player.input"],
  "executorId": "forge-client",
  "since": "1.0"
}
```

Field semantics: every field is optional and **missing fields default to zero/false**, so
`{"params": {}}` is a no-op command (equivalent to the pre-parameter behaviour). Values are clamped
to the human-speed envelope (§7.2) *before* they reach the client, and the audit line records the
values actually written. `ticks` is a **hold duration** (the reference implementation allows 1..200);
it is a scheduling hint, not part of the command value, and every tick of the hold is re-evaluated
by the guard.

**What the receipt reports, and what it does not.** The result carries `queued:true` (the command was
accepted for injection), `queuedCommand` (the values **as requested in the ticket**), and — when a
write actually happened — `writtenCommand` (the values **after** clamping) plus `ticks`. These are
*not* the authoritative record of what reached the client: the **audit line**
`ALLOWED-INPUT … cmd=[…]` in the executor log is. Read `writtenCommand` as a convenience copy;
read the audit line as the evidence.

### 6.2a Op outcome: `ok:true` means the op really executed (clarification)

**Normative rule:** `ok:true` on an `ops[]` entry means *that op executed*. Consequences:

* A **guard refusal** (dev flag off, no valid activation token, undeclared host) is **not** an
  executed op: it MUST be reported as `ok:false` with a stable `error.code` —
  `E_PRECONDITION` — and a message that preserves the detail (`allowed=false`, `queued=false`, the
  refusal reason). It MUST NOT be reported as `ok:true` with `allowed:false`.
* A **no-op** is different: the op *did* execute and determined there was nothing to do (client
  paused, handshake, not in a world). It keeps `ok:true` together with `noop:true`.
* Therefore "every `ops[].ok` is true" is a valid success test, and this is a **clarification inside
  1.0, not a version change**: no new error code is introduced (`E_PRECONDITION` already covers the
  guard's conditions), so no negotiation is affected. Executors that previously emitted
  `ok:true` + `allowed:false` for guard refusals were the bug; agents that keyed on
  `ops[].ok` were being told that a refused injection had happened.

### 6.2b Declared hosts, and "requested ≠ applied"

**Host allow-list matching (normative).** The allow-list compares the **host only**; the port is
ignored in both directions (`MODTEST_ALLOWED_HOSTS=127.0.0.1` matches a connection to
`127.0.0.1:25585`, and a declaration of `localhost:25585` matches a connection to `localhost`).
Rationale: declaring a host declares *the machine*, so a development server may move ports without
invalidating the declaration, and a low port number cannot become a way to slip past a declaration.
IPv6 addresses **must be bracketed** when a port is present (`[::1]`, `[::1]:25585`); a bracketed
address matches a connection to the same address. If an executor cannot determine the address of a
**connected** session it **MUST** refuse and say so — *"host address unavailable … cannot verify
ownership; refusing instead of assuming it is allowed"* — and MUST NOT guess an address, and MUST NOT
treat an unknown address as an allowance. (Single-player needs no address: the policy allows it
before the allow-list is consulted.)

**Partial application.** An op that can only partially take effect — `pose.set` is the reference
case — MUST report what actually happened: `applied:{field: value}` and
`skipped:{field: {requested, actual, reason}}`, plus `authority: "client" | "server"` and a `note`.
`ok:true` keeps meaning "the op executed" (§6.2a) and never implies "every requested field took
effect".

**Position is never reported as `applied`.** Whenever a server authority exists — which includes a
single-player world, whose **integrated server is authoritative too** — `x`/`y`/`z` are **always**
reported under `skipped` with `reason: "server-authoritative position"`, and `applied` is reserved for
fields the client genuinely owns (measured on a real client: **rotation only**). The receipt's `note`
says so: *"client position readings cannot be authoritative; position is owned by the server"*.

This is deliberately **not** derived from any read-back, because a read-back cannot establish
authority: the authority's update lands later than the settle window (measured: the pose is pulled
back **< 0.57 s** after `pose.set`, while consecutive readings are only ~50 ms apart), so **even two
agreeing readings can both be the transient local value** (round 12: a receipt claimed five applied
fields with `dz = -4e-15`). Increasing the number of readings does not help; the window is simply
shorter than the revert delay. Rules for ops that report what took effect:

* fields the client owns (rotation) may be reported as `applied` when the settled value matches the
  request; a remote session gets a longer bounded budget for the server round trip;
* if the pose did not settle in the budget, **nothing** may be reported as applied: every requested
  field goes to `skipped` with `reason: "not settled"`;
* every skipped field carries `requested` + `reason`, and for server-owned fields the client's
  snapshot is reported as **`observedAtReadback`** — deliberately *not* `actual`: it is a
  client-side reading taken inside the settle window, it is **not** the authoritative value (the
  server owns position and never reports it synchronously), and naming it `actual` made a receipt
  read as "the position really is 12" while the truth was 2.851… . The `applied` branch (client-owned
  fields) keeps `actual`, because there the client's value *is* the truth;
* the top-level `pose` object is a client-side snapshot as well and is marked
  **`poseSource: "client-readback"`**; `settled` means "the settle loop converged", not "this pose is
  authoritative"; and `ticks` on an `input.set` receipt is the **requested hold length**, not the
  number of writes performed (those appear only in the audit lines);
* **three states, not two.** A field is reported in exactly one of:
  * **`applied`** — the client owns it and the settled value matches the request (rotation);
  * **`notClientVerifiable`** — the authority owns it and the client cannot witness the outcome. The
    request was delivered; whether the authority applied it is not observable from the client. Each
    entry carries `requested` + `observedAtReadback` + `reason: "the server owns the position; the
    client cannot witness whether it applied"`;
  * **`skipped`** — it did **not** take effect, or could not be decided: `reason` is
    `"server-authoritative position"` (the client watched the value revert), `"did not take effect"`,
    or `"not settled"` (the settle window expired).
  **One word must never carry two meanings**: conflating "did not take effect" with "applied but the
  client cannot testify to it" reported a *working* remote teleport as if it had failed.
* `authority` and `note` are derived from the same verdict as the three states, so they cannot
  contradict it;
* **when in doubt, report `skipped` or `notClientVerifiable`.** An honest "not applied / cannot be
  determined" is required; an `applied` that turns out to be false is a defect.

**Session asymmetry (measured, and it is not a detail).** The same `pose.set` behaves in opposite ways:

| Session | Position after `pose.set` | Evidence |
|---|---|---|
| single-player (integrated server) | **reverted to the old value in < 0.57 s** | round 11/12 read-backs; the value was back to `2.85136002226049` |
| remote (LAN, dedicated server) | **applied and persisted; no revert within 16.4 s** | round 14: client read `z=12` for +16.39 s; a *newly connected* client read `z=12.0` before sending any request; the server log shows `at (…, 17.60936942239352)` on first login and `at (…, 12.0)` on the second |

So "position does not stick" is a **single-player** fact, not a protocol fact. On a remote session the
position change is a real, usable capability — which is exactly why it must be reported as
`notClientVerifiable` rather than `skipped`.

**Authority is not "single-player vs multiplayer".** A single-player world's **integrated server is
authoritative too**: client-side `moveTo` values are overwritten by the next authoritative update, so
`authority: "client"` does **not** imply that a teleport will stick. What does stick on both session
types is client-side **rotation**; position may land in `skipped` in either case, and the receipt
must say so.

### 6.2c Implemented op set: `inv.click`, `inv.toss`, `use.item`, `shot.capture`, `bench.read`

These five were declared in the catalog but answered `E_UNSUPPORTED` in the reference client. As of
`vanilla-client` 1.0 they are **implemented**, and this section is normative for all of them. Three
conventions apply to every op below:

* **Requested ≠ observed.** A field that restates what the ticket asked for and a field that carries
  what the client measured are **different fields**, always (`requestedCount` vs `observedDelta`,
  `before` vs `after`, `sampleFrames` vs `sampleCount`). No receipt may present a request as a
  result. `ok:true` continues to mean "this op executed" (§6.2a) and never "the request took effect".
* **Three states.** Ops that can partially apply report `applied` / `notClientVerifiable` / `skipped`
  exactly as §6.2b defines them, and each receipt carries a machine-readable top-level `verdict`.
  A server-owned effect the client cannot witness is **`notClientVerifiable`**, never `skipped`
  (that would report a working feature as broken) and never `applied` (that would claim it).
  A guard **no-op** (paused / handshake / not in a world) is reported as `verdict:"skipped"` with
  `skipped.dispatch.reason` naming the policy reason: the op executed and is `ok:true`, but **nothing
  reached the client and no audit line was written**, because no write happened. Every write op
  (`inv.select`, `inv.click`, `inv.toss`, `use.item`, `pose.set`, `world.place`, `input.set`) follows
  this rule — a no-op that still writes would be a write nobody authorised and nobody logged.
* **`E_TIMEOUT` is the code for "the client never got there in time"** — a frame that did not arrive,
  a sampling window that did not complete. A timeout is a **failure**: `ok:false`, `error.code:
  "E_TIMEOUT"`, message naming what was waited for. A timeout **MUST NOT** be answered `ok:true`, and
  **MUST NOT** be answered with an empty/blank result standing in for a real one.
* **A verdict is a claim, and a claim needs a settled observation.** Two rules, both learned from
  measured defects on a real client, apply to every receipt in this catalog:
  1. **A negative conclusion requires settled state.** `verdict:"skipped"`, "slot N is empty",
     "no container is open", "no item in the hand" all assert that something did **not** happen or is
     **not** there. Container/inventory state is synchronised **asynchronously** (a join, a dimension
     change, or a click/toss dispatched a moment ago), so one read that has not caught up yet is
     indistinguishable from the real thing — and turning it into a fact makes an agent retry an action
     that already worked. When an adapter reports that its container view may still be catching up
     (`containerSyncPending`), core therefore answers **"cannot determine"**: `E_PRECONDITION` whose
     message **begins with the exact phrase `cannot determine`**, instead of asserting emptiness, and
     reports an **unchanged** read-back as **`notClientVerifiable`**, never as `skipped`. Re-read
     (`state.query`, another op) to establish the outcome. A settled read still produces the plain,
     factual refusals ("slot N is empty", "no container is open", "no item in the hand").
     **The `cannot determine` prefix is the machine-discriminable marker.** The error object carries
     only `code`, `message` and `path` (§4.1), so `E_PRECONDITION` is the *same code* for "this state
     could not be determined" and for a settled refusal ("slot N is empty"). The **only** discriminator
     is that prefix; consumers **MUST** match it. This is a **known limitation of the error
     vocabulary**, recorded rather than papered over: distinct codes (or a structured discriminator)
     are a change for a future protocol revision, and **no new error field is added for it here** — in
     particular the free-form `detail` object (§4.1) is **not** part of this contract and MUST NOT be
     relied on to tell the two apart. (The reference implementation currently also carries an
     informational `detail.reason` key; it is not contractual and is expected to be dropped when the
     error vocabulary is next revised.)
     **How to test it — a criterion correction from the closing round.** Judge these two outcomes by
     the **assertion form** (the `verdict` value, and which of the `applied` / `notClientVerifiable` /
     `skipped` objects is populated), **not** by whether some string occurs in the message. Substring
     matching is what made a correct message look like a failure during review:
     `cannot determine whether slot 1 is empty` legitimately contains the words `is empty`.
     **Known exception, recorded rather than papered over:** world **block occupancy** has no
     equivalent synchronisation signal — only container contents do — so `world.place`'s "cell is
     occupied" refusal (`E_EXEC`) is still a client-side factual assertion about a
     server-authoritative world. It is deliberately left that way: inventing a sync signal for world
     blocks would be a guess, and this protocol only claims what the client can witness. Treat that
     one refusal as **this client's view**, not as the authority's verdict, and read the block back
     (`blockObserved`) after a placement rather than treating the pre-check as proof of absence.
  2. **A self-reported field MUST NOT exceed what the client can witness.** `world.place`'s `placed` is
     the client's own read-back of its world (`blockObserved` carries the id it observed, `null` when
     the adapter has no block query), never an unconditional `true`, and its verdict is
     **`notClientVerifiable`** because the authority decides whether to keep the block. If a client
     cannot observe a thing, the receipt says it cannot — it never reports a request as a measurement.

**World interaction is out of scope.** Using or placing blocks against the world (`useItemOn`, block
placement/interaction through this path) is **not** part of this batch: those ops keep answering
`E_UNSUPPORTED`. `use.item` covers only the hand-held *use* action.

#### `inv.click` — click a slot in the open container

| | |
|---|---|
| Params | `slot` (integer, **required**, player-inventory index; armor is 36..39, offhand 40) · `button` (integer, default `0`) · `mode` (string, default `"pickup"`; one of `pickup`, `quick_move`, `swap`, `clone`, `throw`, `quick_craft`, `pickup_all`) |
| Side effects | `player.inventory` |
| Preconditions | `permitted-session`, `flag: allow-mutate` |

Result: `windowId`, `slot`, `button`, `mode`, `before:{id,count}`, `after:{id,count}`,
`cursorBefore`, `cursorAfter` (each `{id,count,observed}`; `observed:false` means this client does
not expose the carried stack — the core reports that it did not observe it rather than guessing),
the three verdict objects and `note`.

* `verdict:"applied"` — the client's own menu shows the expected change (an observed difference, not
  the request echoed back).
* `verdict:"notClientVerifiable"` — either the session has a server authority (the click was
  dispatched; whether it changed anything is not observable from the client), **or the client's menu
  had not changed by the time it was read back**. The second case is deliberate: an unchanged early
  read is **not** evidence that the click did nothing, and reporting it as `skipped` is the defect
  P9 measured on a real client. The entry's `reason` says the read may simply be early.
* `verdict:"skipped"` — **only** a guard no-op, where nothing was dispatched at all
  (`skipped.dispatch.reason`): it is never derived from an unchanged read-back.

Errors: `E_BAD_PARAMS` (missing/non-numeric `slot`, `slot` outside the window, `mode` outside the
list), `E_PRECONDITION` (no container open for a container slot, or an armor slot — armor slots are
refused **unconditionally**, whatever `mode` or item is involved), `E_TIMEOUT` (the per-op budget),
`E_PRECONDITION` (guard refusal), `E_UNSUPPORTED` (this adapter does not implement the click).

#### `inv.toss` — drop items from a slot

| | |
|---|---|
| Params | `slot` (integer, **required**) · `count` (integer, default `1`, range `1..64`) |
| Side effects | `player.inventory` |
| Preconditions | `permitted-session`, `flag: allow-mutate` |

Result: `requestedCount`, `observedDelta` (how many items the client actually saw leave the slot),
`before`, `after`, `partial`, three verdict objects, `note`.

* `observedDelta == requestedCount` ⇒ `applied`; `0 < observedDelta < requestedCount` ⇒ `applied`
  with `partial:true` (**a partial toss is normal, not an error**); `observedDelta == 0` ⇒
  **`notClientVerifiable`**, never `skipped`: a read-back that had not changed yet is not proof that
  the toss did nothing (P9). For a server session the same verdict applies. `skipped` is reserved for
  a guard no-op, exactly as for `inv.click`.
* Server-side behaviour uses the vanilla drop path (the same request as pressing the drop key),
  **not** a local `player.drop(...)`, so the client and the authority cannot drift apart.

Errors: `E_BAD_PARAMS` (`slot` missing/out of range, `count` outside `1..64`), `E_PRECONDITION`
(empty slot — or `cannot determine` when the container has not caught up, guard refusal), `E_TIMEOUT`,
`E_UNSUPPORTED`.

#### `use.item` — use the hand-held item

| | |
|---|---|
| Params | `hand` (string, default `"main"`; `main` or `off`) |
| Side effects | `player.state`, `player.inventory` (a use changes the "using" state and may consume the item) |
| Preconditions | `permitted-session`, `flag: allow-mutate` |

Result: `dispatched`, `hand`, `heldBefore`, `heldAfter`, `usingBefore`, `usingAfter`,
`cooldownTicks`, three verdict objects, `note`.

`heldBefore`/`heldAfter` report the item of the hand the ticket **asked for** (`hand:"off"` reports the
off hand), so the receipt can be checked against `state.query{what:["offhand"]}` — which exists for
exactly that reason.

**This receipt claims that the action was dispatched, and nothing more.** `applied.dispatch` says
the use was handed to the client; the **effect** of the item is decided by the authority and is
reported under `notClientVerifiable.effect`. The `note` says so in words. If the guard reports a
no-op (paused / handshake / not in a world) the op executed, `dispatched:false`,
`verdict:"notDispatched"`, and nothing reaches the client. The item's *world* interaction
(`useItemOn`) is **not** part of this op — see the scope note above.

Errors: `E_BAD_PARAMS` (`hand` outside `main|off`), `E_PRECONDITION` (already using an item, empty
hand — or `cannot determine` when the inventory has not caught up —, the held item is on cooldown —
the message carries `cooldownTicks=<n>`, and only a **positive** value the client actually reports
refuses, so an adapter that does not expose cooldowns is never blocked by a check it cannot answer —,
guard refusal), `E_TIMEOUT`, `E_UNSUPPORTED`.

#### `shot.capture` — capture one rendered frame and report its bytes

| | |
|---|---|
| Params | `name` (string, optional; the default is `shot-<opId>-<clockMs>-<sequence>`, so two captures — even two in the same millisecond — cannot collide) |
| Side effects | `telemetry.recording` (tier 1 — **never gated by the injection policy**) |
| Preconditions | none |

Result: `bytes` (length), `format`, `width`, `height`, `sha256`, `tick`, `path`, and the fixed
sentence

> `bytes were captured; image content is NOT interpreted`

**The receipt contains byte facts only.** It **MUST NOT** contain any judgement about the picture —
no "the scene looks right", no "the HUD is visible", no pass/fail on appearance. A consumer that
needs that has to look at the image itself; the op's contract ends at "these bytes exist, and here
is their digest".

Errors: `E_BAD_PARAMS` (`name` present but not a string), `E_PRECONDITION` (no frame is available:
minimised, paused, or not in a world), **`E_TIMEOUT`** (a frame was waited for and none was delivered
within the budget — *"waited for a rendered frame"*), `E_UNSUPPORTED` (this adapter does not
implement frame capture). **A timeout MUST NOT be reported as `ok:true` with an empty image.**

#### `bench.read` — sample this client's frame durations

| | |
|---|---|
| Params | `warmup_frames` (integer, default `60`) · `sample_frames` (integer, default `300`, range `1..600`) |
| Side effects | `telemetry.recording` (tier 1) |
| Preconditions | none |

Bounds: `sample_frames ≤ 600`, `warmup_frames + sample_frames ≤ 900` (a bounded number of frames —
roughly 15 s — so one op cannot occupy the client indefinitely). Violations are `E_BAD_PARAMS`.

Result — **all of these are required for the numbers to be interpretable**:
`warmupFrames`, `sampleFrames` (requested), `sampleCount` (actually measured, also emitted as
`actualSampleCount`), `overranWindow` (true when the adapter measured **more** frames than the
requested window), `windowMs`, `fpsMedian`, `frameMsP95`, `onePercentLow`, `units:{fps,ms}`,
`samplesPath` (a per-frame CSV under the bridge directory, so the numbers can be re-checked), `note`.
An adapter that measured **more** than the requested window is reported as measured — the requested and
the measured counts stay separate and `overranWindow` names the anomaly in a `note` clause — rather than
silently clamped or folded into a generic failure code.

Stated limits (the `note` carries them; they are part of the contract):

* these are **client-side frame durations only** — median fps, p95 frame time, 1 % low;
* **no GPU/vendor counters** are read (no GPU time, no driver statistics);
* the numbers are **not directly comparable across machines or scenes**, and a shorter window than
  requested is reported as such rather than silently presented as the full sample.

Errors: `E_BAD_PARAMS` (out-of-range/bounded parameters), `E_PRECONDITION` (no frames were sampled
at all: minimised, paused, or not in a world — and `sampleCount == 0` may never be reported as a
successful measurement), **`E_TIMEOUT`** (sampling did not complete within the budget),
`E_UNSUPPORTED`. **Numbers are never invented**: if the client did not measure it, the receipt does
not contain it.

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

Every value has two spellings, and **both are accepted on input**:

* the **canonical wire form** is the enum name in UPPERCASE. **This is what an executor emits** in the
  published catalog and what a ticket should carry;
* the **documented form** is dotted lowercase, used in the prose and tables of this document for
  readability.

`SideEffect.parse` accepts either spelling, case-insensitively and ignoring `.` / `_`, so a value copied
out of this document round-trips instead of being rejected as a bad parameter.

| Canonical wire form | Documented form | Meaning | Mutating? |
|---|---|---|---|
| `NONE` | `none` | pure read | no |
| `TELEMETRY_RECORDING` | `telemetry.recording` | writes recording files under the bridge dir | no (does not touch the game) |
| `PLAYER_INPUT` | `player.input` | writes player input (movement/keys) | **yes** |
| `PLAYER_INVENTORY` | `player.inventory` | moves items | **yes** |
| `PLAYER_STATE` | `player.state` | pose/health/gamemode/etc. | **yes** |
| `WORLD_BLOCKS` | `world.blocks` | places/breaks blocks | **yes** |
| `WORLD_ENTITIES` | `world.entities` | spawns/removes/kills entities | **yes** |
| `SERVER_COMMAND` | `server.command` | runs a server command | **yes** |
| `RENDER_PIPELINE` | `render.pipeline` | changes renderer/shader state | local-only, see below |

An executor MUST treat any value other than `NONE` / `TELEMETRY_RECORDING` (documented `none` /
`telemetry.recording`) as **mutating**.

`RENDER_PIPELINE` is the documented nuance. It is not a read, so it still needs the executor's mutating
opt-in, but it changes **local rendering only** — it cannot reach another machine's world — so the §7.2
injection gate puts it on the tier-1 (never blocked) side. The tier-1 set is an explicit allow-list, and
every value of the vocabulary is classified by it.

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

The rule is **default deny, plus an explicit allow-list of hosts you own**. A compliant executor
**MUST** implement three tiers, in this order:

1. **Read-only ops are always allowed.** An op whose `sideEffects` contain only `NONE` /
   `TELEMETRY_RECORDING` / `RENDER_PIPELINE` (documented `none` / `telemetry.recording` /
   `render.pipeline`) is never blocked by this policy, on any host: reading is not the risk, and a
   render-pipeline change is local to this client. That read-only set is an explicit **allow-list with
   everything else denied** — a value not in it is a write, so a vocabulary value added later fails
   **closed** (gated) instead of inheriting tier 1 by default.
   "Read-only" is decided by the side-effect vocabulary, **not** by "is it player input": an op that
   declares `player.inventory` / `player.state` / `world.blocks` / `world.entities` /
   `server.command` is a write even when it touches no input axis (`inv.click`, `inv.toss`,
   `use.item` are the reference cases) and **MUST** pass tiers 2 and 3 like any other write.
2. **Writing to the player is off by default.** It requires (a) the executor to have been started
   with an explicit development opt-in and (b) an **activation token supplied out-of-band by the
   operator, with an expiry**. A ticket can never activate anything, and an expired token stops
   working immediately.
3. **With input armed, a session is usable only if it is yours:** either a **single-player**
   (integrated-server) world, or a host the operator **explicitly declared** as theirs
   (`localhost`, `127.0.0.1`, a self-hosted dev server) through configuration — never through the
   ticket. Every other host **MUST** be refused with `E_PRECONDITION`. An empty allow-list is the
   default and means "refuse every non-single-player session".

**Loud audit.** Every tier-3 allowance **MUST** be recorded — who acted, which host, at what time,
which token, which op — in a durable log. Never log the token *value*: record a fingerprint
(e.g. the first bytes of its SHA-256) instead. Every write op — `input.set`, `inv.select`,
`inv.click`, `inv.toss`, `use.item`, `pose.set`, `world.place` — goes through this one gate, so an
allowance always produces **exactly one** audit line. **A refusal that happens *before* the allowance
produces no line at all** (a refusal is not an allowance, and recording it would bury the real ones):
that covers guard refusals (policy, activation, host, missing `allow-mutate`) and parameter validation.
**An op precondition that fails *after* the guard allowed does have an allowance, and its line MUST
exist**: an empty slot, a missing container, a cooldown or an occupied cell is discovered while the op
is already running, so denying its line would hide a write the guard really permitted. In short: the
test is not "did the op fail?" but "did the guard hand out an allowance?". A no-op
(paused / handshake / not in a world) writes nothing, so it is not an allowance either.

Mutating ops in general (`sideEffects` beyond `none`/`telemetry.recording`) **MUST** additionally
require the executor's explicit `allow-mutate` opt-in, and fail with `E_PRECONDITION` without it.
Ops that may run on a declared host SHOULD declare the `permitted-session` precondition (§6.3);
`singleplayer` remains the strictly-local variant.

**Real-machine acceptance criteria — every write op, three paths.** The gate is verified by three
receipts, never by reading the source. The guarded ops are `input.set`, `inv.select`, `inv.click`,
`inv.toss`, `use.item`, `pose.set` and `world.place`:

| Path | Receipt | Audit log | Client |
|---|---|---|---|
| **allowed, effect observed** | `ok:true`, and the op's declared effect really happened (`state.query` before/after, an observed read-back difference, `observedDelta`) | **exactly one** `ALLOWED-MUTATION` line: who, which host, when, which op, and the **8-character token fingerprint** | the write reaches the client **exactly once** |
| **allowed, then an op precondition fails** (empty slot, no container, cooldown, occupied cell, `cannot determine`) | `ok:false`, `error.code:"E_PRECONDITION"` | **exactly one** line — the allowance really was granted | the precondition is discovered **while running**, so nothing further is written |
| **refused by the guard or by parameter validation** | `ok:false`, `error.code:"E_PRECONDITION"` (guard) / `E_BAD_PARAMS` (params) | **no line at all** (no allowance was granted) | **nothing** reaches the client |
| **no-op** (paused / handshake / not in a world) | `ok:true` (`ok` means the op executed), `verdict:"skipped"`, `skipped.dispatch.reason` starting `guard no-op:` | **no line at all** | **nothing** reaches the client |

Across all four paths the audit line **MUST NOT** contain the token *value* — only its fingerprint.

**Rationale.** The question that matters is **"is this a server you own?"**, not **"is it
remote?"**. A blanket remote refusal is at once too strict — it breaks the supported local
multi-instance workflow (a development server you started yourself) — and too blunt, because it
never asks about ownership. Declaring hosts is the operator's explicit statement of ownership, and
the empty default keeps the posture fail-closed.

**Also required:** refuse unsupported ops with `E_UNKNOWN_OP` / `E_UNSUPPORTED` rather than silently
skipping; never bypass authentication or server checks; never modify the client's network layer;
and log every executed op (ticket, op id, name, outcome) so a run stays auditable.

### 7.3 Build variants (guarded / unguarded)

The reference implementation ships **one source tree** and two artifacts:

| Variant | How it is produced | Behaviour |
|---|---|---|
| **guarded** — default, and the only one released | `./gradlew :core:jar` | Enforces §7.2 in full |
| **unguarded** — self-compiled, never distributed as a download | `./gradlew :core:jar -Punguarded` | **Bypasses** the injection policy; every decision says so |

An unguarded artifact **MUST** identify itself, so that using it leaves a trace:

* its jar manifest carries `Modtest-Guard-Variant: guarded|unguarded`;
* a version/startup line reports the variant (`… guard=GUARDED` / `guard=UNGUARDED`), and an
  unguarded build logs an explicit warning that the policy is disabled;
* the implementation re-reads the manifest at runtime; an **unstamped** artifact is treated as
  **guarded** (fail closed).

The unguarded variant exists for an operator's own local iteration on hardware they control. See the
repository README for the responsibility statement that comes with it.

*(The previous flat wording — "refuse remote sessions" / "default to read-only" — is superseded by
the three tiers above; a flat remote refusal was both too strict and imprecise about ownership.)*

### 7.4 Crash and restart behaviour

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
- [ ] keep the write ops (`input.set`, `inv.click`, `inv.toss`, `use.item`) behind the one guard:
      one audit line per allowance, none for a refusal, never the token value
- [ ] answer `E_TIMEOUT` (never `ok:true`) when a frame or a sampling window does not arrive, and
      never invent numbers for `bench.read` (§6.2c)
- [ ] keep world interaction (`useItemOn`, block placement/interaction) answering `E_UNSUPPORTED`
      until it is designed and declared — it is deliberately out of scope in this batch
- [ ] include `protocol` in both directions and answer `E_PROTOCOL` on mismatch
- [ ] (recommended) publish `catalog.json` and validate `params`/`result` against it

The reference implementation of the *agent side* is `src/modtest_mcp/server.py`; the offline
conformance smoke test is `src/modtest_mcp/smoke.py` (`MCP-SMOKE-ALL-PASS`).
