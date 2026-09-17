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
> current version line is `1.0.0a4` / `1.0.0-alpha.4`; one release, written in each notation). The
> **protocol version** (`modtest-bridge/1.0`) and the **product version** are independent: the protocol can
> stay at 1.0 while the tool is still a preview.

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
* the version line reports it — `modtest-harness-core 1.0.0-alpha.4 guard=GUARDED`;
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
| product version | `1.0.0a4` (Python/MCP server) / `1.0.0-alpha.4` (Gradle) — **one release, two notations**; the same line is in `gradle.properties`, `pyproject.toml`, the MCP server's `__version__` and `mods.toml`, and a test fails if they disagree |
| artifact file names | `modtest-harness-forge-1.0.0-alpha.4.jar` / `…-1.0.0-alpha.4-unguarded.jar`. **They changed in this cut** (the version lines were aligned), so a jar's file name now matches its release |
| protocol version | `modtest-bridge/1.0` (independent of the product version) |
| packaged `:core` classes | **73** (enforced by the `verifySelfContainedJar` guardrail) |
| also inside each jar | `pack.mcmeta` (pack_format 15) and `modtest.refmap.json` (Mixin AP output) |
| executor contract identity | the executor id plus `executorVersion` in the catalog/receipt (`0.1.0` in the Forge adapter). **Deliberately independent of the product version line**: it names the executor/contract that produced a receipt, it is wire-visible metadata, and re-stamping it for cosmetic alignment would change already verified identities while the protocol stayed the same. Not a leftover — do not "tidy" it into `1.0.0-alpha.4`. |

**There are two artifact identities. They differ, on purpose, and neither one is wrong.**

**(a) The published asset — what a downloader gets.** Release `v1.0.0-alpha.2` was cut from `8720db0`,
*before* the P11 (real interaction path) and P12 (bounded sync window) fixes. This is an already-published
fact and it stays on the record:

| | |
|---|---|
| guarded jar (release asset) | `modtest-harness-forge-1.0.0-alpha.1.jar` — 156,554 B, **90 entries**, sha256 `9FD682D9C7241AA600F15EBAD2C74332E803AE1B461E262DEFDAF184AA79CDD2`, manifest `Modtest-Guard-Variant: guarded` |
| unguarded jar (same cut, not published) | `modtest-harness-forge-1.0.0-alpha.1-unguarded.jar` — 156,601 B, **90 entries**, sha256 `610C6AE91D794293955DAAE822966DFDDC104F17394C46FB43187CA36E15CE71`, manifest `Modtest-Guard-Variant: unguarded` |

**(b) What the current source revision builds.** The task-78 revision (the P11/P12/task-77 fixes **plus** the
block-query + two-tier-stop batch and the aligned version lines) builds:

| | |
|---|---|
| guarded jar | `modtest-harness-forge-1.0.0-alpha.4.jar` — **170,625 B**, **94 entries**, **362,557 B uncompressed**, sha256 `c288bcf18d0703835688295d77f4b6ae43108b26502dc7caa07ac9fc777af915`, manifest `Modtest-Guard-Variant: guarded` (316 B), `modtest.refmap.json` + `modtest.harness.mixins.json` present, **71 `m_*_` / 20 `f_*_` SRG references** (i.e. reobfuscated), **77** `:core` classes, `mods.toml` version `1.0.0-alpha.4` |
| unguarded jar | `modtest-harness-forge-1.0.0-alpha.4-unguarded.jar` — **170,672 B**, **94 entries**, sha256 `5dddc70871045768a3070b7536365e2f7793d191a42763903544ff9830ca6224`, manifest `Modtest-Guard-Variant: unguarded` (369 B) |

**This build is a different thing from the published asset, on purpose** (and the file names now say so —
they changed from `…-1.0.0-alpha.1.jar` when the version lines were aligned). Compared with (a) it has:
**+4 entries** — `StopRequest.class`, `StopRequest$Tier.class`, `ClientModel$StopResult.class`,
`VanillaOps$StopOps.class` — the new op `input.stop`, a new `state.query` block/moving reading, and the
aligned version strings. **Nothing was removed.** The guarded jar is **+14,068 B** (156,554 → 170,625),
which is the expected direction for a batch that adds an op, an interface method set and four classes; the
`84 productEntries / 85 harnessEntries` split and `77/77` packaged `:core` classes are asserted by the
guardrails on every build, so a wrong-shaped jar cannot be handed on. **Stale jars are not deleted by
Gradle** — after this build two old `…-1.0.0-alpha.1.jar` files were still sitting in `forge/build/libs`;
they were removed by hand, because "the newest file in `build/libs`" is not a safe way to choose an
artifact when the version line has changed.

