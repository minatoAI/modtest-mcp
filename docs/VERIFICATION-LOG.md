# Real-machine verification log

This is the round-by-round record of how `modtest-mcp` was verified **on a real Minecraft 1.20.1
client** (Forge, JDK 17, windowed 1280×800, single-player worlds plus one locally hosted Forge
server). It is a log, not a brochure: it includes the runs that failed, the claims we had to take
back, and the numbers as measured. Everything here was produced by bounded test runs on one
operator's own machine; **nothing was simulated or copied from a green unit test**.

**A few words used throughout**

- **bridge protocol** — the file-based handshake: a test writes a JSON **ticket** (a request) into
  `inbox\`, the game executes it, and writes a **receipt** (the JSON result) into `outbox\`.
- **guard** — the three layers that must all agree before any input injection happens: the *dev flag*,
  an unexpired *activation token*, and a session that is either *single-player* or a *declared host*.
- **`ALLOWED-INPUT` line** — the audit line the game prints whenever the guard lets a write through.
  It names the executor, host, dimension, time, an **8-character token fingerprint** (never the token
  itself), the op, the value actually written, and the reason.
- **`E_UNSUPPORTED` / `E_PRECONDITION`** — machine-readable refusal codes: "this op is deliberately
  not implemented" and "a precondition (e.g. the guard, or the allow-mutate flag) is not satisfied".

## At a glance

| # | Acceptance item | Verdict |
|---|---|---|
| 1 | Injected input is *actually taken up* by the client | **PASS** — control drift 0; movement follows the facing direction; open-path magnitude reproducible |
| 2 | Refusal/allowance semantics (single-player, undeclared remote host, whitelisted host) | **PASS** on both sides after fixes (remote refusal leaves the player provably unmoved) |
| 3 | The five unimplemented ops keep refusing | **PASS** — all five return `E_UNSUPPORTED`, none implemented |
| 4a | The adapter compiles and loads as a mod | **PASS** |
| 4b | The Mixin really applies in the production (obfuscated) domain | **PASS** |
| 5 | End-to-end ticket loop closes (ticket → execution → receipt → archive) | **PASS** — 60+ tickets |
| 6 | The "unguarded" build identifies itself and still logs | **PASS** — 4/4 criteria |

**Defect chain, one line each (every one was first proven, then fixed, then re-verified on a real client):**

| ID | One-line conclusion |
|---|---|
| P0 | The jar announced a refmap it did not contain → Mixin failed, the client crashed at start. |
| P0b | The refmap translated method names but not the `@At` target → injection check failed 0/1. |
| P0c | The production jar was not self-contained (the core classes were not inside) → `NoClassDefFoundError` at construction. |
| P0d | The jar had no `pack.mcmeta` → Forge showed a **blocking** "mod loading warnings" screen, so automation stalled. |
| P0e | Core was compiled against gson 2.10.1 while the game ships 2.10 → first allowed injection died with `NoSuchMethodError`. |
| P1 | The "loud by design" audit line was silently wired to a no-op sink → allowances left **no trace at all**. |
| P2-a | The receipt field `command` was the *queued* value, not the *written* one → renamed + a `writtenCommand` added. |
| P2-b | Guard refusals were reported as `ok:true` → unified to `ok:false` + stable code; "`ok:true` means the op really ran". |
| P4 | The host allow-list could never match (`serverAddress()` was never provided by the adapter) → the allow path was dead code. |
| P5 | `pose.set` reported success for a teleport that had already been rolled back → now three honest states. |
| P7 | A `ticks:20` (one second) hold was **self-renewing**: the player walked ~7.96 blocks over ≥11.8 s. |
| P8 | On a remote server the teleport *did* work and persist, yet the receipt called it "not applied" → split into three states. |

---

## R1 · 2026-09-17 — first real launch

- **Goal:** does the mod load and does an op execute at all?
- **Saw:** the client refused the jar: `Reference map 'modtest.refmap.json' … could not be read`,
  then `InvalidInjectionException … could not find any targets matching 'aiStep' … No refMap loaded`.
- **Found:** **P0** — the manifest advertised a refmap that was not inside the jar.
- **Fixed:** package the refmap (task-57).
- **Re-verified:** the refmap now loads; the next error surfaced, so the round continued.

## R2 · 2026-09-17 — two more layers peeled off

- **Goal:** make the Mixin actually inject.
- **Saw:** `failed injection check, (0/1) succeeded. Scanned 1 target(s)`. Using `javap` on the
  production client jar we measured `aiStep` offset 139 → `invokevirtual … Input.m_214106_:(ZF)V`,
  i.e. the refmap translated the method name but **not** the `@At` target. Next: construction failed
  with `NoClassDefFoundError: io/github/minatoai/modtest/core/Guard$SessionState` — 0 `core/**`
  entries inside the jar versus 64 classes on disk.
- **Found:** **P0b** (unmapped `@At` target) and **P0c** (the jar was not self-contained).
- **Fixed:** generate the refmap with the annotation processor (task-59); merge the core classes into
  the jar (task-60).
- **Re-verified:** injection applied with zero mixin errors; the mod constructible.

## R4 · 2026-09-17 — blocking UI, then a runtime crash

- **Goal:** run the acceptance items end to end.
- **Saw:** Forge opened a **blocking** "mod loading warnings" screen (screenshot kept) because the jar
  had no `pack.mcmeta`. After adding one, the first *allowed* injection crashed with
  `NoSuchMethodError: 'boolean com.google.gson.JsonObject.isEmpty()'`, proven by `javap` to exist in
  the compile-time gson (2.10.1) but not in the runtime gson (2.10) the game ships.
- **Found:** **P0d** and **P0e**.
- **Fixed:** ship `pack.mcmeta`; compile against the gson the game actually ships and add a guardrail
  that fails the build if the core uses an API the runtime lacks (task-61).
- **Re-verified:** the client starts with the **default** `showLoadWarnings=true` and no workaround
  left in place; allowed injection no longer crashes.

## R5 · 2026-09-17 — the acceptance items, and the first silent security downgrade

- **Goal:** items 1/3/5 plus the guard's allowance/refusal paths.
- **Saw (raw):** control drift `q2−q1 = 0`; after an injected forward command the player moved
  `dz = −4.770794473859645` along the facing direction. The receipt said
  `allowed=true, reason="single-player world"` — **but the game log contained 0 occurrences of
  `ALLOWED-INPUT`**. Also: 5 single-op tickets (`u1`..`u5`) all returned `E_UNSUPPORTED`, and the
  ticket loop closed.
- **Found:** **P1** — the production wiring used the 3-argument `GuardedInputWriter`, whose sink is a
  no-op; only the 4-argument overload carries an audit sink. **"Allowed an injection" left no trace.**
- **Fixed:** make the sink mandatory and route production through the same audited factory the tests
  use (task-62).
- **Re-verified:** **0 → 196** `ALLOWED-INPUT` lines for one allowance, and the literal token value
  appears **0** times (only the 8-character fingerprint `b0fcb991`).

## R7 · 2026-09-17 — the negative half of the same fix

- **Goal:** prove the *refusal* path leaves no audit line.
- **Saw (raw):** with the dev flag off and no token, `input.set` returned
  `allowed:false, reason="injection is off by default (dev flag not set)"`, and `ALLOWED-INPUT`
  = **0** lines while the world stayed healthy.
- **Found:** the fix had the right shape in both directions — and a second encoding problem: this
  refusal came back as `ok:true`, while the `allow-mutate` refusal came back as `ok:false`.
- **Fixed:** **P2-b** — unified to `ok:false` + a stable error code, with the rule "`ok:true` means
  the op really executed" written into the protocol.

## R8 · 2026-09-17 — item 6 (unguarded build) and the LAN half

- **Goal:** verify the unguarded variant and the multiplayer decision path.
- **Saw (raw):** unguarded build — manifest stamp `unguarded`, startup line `guard=UNGUARDED`, and
  with **no token at all** the injection was allowed (`ALLOWED-INPUT` **197** lines, `token=none`).
  LAN: single-player refusal `E_PRECONDITION`, and against a locally hosted Forge server the receipt
  refused the injection and the player **did not move** (`dx=dy=dz=0`, 0 audit lines) — corroborated
  by the server log. Then, with `MODTEST_ALLOWED_HOSTS=127.0.0.1:25585`, the injection was
  **still refused**.
- **Found:** **P4** — the adapter never supplied `serverAddress()`, so the allow-list could never
  match; the "declared host is allowed" capability was dead code (safe, but dead).
- **Fixed:** provide the real address, compare **host only** (ignore port), and refuse explicitly when
  the address cannot be determined (task-64).
- **Re-verified:** `reason="explicitly whitelisted host: 127.0.0.1:25585"`, 397 audit lines, and the
  player really moved (`dz=+3.8426`).

## R9–R11 · 2026-09-17 — the teleport that lied, and the hold that never ended

- **Goal (R9):** re-verify `pose.set` after the first hardening; **(R11)** measure how fast a
  single-player position change is rolled back.
- **Saw (raw):**
  - R9: `pose.set{z:12}` returned `settled:true` and **all five fields applied**, yet a later read
    showed the position back at its old value. On the remote server the same receipt style hid a
    position that **had** worked.
  - R11: after `pose.set{z:12}`, the **first observable read (+0.57 s) was already back at the old
    value**, and stayed there for 4 s — while the **rotation persisted**. On the remote server, the
    new value **stayed for 16.4 s with no rollback** (confirmed later by a reconnect and by the server
    log line changing from `…, 17.60936942239352)` to `…, 12.0)`).
  - R9 also showed an injection request of `ticks:20` (one second) producing growing audit counts
    (**63 → 106 → 244** lines) and, in R11, **7.96 blocks of travel over ≥11.8 s**.
- **Found:** **P5** (the verdict was computed from a value read immediately after the teleport, i.e.
  before the authoritative update) and **P7** (the hold was re-armed on every write, so it renewed
  itself forever).
- **Fixed:** delete the two-reading rule, never report position as applied, and report three states
  (task-63/`5086455`); replace the atomics with a single `InputHold` that is *replaced* on install,
  decremented once per write, cleared on four paths, and attributed per ticket (`ee147e8`).
- **Re-verified (R12/R13):** single-player `applied={yaw,pitch}` with position `skipped`
  (`reason="server-authoritative position"`) and `dz = 0`; **`ticks:20` attributable audit lines
  = exactly 20**, **0** lines in read-only tickets after the window, and travel dropping from
  **7.96 blocks to 0.5148 blocks**, stopping within ~1.5 s.

## R14 · 2026-09-17 — the fourth answer, which turned into a new defect

- **Goal:** finish item 2 by verifying the multiplayer half of `pose.set`.
- **Saw (raw):** the receipt was clean (`applied={yaw,pitch}`, position `skipped`, `authority:server`)
  — but the client kept reading `z=12` for **+16.39 s**, a **newly connected client read `z=12.0`
  before sending any request**, and the server log showed the login positions changing from
  `17.60936942239352` to `12.0`. Three independent sources, same conclusion: **the remote teleport
  really worked and persisted.**
- **Found:** **P8** — one word (`skipped`) was carrying two meanings: "this really did not take
  effect" (true in single-player) and "it applied, but the client cannot testify to it" (the remote
  case). That reported a *working* capability as a failure.
- **Fixed:** three states — `applied`, `notClientVerifiable`, `skipped` — plus the measured session
  asymmetry written into the protocol (`7c6b7ab`).
- **Re-verified (R15):** single-player → position `skipped` with `notClientVerifiable` **empty**,
  `dz=0`, rotation kept; remote → position `notClientVerifiable` with the literal reason
  *"the server owns the position; the client cannot witness whether it applied"* and `skipped`
  **empty**, rotation `applied`. (Note: in this last run the requested value equaled the value the
  server already held, so this run does **not** re-demonstrate that the position changes; that claim
  rests on R14's three measurements.)

---

## Corrections we made to our own earlier claims

This section exists because the log is only trustworthy if it records the moments we changed our
minds.

1. **We treated a *queued* value as a *written* value.** R5 originally implied "the receipt recorded
   `forward=1.000`, therefore the client walked that fast". A later run showed the receipt field was
   the value **as requested** while the per-tick writes were clamped to `forward=0.215` — visible only
   in the `ALLOWED-INPUT … cmd=[…]` line. The field was renamed (`queuedCommand`) and a
   `writtenCommand` added; **the audit line, not the receipt, is the authority on what was written.**
2. **We retracted a magnitude.** The `dz = −4.7708` blocks measured in R5 is **not reproducible** and
   is **no longer used as a magnitude claim**: the same request gave `0.1094` in R6 (the player was
   walking into terrain) and `3.2393` per 60 ticks in R8 from an open position, consistent with
   "clamped value × duration". Only the **direction** and the **open-path magnitude** are claimed.
3. **We deleted a field we had just added.** R9's hardening introduced `confirmed` ("two independent
   readings agree"). R10 showed it was still a false green, because both readings happened inside a
   window shorter than the rollback; the field and the rule were removed in favour of "position is
   never reported as applied" (`5086455`).
4. **"The player stopped" was not evidence that the input had stopped.** In an early probe the player
   appeared frozen, which we briefly read as "the hold ends correctly". The honest reading is that the
   position was blocked by terrain: **already-written input was still being recorded** (audit lines
   kept appearing). The fix's acceptance therefore uses **time** (a `ticks:N` request may only
   produce N writes and must not outlive N/20 seconds), never "did the player stop moving".
5. **We corrected an environment explanation.** An audit failure was first attributed to a lingering
   helper process; it was in fact another session's transient build, and the fix was to attribute
   processes by command line rather than to blame the run. A later audit failure was the operator
   playing a game in the background — correctly attributed as foreign activity.

## Not verified, or limited by available means

- **Byte-for-byte reproduction with the official launcher** — not attempted: no official launcher is
  installed here. No claim is made.
- **Pixel-level "the lamp is lit"** — the original artifacts are gone, so this cannot be recomputed.
  **Not quantified.**
- **No public-internet or third-party-server validation.** All remote testing was against a locally
  hosted Forge server on `127.0.0.1`, on the operator's own machine.
- **`localhost` and `127.0.0.1` do not match each other** in `MODTEST_ALLOWED_HOSTS` (the matcher
  normalises text; it performs no DNS resolution). This is a **fail-closed usability trap, not a
  security hole**, and is recorded as documentation only.
- **`wait.frames` is a synchronous no-op** — it cannot substitute for real game ticks.
- **The fixture's "foreign activity ⇒ advisory" branch was proven by parameter comparison, not by
  loading the operator's machine.** A natural trigger would have required deliberately burning eight
  CPU threads or holding more than a gigabyte; instead the thresholds were varied to isolate the
  branch, proving both directions (advisory instead of failure when foreign growth is attributed;
  a hard failure — exit 7 — when nothing is attributed).
- **`pose.set` in single-player** changes rotation only; the position is reported `skipped`. This is
  correct behaviour, not a defect.

## How the operator's run constraints were honoured

The runs were executed under constraints set by the machine's owner, and the log records how each was
enforced rather than asserted:

| Constraint | Enforcement, as measured |
|---|---|
| One instance at a time | Every round is a single client; the launcher **refuses to start** if a client process is already present. |
| ≤ 150 s per instance (hard script ceiling 360 s) | Longest instance observed: **150.25 s**, and that one was the watchdog **force-killing** a stalled client. Everything else ran 22–52 s. |
| ≤ 25 min per round | No round approached the 1500 s budget. |
| Must be closed afterwards, nothing left running | Each round ends with a mandatory audit (target PID gone, no orphans, CPU/memory recovered) and a final check of `java.exe` — every round ended at **zero** Java processes. |
| The dedicated server is killed after use | Force-killed in both LAN rounds (lifetime ≈ 1–2.5 min). |
| One relaxation, stated every time | Only the LAN rounds allowed a second JVM to coexist (`-BlockingProcessNames 'Minecraft'`), because a client and its dedicated server must run together. Everything else — including the hard `orphans` gate — stayed enforced. |

Every instance was also preceded by a check that no foreign workload (game, concurrent build) was
occupying the machine; when foreign activity did appear, it was reported immediately and attributed
by the fixture rather than blamed on the run.
