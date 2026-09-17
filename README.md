# modtest-mcp — a development harness for Minecraft mod authors (MCP)

> **Status: EARLY PREVIEW — executor present, real-machine verified, not for production use.**
> This repository ships the **agent side** (the MCP server, the `modtest-bridge/1.0` specification
> and the analysis tools) **and** the game-side executor: `core/` (pure JVM, unit-tested) plus the
> Forge 1.20.1 adapter in `forge/` — see [`docs/STAGE2-TODO.md`](docs/STAGE2-TODO.md). It drives a
> **local development client** in a single-player test world or on a server you own. Real-machine
> coverage is listed honestly in
> [§9](#9-current-state-verification-status-and-known-limitations) — including what is **not**
> verified. This is still an early preview and the wire format may still change before 1.0.
> The wire format is published for review and **may still change before 1.0**; expect breaking
> changes between preview releases, and pin a commit if you build against it.
> The Java side is built from one source tree in two variants — a **guarded** default and a
> self-compiled **unguarded** one; see [§8](#8-guarded-and-unguarded-builds--read-this-before-building-from-source).
> **Versions:** tag `v1.0.0` marks the **first preview drop** (there is no stable release yet —
> current version line is `1.0.0a1` / `1.0.0-alpha.1`). The **protocol version**
> (`modtest-bridge/1.0`) and the **product version** are independent: the protocol can stay at 1.0
> while the tool is still a preview.

`mcp-name: io.github.minatoAI/modtest-mcp`

modtest-mcp is a **developer tool**: it drives a **local development client** in a
single-player test world so that mod authors and CI can reproduce rendering, lighting and
game-state bugs deterministically. It speaks the **Model Context Protocol (MCP)** over stdio, so
an agent or a plain CLI can send scripted **tickets** (JSON) and read back structured
**telemetry** (JSON receipts) — no key/mouse automation, no screen scraping required.

The wire format is a small, versioned file protocol: **`modtest-bridge/1.0`** — see
[`docs/PROTOCOL.md`](docs/PROTOCOL.md). This repository contains the *agent side* of that
protocol (the MCP server) plus a set of mod-agnostic analysis tools for the artifacts a test run
produces (screenshots, frame recordings, logs).

**Not for multiplayer.** It is not a gameplay bot, cheat, or client modification intended for
competitive or public servers. It contains **no combat automation, no aim assistance, no
movement cheats, no X-ray, and no duplication**. Use it only on worlds/servers you own or
administer.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
It requires a legitimate Minecraft account; no authentication or server checks are bypassed.

**Scope (what it is for):** mod development, rendering/lighting regression checks, deterministic
bug reproduction, telemetry collection on a local single-player test world, and CI smoke tests.

---

## 1. What is in the box

| Path | What it is |
|---|---|
| `docs/PROTOCOL.md` | **The specification** (`modtest-bridge/1.0`): directory layout, ticket/receipt schemas, atomic-write rules, error codes, versioning, op catalog with JSON-Schema op descriptions, safety requirements |
| `src/modtest_mcp/server.py` | MCP server (stdio JSON-RPC, `Content-Length` framing, **standard library only**) exposing `drop_ticket`, `read_receipt`, `list_queue` |
| `src/modtest_mcp/smoke.py` | Offline conformance smoke test (no game, no network): prints `MCP-SMOKE-ALL-PASS` |
| `tools/` | Mod-agnostic analyzers: image diff, luminance stats, grid diff, paired A/B analysis, frame-recording analyzer, crop/zoom, style metrics, RCON client (parameters only, no default credentials) |
| `examples/` | How a mod-side executor plugs in: a sample `catalog.json` and a worked example of an op provider |
| `docs/STAGE2-TODO.md` | Roadmap: Java-side executor modules (relay / input injection / ticket executor / op-provider SPI) and their safety requirements |

## 2. Install and run

```bash
# 1) run straight from a checkout (no install needed)
python src/modtest_mcp/server.py --dir ./.modtest-agent

# 2) or as an installed console script
pip install .
modtest-mcp --dir /path/to/bridge

# 3) or via uvx (no venv needed)
uvx modtest-mcp --dir /path/to/bridge
```

Register it with an MCP host (Claude Desktop, Cursor, …):

```json
{
  "mcpServers": {
    "modtest": { "command": "uvx", "args": ["modtest-mcp", "--dir", "/path/to/bridge"] }
  }
}
```

Offline self-check (recommended first step):

```bash
python src/modtest_mcp/smoke.py        # -> MCP-SMOKE-ALL-PASS
```

## 3. The three tools

| Tool | Arguments | Result |
|---|---|---|
| `drop_ticket` | `{ticket, payload:{protocol?, trial?, timeout_ms?, on_error?, ops:[{id?, op, params?, expect?}]}}` | `{ok, accepted, path, protocol, ops:[id…]}` |
| `read_receipt` | `{ticket, timeout_s?}` | the receipt object (or `{ok:false, error:{code:"E_TIMEOUT"}}`) |
| `list_queue` | `{which: "inbox"\|"outbox"\|"done"}` | `{ok, which, files:[…]}` |

Tickets are written **atomically** (`.json.tmp` → rename), so the game-side executor never sees a
partial file. If `<dir>/catalog.json` exists, the server validates op names and required params
*before* the ticket is sent — typos fail fast instead of hanging a test run.

## 4. How to plug in your own mod

modtest-mcp deliberately contains **no game code**. A mod-side executor implements the other half
of the protocol:

1. **Poll** `<BRIDGE_DIR>/inbox/` (2 Hz is a good default for a client tick loop); ignore `.tmp`.
2. **Parse + validate** the ticket, then execute ops **in order** against your client.
3. **Write exactly one receipt** to `<BRIDGE_DIR>/outbox/<ticket>.result.json`, archiving any
   previous receipt instead of overwriting.
4. **Publish a catalog** at `<BRIDGE_DIR>/catalog.json` describing your ops with
   `paramsSchema` / `resultSchema` / `preconditions` / `sideEffects` (§6 of the protocol).
5. **Enforce the safety requirements** (§7.2): refuse mutating ops when the client is on a remote
   server, and require an explicit `allow-mutate` opt-in. Read-only ops stay available.

`examples/` shows a minimal catalog and the op-provider shape Stage 2 will formalise. Stage 2 will
also ship a reference Java executor (relay + input injection + ticket executor + op-provider SPI)
for Minecraft clients; see `docs/STAGE2-TODO.md`.

## 5. Analysis tools

```bash
node tools/imgdiff.js before.png after.png --json      # pixel diff + changed-pixel ratio
node tools/lumastats.js shot.png                       # luminance histogram / clipping
node tools/rec-analyze.js session/ --json              # strict frame-recording analyzer
pwsh tools/paired-analyze.ps1 -Pairs a1,a2 -Pairs b1,b2 # paired A/B drift analysis
pwsh tools/rcon.ps1 -ServerHost 127.0.0.1 -Password (Read-Host -AsSecureString) -Command "list"
```

* All tools take paths as arguments — **no hardcoded absolute paths** anywhere in this repository.
* The `.ps1` tools that use GDI+ (`griddiff.ps1`, `stylemetrics.ps1`, `clusterprobe.ps1`) are
  **Windows-only**; the `.js` tools are cross-platform (Node ≥ 18).
* `rcon.ps1` has **no default host and no default password**: both must be supplied (or read from
  `MODTEST_RCON_HOST` / `MODTEST_RCON_PASSWORD`).

## 6. Known limitations (read before trusting output)

* **Stage 1 ships the agent side + protocol only.** There is no reference game-side executor yet,
  so a fresh clone cannot drive a client until you implement (or wait for) Stage 2.
* The analyzers assume the artifact *shapes* they document (image sizes, CSV headers); they are
  not universal parsers. `rec-analyze.js` takes the recorder's header signature as a parameter.
* `expect` assertions implement the v1.0 subset only (nine comparison ops, no nested boolean logic).
* No authentication on the bridge directory: it is a **local** protocol. Keep `<BRIDGE_DIR>` off
  network shares and out of synced folders.
* Performance numbers, if any appear in issues or examples, are **machine-specific measurements**,
  not guarantees.

## 7. License

GPL-3.0-or-later — see [`LICENSE`](LICENSE). Third-party attributions and the "ideas only, no
verbatim copying" statement for prior art are in [`THIRD_PARTY.md`](THIRD_PARTY.md).

## 8. Guarded and unguarded builds — read this before building from source

The Java side is built from **one source tree** and can be compiled in two variants. The difference
is the injection guard described in [`docs/PROTOCOL.md`](docs/PROTOCOL.md) §7.2–7.3.

| Variant | Command | What it does |
|---|---|---|
| **guarded** (default) | `./gradlew :core:jar` | Enforces the policy: **default deny**, read-only ops always allowed, input injection needs a dev flag + an unexpired activation token, and a host must be either single-player or **explicitly declared by you**. Every allowance is logged loudly. |
| **unguarded** | `./gradlew :core:jar -Punguarded` | **Bypasses the injection policy entirely.** Any host, no token requirement. |

**Both variants identify themselves**, so a build can never be passed off as the other one:

* the jar manifest carries `Modtest-Guard-Variant: guarded` or `unguarded`;
* the version line reports it — `modtest-harness-core 1.0.0-alpha.1 guard=GUARDED`;
* an **unguarded** build logs a warning at startup: *"UNGUARDED BUILD: the injection policy is
  disabled…"*;
* an artifact with no stamp at all is treated as **guarded** (fail closed).

We document the unguarded build instead of hiding it, because the value of the guard is in its
**defaults and its audit trail**, not in pretending source code can be made un-editable. But be
clear about what you are switching off:

> **Responsibility.** The unguarded variant is intended **only** for single-player worlds and for
> test servers **you own or administer** — for example a local development server you started
> yourself. It **must not** be used on public servers, on servers you do not own, or in any
> competitive setting. If you compile or run the unguarded variant, **you** are responsible for
> where it runs and for the consequences; the project's maintainers provide it as documented source
> for local development and take no responsibility for its use. All released artifacts of this
> project are the **guarded** variant; the unguarded variant is source-only and is not offered as a
> download.

---

## 9. Current state, verification status and known limitations

### 9.1 Versions and artifact identity

| | |
|---|---|
| product version | `1.0.0a1` (Python/MCP server) / `1.0.0-alpha.1` (Gradle) |
| protocol version | `modtest-bridge/1.0` (independent of the product version) |
| guarded jar | `modtest-harness-forge-1.0.0-alpha.1.jar` — 122,843 B, **84 entries**, sha256 `CD47C676D360260FF4087E812848A94BDC48F1FCB6310775991744BDC052DE6A`, manifest `Modtest-Guard-Variant: guarded` |
| unguarded jar | `modtest-harness-forge-1.0.0-alpha.1-unguarded.jar` — 122,890 B, **84 entries**, sha256 `73C28368A6DB97CBBD55F7CB06FB2C9D45A3857245022B48E8737AD09E495421`, manifest `Modtest-Guard-Variant: unguarded` |
| packaged `:core` classes | **67** (enforced by the `verifySelfContainedJar` guardrail) |
| also inside each jar | `pack.mcmeta` (pack_format 15) and `modtest.refmap.json` (Mixin AP output) |

**We do not claim byte-reproducible jars.** Building the same input twice produces different
sha256 values (zip entry timestamps), so a sha identifies *one* build's output only. Integrity rests
on the **entry count**, the **four guardrails** (`verifyMixinRefmap`, `verifySelfContainedJar`,
`:core:verifyGsonApiSurface`, and the `pack.mcmeta` assertion) and the **content**, not on the hash.

### 9.2 Real-machine verification (honest list)

Verified on a real 1.20.1 client (Forge, JDK 17) **by the harness test runs**; the per-round
evidence is in `docs/agent-harness/task55-index.md` and the `task55-round*-report.md` reports. Both
variants were exercised:

1. **Input is actually taken up** — an injected forward command moves the player (closed loop:
   before/after `state.query` plus the server log).
2. **Refusal by default, and each allowance path works**: injection is off unless the dev flag *and*
   an unexpired token are present; single-player is allowed; an **undeclared** remote host is
   refused; a host declared in `MODTEST_ALLOWED_HOSTS` is allowed and audited.
3. **The five unimplemented ops keep refusing** (`inv.click`, `inv.toss`, `use.item`,
   `shot.capture`, `bench.read`) with `E_UNSUPPORTED` — a deliberate scope decision, not a defect.
4. **Both variants build** (4a) and the **Mixin really applies** (4b: `@At` injection resolved in
   the production (SRG) domain, zero mixin errors).
5. **End-to-end ticket loop closes** (ticket → receipt → archive).
6. **Unguarded variant: 4/4** of the above, with its own manifest stamp.

The defect chain found on real machines is **closed, each one first proven, then fixed, then
re-verified on a real client**: missing audit sink wiring, the Gson API mismatch
(`JsonObject.isEmpty()` vs runtime gson 2.10), the self-contained-jar failure, the missing refmap
`@At` target, the missing `pack.mcmeta`, the unreachable host allow-list
(`serverAddress()` never provided), the dishonest `pose.set` verdict (now three states, §9.3), and
the input-hold leak (`PENDING.set` re-arming on every write — now a single `InputHold` per ticket,
"one `ticks:N` request ⇒ exactly N writes").

### 9.3 `pose.set` reports three states — one word must never carry two meanings

`pose.set`'s receipt puts each requested field in **exactly one** of:

* **`applied`** — the client owns the field and the settled value matches the request (in practice:
  **rotation**);
* **`notClientVerifiable`** — an authority owns the field and the client **cannot witness** the
  outcome. The request was delivered; whether the server applied it is not observable from here.
  Each entry carries `requested`, `observedAtReadback` and a reason saying so;
* **`skipped`** — it did **not** take effect, or could not be decided (`reason` is
  `server-authoritative position`, `did not take effect`, or `not settled`).

**Session asymmetry (measured on a real client — see the evidence pointer in §9.2):** in
**single-player** the integrated server is authoritative too and
the position is pulled back in **< 0.57 s** — so a position change really does not stick. On a
**remote (LAN) session** the same request **applied and persisted: no revert within 16.4 s**, a
freshly connected client read the new position before sending any request, and the server log shows
the change. Reporting both as "skipped" would hide a working capability; reporting the remote one as
"applied" would claim something the client cannot see. Hence the third state.

`authority`, `note`, `poseSource` (`"client-readback"`) and `settled` are derived from the same
verdict, so they cannot contradict it. **`ticks` on an `input.set` receipt is the requested hold
length, not the number of writes performed** (the audit lines are the record of writes).

### 9.4 Known limitations and things we have **not** verified

* **Official-launcher byte-for-byte parity is not verified** — verification used a locally built
  client; we have not compared the official launcher's files byte by byte.
* **Pixel-level reading is not verified** — screenshot capture exists, but we make no claim that
  images are interpreted reliably; treat image-based assertions as unproven.
* **`localhost` and `127.0.0.1` do not match each other** in the host allow-list. This is a
  **fail-closed usability trap, not a security hole**: declare the exact form you connect with.
* **`wait.frames` is a synchronous no-op** in the current adapter — do not rely on it to advance the
  client.
* **No verification on public or third-party servers.** Everything above was verified on worlds and
  servers owned by the operator, on loopback/LAN.
* **The five `E_UNSUPPORTED` ops are a scope decision** (§9.2 item 3), not an unfinished accident.
* (Updated status: the earlier `pose.set` false-green is **fixed** — §9.3; the fixture-threshold
  branch that could mask resource leftovers was proven in both directions and is **not** an open
  item.)

### 9.5 Run constraints

A test instance is bounded on purpose: **one instance at a time**, **≤ 6 min per instance** (the
harness fixture defaults to 150 s), **≤ 25 min per round**, a **windowed 1280×800** client, and a
**forced teardown to `java=0`** before the round is reported. The only relaxation used during LAN
verification was allowing the `Minecraft` process name in the fixture's blocking-process check.

### 9.6 What this means for use

This is a **development harness for worlds and servers you own**. Two boundaries matter in practice:

* `pose.set` on a **remote** session reports position as `notClientVerifiable` — the change usually
  works, but the client cannot prove it. **Do not build assertions that depend on reading a
  position back on a remote session.**
* `pose.set` in **single-player** does **not** move the player: the integrated server reverts it.
  Use input injection (which does work) or run the check on a remote session you control.

Early preview: expect breaking changes before 1.0, pin a commit if you build against it, and read
[`docs/PROTOCOL.md`](docs/PROTOCOL.md) for the normative rules.