**Entry-level comparison is still how you check a rebuild** (not sha256): same entry names, uncompressed
sizes and CRC32s means same content. Within **this** build the two variants differ by exactly **47 B**, and
that difference is **entirely `META-INF/MANIFEST.MF`** (316 B vs 369 B uncompressed): every other entry, and
its size, is identical. (The published pair from (a) differs by the same 47 B: 156,601 − 156,554.) A larger
gap than that is not a labelling difference — it means one of the two jars is not in its final state.
`build/libs` is written twice, so the shippable bytes are the **reobfuscated** ones: in this build
`build/libs/…-unguarded.jar` and `build/reobfJar/output.jar` are **byte-identical**
(`5dddc708…`), and both guardrail tasks assert that identity against `reobfJar`'s own output.

**Why the two identities differ — so a source build's sha is not a contradiction.** Two independent reasons:
1. **The code changed** (P11/P12, then this batch), so the current jars are bigger: **+14,068 B** guarded
   against the published alpha.3 asset, and **+1,386 B** against alpha.2 before that
   (158,771 → 160,157 → 170,625). Different content and a different entry count, by design.
2. **Jar bytes are not reproducible.** Zip entry timestamps make a rebuild of the *same* input produce a
   different sha256; even rebuilding `8720db0` would not reproduce `9FD682D9…`.

So compare by **entry count, entry CRC32s and content**, never by hashing a local build against the released
asset. **The next release will carry the artifacts measured above, and this section will then name
`c288bc…` (guarded) as the published identity — but only after the real-machine acceptance round passes.**
Until then those bytes are a **local, unpublished build**. The already-published `v1.0.0-alpha.3` asset keeps
the internal version string
`1.0.0-alpha.1` and the file name `…-1.0.0-alpha.1.jar` (a published fact); the current revision aligns all
version lines to `1.0.0-alpha.4` / `1.0.0a4`, so its jars are named `…-1.0.0-alpha.4.jar`.

**`build/libs` is written twice — verify and ship only the reobfuscated write (task-77, fixed and asserted).**
`:forge:jar` writes the jar with **official/mapped names and no SRG references**; `reobfJar` then *replaces*
it with the shippable bytes. An invocation that only ran `:forge:verifyMixinRefmap` /
`:forge:verifySelfContainedJar` used to stop after the first write, leaving a **non-reobfuscated** jar in
`build/libs` that nevertheless passed both checks. Measured: that state is **159,308 B with 0 `m_*_`/0 `f_*_`
references**, versus **160,157 B with 40/10** for the real artifact — an **849 B** gap, and a jar that would
not work against a real client. Note that both states contain `modtest.refmap.json` **and**
`modtest.harness.mixins.json`, so checking for the mixin products is *necessary but not sufficient*.
The trap has already cost us: a reduced (non-reobfuscated) jar was once reported as the canonical identity
(see the corrections in `docs/VERIFICATION-LOG.md`), and a real-machine round was stopped by the tester's
hash gate because the jar on disk did not match the reported one. **Fixed in the build graph:**
- both guardrail tasks now `dependsOn 'reobfJar'`, so a guardrail-only run ends with the final artifact;
- both guardrails additionally **compare the entry CRCs of `build/libs` against `reobfJar`'s own output**
  and fail if anything else produced those bytes (the mixin-product check is kept as a second assertion).

Acceptance (measured): `:forge:clean` + the two guardrails alone now leave **160,157 B / 40 `m_*_` refs /
entry-identical to the shipped jar**, and the same two guardrails **fail loudly** if the `reobfJar`
dependency is removed (`reobfJar/output.jar does not exist — reobfJar never ran, so the jar in build/libs
cannot be the shippable artifact`). Always `:forge:build` (or `:forge:build -Punguarded`) before handing a
jar to anyone, and **re-measure the identity after every rebuild**.

**We do not claim byte-reproducible jars.** A sha identifies *one* build's output only. Integrity rests
on the **entry count**, the **four guardrails** (`verifyMixinRefmap`, `verifySelfContainedJar`,
`:core:verifyGsonApiSurface`, and the `pack.mcmeta` assertion), the **reobfJar identity assertion** and the
**content**, not on the hash.

### 9.2 Real-machine verification (honest list)

Verified on a real 1.20.1 client (Forge, JDK 17) **by the harness test runs**; the per-round
evidence is in `docs/agent-harness/task55-index.md` and the `task55-round*-report.md` reports. Both
variants were exercised:

1. **Input is actually taken up** — an injected forward command moves the player (closed loop:
   before/after `state.query` plus the server log).
2. **Refusal by default, and each allowance path works**: injection is off unless the dev flag *and*
   an unexpired token are present; single-player is allowed; an **undeclared** remote host is
   refused; a host declared in `MODTEST_ALLOWED_HOSTS` is allowed and audited.
3. **The five ops are implemented and verified on a real client** (closing rounds R16–R20):
   `inv.click`, `inv.toss`, `use.item`, `shot.capture` and `bench.read` no longer answer
   `E_UNSUPPORTED`. They are **implemented in `:core`**, **wired in the Forge adapter**, and were
   exercised on a real 1.20.1 client together with the three older write ops that were newly brought
   under the guard (`inv.select`, `pose.set`, `world.place`): refusal paths behaved **6/6** and
   **7/7**, every allowed write op left **exactly one** audit line, and the token's literal value
   appeared **0** times in the logs. What each receipt may claim is now governed by the two rules in
   §9.3a.
4. **Both variants build** (4a) and the **Mixin really applies** (4b: `@At` injection resolved in
   the production (SRG) domain, zero mixin errors).
5. **End-to-end ticket loop closes** (ticket → receipt → archive).
6. **Unguarded variant: 4/4** of the above, with its own manifest stamp.

**Audit lines come in three granularities — count them per class, never across classes.** This is existing
design, not a gap, and a real-machine round mistook it for one (it counted only `ALLOWED-MUTATION`):

| Class | Ops | One line per | Tag |
|---|---|---|---|
| mutation | `inv.select`, `inv.click`, `inv.toss`, `use.item`, `pose.set`, `world.place`, **`input.stop`** | one **allowed, non-no-op op call** — exactly 1, no matter how the op then turns out | `ALLOWED-MUTATION` (one per call, so calling the same op twice leaves two lines) |
| injection | `input.set` | one **tick actually written**: the op installs a hold of `ticks:N`, and every held tick goes through the same gate again ⇒ **up to N+1** lines | `ALLOWED-INPUT` |
| recording | `shot.capture`, `bench.read` | **never** — 0 lines: they write inside the bridge dir / read the render pipeline and never touch the game | — |

Guard **no-ops** (paused / handshake / no world) and **refusals** produce **no** line by design (a no-op
still returns `ok:true` with `verdict:"skipped"`; a refusal returns `E_PRECONDITION`). So
`write ops ok:true ≠ ALLOWED-MUTATION count`: when a ticket contains an `input.set`, its lines are the
`ALLOWED-INPUT` family, and for `ticks:N` that is the number that proves exactly N ticks were written
(the P7 guarantee). Counting one tag over a mixed ticket will always appear to "lose" lines.

The defect chain found on real machines is **closed, each one first proven, then fixed, then
re-verified on a real client**: missing audit sink wiring, the Gson API mismatch
(`JsonObject.isEmpty()` vs runtime gson 2.10), the self-contained-jar failure, the missing refmap
`@At` target, the missing `pack.mcmeta`, the unreachable host allow-list
(`serverAddress()` never provided), the dishonest `pose.set` verdict (now three states, §9.3), and
the input-hold leak (`PENDING.set` re-arming on every write — now a single `InputHold` per ticket,
"one `ticks:N` request ⇒ exactly N writes").

A public, round-by-round log of these runs — including the claims we had to take back — is in
[`docs/VERIFICATION-LOG.md`](docs/VERIFICATION-LOG.md).

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

### 9.3a What a receipt may claim: three rules (found as P9/P10/P11 on a real client)

Defects of one family — a receipt, or a write, that asserts more than the client actually did. All
three rules now apply to every op:

1. **A negative conclusion requires a settled observation.** `verdict:"skipped"`, `slot N is empty`,
   `no container is open` and `no item in the hand` all assert that something did **not** happen or is
   **not** there. Container contents synchronise asynchronously (a join, a dimension change, or a
   click/toss dispatched a moment ago), so one read that has not caught up is indistinguishable from
   the real thing. When the adapter reports that its container view may still be catching up, the
   receipt answers **`cannot determine`** — `E_PRECONDITION` whose message begins with that exact
   phrase — instead of asserting emptiness, and an **unchanged** read-back is
   **`notClientVerifiable`**, never `skipped`. That was the P9 defect: `inv.click` reported `skipped`
   for a click that had in fact landed, which invites an agent to repeat an action that already
   worked. `skipped` now means exactly one thing — a guard no-op. Re-read (`state.query`) to establish
   the outcome.
2. **A self-reported field must not exceed what the client can witness.** `world.place` reported
   `placed:true` and `verdict:"applied"` unconditionally, derived from the request (P10). It now reads
   its own world back: `placed` is the observed state, `blockObserved` is the block id the client sees
   (`null` when the adapter has no block query), and the verdict is **`notClientVerifiable`**, because
   the authority decides whether to keep the block.
3. **A write must take the path a player would take (P11).** `world.place` used to write the world
   directly (`level.setBlockAndUpdate`), which nobody else can observe: on a real client that write
   produced **no KubeJS `BlockEvents.placed`** (the event type existed, the handler was registered, and
   a positive control did fire), so it was not equivalent to a player placing a block. It now goes
   through the client's real interaction entry (`MultiPlayerGameMode.useItemOn` → the server's own
   placement path). The consequences are enforced instead of hidden: the block **must be the item in
   the selected slot**, the target must be replaceable and **within the player's block reach (about 4.5
   blocks — move the player first instead of retrying a far target)**, and each failure is an honest
   `E_PRECONDITION` — the block is never conjured into the world. Selecting a hotbar slot likewise now
   tells the server (`ServerboundSetCarriedItemPacket`), because the server learns the carried slot only
   from that packet and would otherwise place the wrong item. Both requirements are also published in the
   catalogue's per-op `divergence` block and in the MCP tool description, so an agent reads them where it
   actually looks.

Also in this batch: `use.item{hand:"off"}` reports **that** hand's item in `heldBefore`/`heldAfter`,
and `state.query{what:["offhand"]}` exposes the off hand, so an off-hand dispatch can be checked from
the client instead of taken on trust.

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
* **A negative verdict needs a settled read.** `skipped`, `slot N is empty` and `no container is open`
  are only reported when the adapter's container view is settled. Inside the synchronisation window
  after a join, a dimension change, or a click/toss it just dispatched, the Forge adapter says so and
  the receipt reports `notClientVerifiable` (unchanged write) or `E_PRECONDITION` with the message
  `cannot determine …` and error detail `reason:"container-not-synced"` (state that cannot be judged)
  instead of asserting emptiness. `world.place` likewise reports `placed` from its own read-back
  (`blockObserved` carries the observed id) with verdict `notClientVerifiable` — the client's view of
  the block is not the authority's decision.
* **`E_PRECONDITION` carries two different meanings, distinguished only by a message prefix.**
  "This state could not be determined" and "the slot is empty" are the *same* error code; the only
  machine-readable discriminator is that the former's message **begins with `cannot determine`**. This
  is a recorded limitation of the error vocabulary — no new error field was added for it — so match
  the prefix, and judge receipts by their **assertion form** (the `verdict` value and which of the
  `applied` / `notClientVerifiable` / `skipped` objects is populated), never by whether some string
  happens to occur in the message.
* **`pose.set` is a scripted divergence, and it is the only one left.** It writes the client's own pose
  (`moveTo` / `setYRot` / `setXRot`) and there is **no packet-equivalent player path to take, because a
  player cannot teleport** — so unlike `world.place` and `inv.select` (which were moved onto the real
  player paths, §9.3a rule 3) it cannot be made "like a human" and is not pretending to be. Treat it as
  putting a client in position, with the three-state receipt saying what actually stuck. The other
  state-writing ops were audited and are not divergent: `input.set` writes the very input state the
  keyboard feeds the game, `inv.click`/`inv.toss` use the vanilla container-click path, `use.item` uses
  the vanilla use path.
* **"Unknown" is a first-class answer, and it is never "air" (A).** `state.query{what:["block"],x,y,z}`
  reports `blockKnown:false` with JSON `null`s when the client cannot see the cell — an unloaded chunk, a
  `y` outside the build height, or an adapter with no block query. Vanilla's chunk API answers *air* for an
  unloaded chunk, so folding unknown into air would be a fabricated fact. The same channel answers
  `blockReplaceable` (`canBeReplaced()`), so "may I place here?" is verifiable with our own ops instead of a
  KubeJS probe; `what:["moving"]` adds a cheap movement bit whose basis is the player's own delta movement
  (displacement, **not** a request: `moving:false` right after `input.set` is not evidence of failure).
* **A stop is a cancellation, never a renewal (C, the P7 lesson).** `input.stop{mode:"immediate"|"safe"}`
  cancels injected movement now or at the first safe point (on ground, outside a wall), bounded by `ticks`.
  After a stop the hold cannot write again and no tick budget survives; the receipt reports `armed:true` +
  `stopped:false` + `verdict:"notClientVerifiable"` when it could only arm the stop — it never claims a
  player has stopped who has not.
* **Four codes are "non-failure terminations", not errors (C).** `E_SUPERSEDED`, `E_STOPPED`, `E_NO_PATH`
  and `E_STUCK` mean *the task did not complete and that is not a defect*. `E_STOPPED` (nothing was moving
  to stop) and `E_SUPERSEDED` are produced by `input.stop`; `E_NO_PATH`/`E_STUCK` are **declared but
  reserved** for the movement planner (`walk.within`) and nothing produces them yet. Existing codes keep
  their exact meaning, and no error field was added for the distinction.
* **`E_SUPERSEDED` is narrow, and an `input.set` that replaces a hold is *not* it.** A real-machine round
  sent a second `input.set` while a hold was still running and got `ok:true`, then read that as "the code
  never fires". That receipt is **correct**: the superseded party is the *old hold*, whose op already
  answered, and only a **stop request** can be superseded in a way a later receipt can report. The one
  reachable trigger is `input.stop{mode:"safe"}` armed while the player is **not** at a safe point → a newer
  `input.set` supersedes it → a further `input.stop` sees that **inside the same ticket** and answers
  `E_SUPERSEDED`. Across tickets the client-tick loop deliberately discards the supersession (so a stale
  stop can never cancel the command that now owns the player), which is why the code is narrow rather than
  impossible. Full wording: PROTOCOL §4.1 and §6.2g.
* **A "cannot determine" answer is bounded, never permanent.** The container sync window is counted in
  **client ticks** (20) and also closes as soon as the server's own answer arrives, so the plain
  refusals (`empty-hand`, `slot-empty`, …) are always reachable. The first version used wall-clock
  milliseconds and did **not** converge on a real client — an empty hand was still answered
  `container-not-synced` four seconds later, which left `empty-hand` unreachable (P12).
* **Replaceability is decided once, from the client's own view.** A target that cannot be replaced is
  refused with `E_PRECONDITION reason:"target-not-replaceable"`, and a *replaceable* non-air target
  (tall grass, a snow layer) is allowed, as it is for a player. The older `E_EXEC "cell occupied"`
  pre-check is gone: it ran before the interaction, hid the honest check and refused targets a player
  could place into. There is still no synchronisation signal for world blocks, so use `blockObserved`
  after the fact as the observation.
* **World changes persist across instances — pick fresh ground each round.** A block placed by an
  earlier run is still there in the next one, so a fixed target cell becomes "not replaceable" on the
  second run and a test that reused it looks like a regression when it is only stale ground. Likewise,
  after `inv.select` let the synchronisation window close (20 client ticks at most) before sending the
  placement that depends on it.
* **The off hand is observable now.** `state.query{what:["offhand"]}` reports the off-hand item and
  `use.item{hand:"off"}` reports *that* hand's item in `heldBefore`/`heldAfter`; before this, an
  off-hand use could not be checked from the client at all.
* **The task-70 ops have not had their real-machine pass yet.** `inv.click`, `inv.toss`, `use.item`,
  `shot.capture` and `bench.read` are implemented in `:core` with a full unit-test matrix, and the
  Forge adapter now wires them — but every claim about what they do to a *real* client is still
  unverified. The same applies to `inv.select`, `pose.set` and `world.place`, which now pass the
  write-op guard and are audited for the first time (§7.2 has the three-path criteria).
* **Frame telemetry on a real client is bounded by the frames already rendered.** The relay runs on
  the client/render thread, so `bench.read` reports the **most recent** rendered window
  (`sampleCount` may be shorter than requested; core says so in the note) rather than waiting for
  future frames — waiting there would deadlock the tick loop that drives the relay. `shot.capture`
  reads the framebuffer synchronously on that same thread (`Screenshot.takeScreenshot`); it never
  invents a frame, but it also cannot wait for a future one, so a "waited for a frame" `E_TIMEOUT`
  is not produced by this adapter (the per-op budget timeout still applies).
* **`RENDER_PIPELINE` is read-only for the injection gate but not for `allow-mutate`.** See §6.4:
  the two predicates differ by design, and the whole vocabulary is classified explicitly.
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

