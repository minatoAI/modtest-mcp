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
| 3 | The five unimplemented ops keep refusing *(status at R1–R15)* | **PASS then; superseded by R16–R20** — all five used to return `E_UNSUPPORTED`; they are now implemented and verified on a real client (README §9.2 item 3) |
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
| P9 | A `skipped` verdict was derived from a read-back that had not caught up, so a click that **had** landed was reported as "did not happen" — the mirror image of P5, and it invites an agent to repeat a working action. Negative conclusions now require a settled read; otherwise the answer is `cannot determine` / `notClientVerifiable`. |
| P10 | `world.place` reported `placed:true` and `verdict:"applied"` from the request, with nothing the client could witness → `placed` is now the client's own read-back (`blockObserved`) and the verdict is `notClientVerifiable`. |

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

## R16 · 2026-09-17 — the five new ops on a real client, and the first sighting of P9

- **Goal:** execute `inv.click` / `inv.toss` / `use.item` / `shot.capture` / `bench.read` through the
  bridge on a real 1.20.1 client (the action layer that had just been wired) and read each receipt
  against what the world actually did.
- **Saw:** `inv.click` returned `verdict:"skipped"` with the reason *"no change was observed in the
  client's menu"*, while an `inv.toss` about a second later reported *"slot 0 is empty"* — that is,
  the click **had** taken effect. The read-back had run before the container synchronisation.
- **Found:** **P9** — a `skipped` verdict derived from a single read-back that had not caught up.
  First causal reading (corrected in R17): "the click picked the item up".
- **Held:** the round stopped at reporting. The fix waited for the second sighting, so that the cause
  was measured rather than assumed.

## R17 · 2026-09-17 — P10, and P9's causality corrected

- **Goal:** check that `world.place` can be witnessed from the client at all, and re-run the
  join-time sequence that had produced P9.
- **Saw:** `world.place` returned `{"block":"minecraft:stone","placed":true,"verdict":"applied"}`
  while the client had **no way to witness it** — the bridge exposed no block query, and
  `state.query{what:["block"],x,y,z}` answered `unknown param(s)`. Separately, the **first** `inv.toss`
  after joining (no click before it) also reported *"slot 0 is empty"*, and a `state.query` **one
  second later** showed slot 0 occupied.
- **Found:** **P10** — a self-reported field exceeding what the client can observe (`placed:true` came
  from the request, not from any read-back) — and a **correction to P9's causality**: not "the click
  picked the item up", but a **race between a container read and the synchronisation window**, which
  produces the same wrong answer in both directions.
- **Fixed:** `eee7093`; re-verified in R18–R20 below.

## R18 · 2026-09-17 — refusal paths and the audit rule, re-measured

- **Goal:** after the P9/P10 fix, re-run the refusal half over the guarded write ops.
- **Saw:** the refusal paths behaved **6/6** in one run and **7/7** in the other (each op refused the
  path it was aimed at, with `E_PRECONDITION`, and nothing reached the client); **exactly one**
  `ALLOWED-MUTATION` line per allowed op; and the token's **literal value appeared 0 times** in the
  logs — only its 8-character fingerprint.
- **Note (recorded because it was ambiguous before):** an op precondition that fails *after* the guard
  allowed — empty slot, no container, cooldown, occupied cell — correctly keeps **its** allowance
  line. "A refusal leaves no trace" applies to guard and parameter refusals only (PROTOCOL §7.2).

## R19 · 2026-09-17 — P9 re-verified: both decisions

- **Goal:** re-run the two shapes P9 had exposed.
- **Saw (decision 1):** an `inv.click` whose read-back had not changed came back
  `verdict:"notClientVerifiable"` with `skipped` **empty**, and a later read showed the change — the
  receipt no longer claims "it did not happen".
- **Saw (decision 2):** with the container still catching up, the refusal read `E_PRECONDITION` /
  *"cannot determine whether slot 0 is empty …"* instead of *"slot 0 is empty"*; once the view is
  settled, the same absent slot is refused factually again.
- **Also:** `use.item{hand:"off"}`'s `heldBefore`/`heldAfter` report the **off hand's** item, and
  `state.query{what:["offhand"]}` makes an off-hand dispatch checkable from the client.

## R20 · 2026-09-17 — P10 re-verified, and the refusal we deliberately left alone

- **Goal:** re-run `world.place` against the new read-back.
- **Saw:** the receipt carries `blockObserved` with the block id the client sees, `placed` **is** that
  read-back, and the verdict is `notClientVerifiable` — the unconditional "applied" claim is gone. The
  equivalent evidence was measured in the other direction too: with an adapter that exposes no block
  query, `placed` is `false` and `blockObserved` is `null`, so nothing is claimed either way.
- **Left alone on purpose:** `world.place`'s pre-check still refuses an occupied cell as a
  **client-side factual assertion** (`E_EXEC`), because world block state has **no** synchronisation
  signal — only container contents do. Recorded as a known limitation rather than guessed at
  (PROTOCOL §6.2c, README §9.4).

### Round T · 2026-09-17 — closing acceptance, and three corrections to our review criteria

- **Result:** **PASS.** The five implemented ops, the three newly guarded legacy write ops, the refusal
  paths (**6/6** and **7/7**), the one-audit-line-per-allowance rule and the never-logged token
  (literal value **0** occurrences) all held on a real client.
- **Criteria corrections (all on the review side; now in PROTOCOL §6.2c):**
  1. The **`cannot determine` message prefix is the machine-discriminable marker**: `E_PRECONDITION` is
     the *same code* for "could not be determined" and "empty", and the error object has no other
     discriminator. Recorded as a known limitation of the vocabulary; **no new error field** was added
     for it.
  2. **Judge by assertion form, not by substring.** Matching a string is what made a correct message
     (`cannot determine whether slot 1 is empty`) look like a failure during review.
  3. **No new `detail` field** — the free-form detail object is not part of that contract.

## R21 · 2026-09-17 — P11 on a real client: the placement became visible

- **Goal:** does a scripted placement now look like a player's placement to everyone else?
- **Saw:** with the fix, **KubeJS `BlockEvents.placed` really fired** —
  `block_placed seq=2, payload block:"minecraft:stone_bricks"` — against the spike round, where the
  direct world write produced no event at all under the same setup. And after `inv.select`, the server
  placed from the **new** slot (`smooth_stone`), i.e. the carried-item packet reached it. `verdict` did
  not fall back to `applied`, and the regression suite stayed green.
- **Found (P12):** the conservative answer did not converge. With an empty hand the op answered
  `reason:"container-not-synced"` immediately **and still after four seconds**, so `empty-hand` was
  unreachable and a caller could wait forever. The window had been counted in wall-clock milliseconds.
- **Fixed:** the window is now an age in **client ticks** (`containerSyncAgeTicks`, 20 ticks), which
  always grows while the client keeps running, and a dispatched click/toss closes it as soon as the open
  menu's state id moves — the server's own answer rather than a timer. `empty-hand` and `slot-empty` are
  therefore reachable again, just not immediately; selecting a slot opens no window at all.
- **Also decided (P11 follow-up):** core's `E_EXEC "cell occupied"` pre-check was removed. It ran before
  the interaction and so (a) made the adapter's honest `target-not-replaceable` check unreachable and
  (b) refused replaceable targets a player *can* place into (tall grass, a snow layer). Replaceability is
  now decided once, in the adapter, from the block state.
- **Two operational lessons (each cost a run):**
  1. **World changes persist across instances.** A block placed in one round is still there in the next,
     so a fixed target cell becomes "not replaceable" on the second run, and a test reusing it looks like
     a regression when it is only stale ground. Round targets MUST be on **fresh, empty ground per round**.
  2. **After `inv.select`, let the synchronisation window close before sending the placement.** The server
     learns the carried slot from the packet, and the view core reads may still be catching up; an op sent
     in the same instant can be answered `cannot determine` even though the selection was correct.
  3. **Take the artifact from `:forge:build`, never from a guardrail-only run.** `build/libs` is written
     **twice**: `:forge.jar` writes it with **official/mapped names and no SRG references**, and `reobfJar`
     then *replaces* it with the shippable bytes. A guardrail-only invocation used to stop after the first
     write, leaving a **non-reobfuscated** jar that still passed both guardrail checks. **Measured:** the
     intermediate is **159,308 B with 0 `m_*_` / 0 `f_*_` SRG references**, the real artifact is
     **160,157 B with 40 / 10** — an **849 B** gap, and a jar that would not work against a real client.
     Both states contain `modtest.refmap.json` **and** `modtest.harness.mixins.json`, which is why a
     mixin-product check alone is necessary but **not sufficient** (correction 10). This trap had already
     cost us twice: **a reduced jar was reported as the canonical identity** (correction 9) and **a
     real-machine round was stopped** by the tester's hash gate. The canonical pair is **guarded 160,157 B**
     and **unguarded 160,204 B**, differing by **47 B, entirely `META-INF/MANIFEST.MF`** (316 vs 369 B); a
     larger gap means one jar is not in its final state. **Fixed under task-77** (see R22): both guardrails
     now depend on `reobfJar` and assert entry-CRC identity with `reobfJar`'s output.

---

## R22 · 2026-09-18 — the build graph could hand a non-reobfuscated jar to a tester (task-77, fixed)

- **Goal:** make it impossible for a guardrail-only invocation to leave a wrong jar in `build/libs`, after
  R21's identity mix-up.
- **Root cause, measured (and it was NOT what we first assumed):** `build/libs` is written twice — `:forge.jar`
  writes it with **official/mapped names**, then `reobfJar` *replaces* it with the shippable bytes. The two
  guardrail tasks only pulled in `jar`, so a guardrail-only run left the **non-reobfuscated** jar behind.
  Discriminator: the intermediate's `MinecraftClientModel.class` contains **0 `m_*_` / 0 `f_*_`** SRG
  references (and still contains the readable `getBlockReach`), the real artifact has **40 / 10**. Sizes:
  **159,308 B** vs **160,157 B** (849 B). **Both** states contain `modtest.refmap.json` **and**
  `modtest.harness.mixins.json`, which is why the mixin-product check the task originally called for would
  have passed the wrong jar.
- **Fix:** both guardrail tasks now `dependsOn 'reobfJar'`, and both compare the **entry CRCs of `build/libs`
  against `reobfJar`'s own output** (`build/reobfJar/output.jar`), failing when any other task's bytes are
  sitting there. The mixin-product presence check is kept as a second assertion.
- **Acceptance, measured:** `:forge:clean` + the two guardrail tasks alone now end with **160,157 B / 40 `m_*_`
  references**, and the jar is **entry-for-entry identical (names + uncompressed sizes + CRC32) to the
  shipped `v1.0.0-alpha.3` artifact** — for **both** variants (guarded and unguarded). Removing the
  `reobfJar` dependency reproduces the defect and makes **both guardrails fail loudly**
  (`reobfJar/output.jar does not exist — reobfJar never ran, so the jar in build/libs cannot be the
  shippable artifact`) while leaving the 159,308 B intermediate behind; the sources were then restored
  byte-exactly (sha verified, zero residue) and both variants re-verified green, plus the four guardrails
  and `verifyProductJar` (`PRODUCT-JAR-CLEAN`) in both variants.
- **Artifact identities after the fix (local, per-build sha):** guarded **160,157 B /
  `CCF801A4…7481AE`**, unguarded **160,204 B / `CA4599A8…01A686`** — content identical to the published
  asset's build (`D5EDB684…`), which remains untouched.

---

## R23 · 2026-09-18 — task-78 (A block query / B version lines / C two-tier stop): built, all red-proofs measured

- **Goal:** (A) expose a block reading with explicit **unknown** semantics plus a cheap `isMoving`;
  (B) align every version line on `1.0.0-alpha.4` / `1.0.0a4`; (C) two-tier cancellation with
  non-failure termination codes, and a stop that **really stops** (the P7 lesson).
- **Core, measured:** `:core:cleanTest :core:build --rerun-tasks` (JDK 17) ⇒ **212 tests, 0 failures,
  0 errors, 0 skipped** (was 191; `CancelAndBlockQueryTest` adds 21). The four guardrails are green:
  `verifySelfContainedJar` (**77 :core classes, top-level 77, nested 0**), `verifyMixinRefmap`
  (member ref still resolves to SRG `m_214106_`; final artifact carries `modtest.refmap.json` +
  `modtest.harness.mixins.json`), `verifyGsonApiSurface` (77 classes), and
  `noDeclarativeKubeJsDependencyExistsInTheBuild`.
- **"Old code goes red" — six negative tests, each temporarily reverted, then restored byte-exactly
  (sha256 verified after every case):** ① unknown-≠-air in `state.query` (fabricating air for an unread
  cell) ⇒ red; ② the Forge `isLoaded`/build-height guard removed ⇒ red; ③ `InputHold.stop` no longer
  cancels ⇒ red (that is the P7 renewal defect); ④ an unknown footing treated as safe ⇒ red;
  ⑤ `pyproject.toml` put back to `1.0.0a3` ⇒ version test red; ⑥ `E_NO_PATH` mentioned in a non-Protocol
  source file ⇒ reserved-pin test red. N1's red was inspected in full: it is an
  **`AssertionFailedError`** (`"block":"minecraft:air"` for an unread cell), not a compile failure.
- **Two real defects the new tests caught in this batch's own code** (fixed before the final run):
  (1) `input.stop` originally dispatched **without going through the single guard point**, i.e. an
  unaudited write — caught by `anImmediateStopCancelsAndIsReportedAsApplied` (0 audit lines, expected 1),
  now routed through `MutationGuard.requireAllowed` like every other write op; (2) `LevelReader.hasChunkAt`
  is deprecated in this mapping, so the guard uses the supported `Level.isLoaded(pos)` (build output now
  warning-free for these sites).
- **Artifact identities (local, not published — this build), measured after `:forge:build -PwithForge`
  and `-PwithForge -Punguarded`:**
  - guarded `modtest-harness-forge-1.0.0-alpha.4.jar` — **170,625 B, 94 entries, 362,557 B uncompressed,
    sha256 `c288bcf18d0703835688295d77f4b6ae43108b26502dc7caa07ac9fc777af915`**, manifest
    `Modtest-Guard-Variant: guarded` (316 B), `m_*=71 / f_*=20` SRG refs, `mods.toml` version
    `1.0.0-alpha.4`;
  - unguarded `…-1.0.0-alpha.4-unguarded.jar` — **170,672 B, 94 entries, sha256
    `5dddc70871045768a3070b7536365e2f7793d191a42763903544ff9830ca6224`**, manifest 369 B;
  - guarded→unguarded gap **47 B, entirely `META-INF/MANIFEST.MF`** (316 vs 369 B) — the established
    fingerprint that the jar is in its final state holds;
  - **entry delta vs R22's state: 90 → 94, four added, none removed** — `StopRequest.class`,
    `StopRequest$Tier.class`, `ClientModel$StopResult.class`, `VanillaOps$StopOps.class`;
  - **reobf identity:** `build/libs/…-unguarded.jar` sha256 **equals** `build/reobfJar/output.jar` sha256
    (`5dddc708…`) byte-for-byte, so `build/libs` really holds the reobfuscated write;
  - **the file names changed with the version line** (`…-1.0.0-alpha.1.jar` → `…-1.0.0-alpha.4.jar`), and
    two **stale alpha.1 jars were sitting in `build/libs` next to them** (build outputs accumulate) — they
    were removed so no tester can pick up the old file by name.
- **`verifyProductJar` (root task, a gate on *product* jars):** on the clean product reference ⇒
  `PRODUCT-JAR-CLEAN`, exit 0; on our harness jar ⇒ `PRODUCT-JAR-CONTAINS-HARNESS`, exit 1 — the gate
  refusing a harness jar as a product is its designed behaviour, not a regression.
- **Follow-up from the real-machine round (as reported by the round; includes two things NOT observed).**
  The round's initial alarm — "8 write ops but only 4 `ALLOWED-MUTATION` lines" — was a **counting error
  across two families**, not a missing line: mutation class = exactly 1 line per allowed op (**4/4**),
  injection class = 1 `ALLOWED-INPUT` line per **written tick** (**106** lines for the `input.set` hold,
  the P7 design). Nothing was broken, so the fix is **wording**: PROTOCOL §7.2 and README §9.2
  now state the three granularity classes (mutation / injection / recording) and the fact that a single-tag
  count over a mixed ticket must undercount. The round also showed a second `input.set` while a hold was
  still running returning **`ok:true`**, which is **correct** per §4.1: `E_SUPERSEDED` describes a
  superseded **stop request**, not a replaced hold — its one reachable trigger (safe stop armed while not at
  a safe point → newer `input.set` → a further `input.stop` **inside the same ticket**) is now written into
  PROTOCOL §4.1/§6.2g with a reproduction recipe.
  **NOT observed (honest gaps, limited means):** (1) the two stop tiers' **observable difference** — both
  real-machine stops landed `atSafePoint:true`, i.e. the *applied* branch, so the **`armed` branch has still
  only been exercised by the core unit test** (`anArmedSafeStopDoesNotClaimThePlayerHasStopped`) and never on
  a live client; seeing it needs the player to be genuinely off a safe point (airborne / in a wall) when the
  stop arrives. (2) `state.query{what:["moving"]}` with `movingKnown:false` — that branch was never reached
  on a real client, so the "unknown movement" answer rests on unit tests only.
- **Still pending a real-machine round:** `blockReplaceableAt`'s three-state honesty on an unloaded chunk,
  and the absence of per-tick renewal (the `ALLOWED-INPUT` tick count) against a live client.

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
6. **We got P9's cause wrong the first time.** R16 read it as "the click picked the item up, but the
   receipt was early". R17's join-time run — the first op after joining, with no click before it —
   produced the same *"slot 0 is empty"* and a `state.query` one second later showed the item, so the
   real cause is a **race with the container synchronisation window**, not the click's side effect.
   The defect (a negative conclusion derived from an unsettled read) is unchanged; only the
   explanation was corrected, and the fix covers both directions.
7. **Our own review criterion was wrong twice.** We first judged the new `cannot determine` refusal by
   asking whether its message contained the words "is empty" — but a correct message legitimately says
   *"cannot determine whether slot 1 **is empty**"*. The criterion is now: judge by the **assertion
   form** (the `verdict` and which verdict object is populated) and by the `cannot determine`
   **prefix**, and **do not** add an error field for the distinction.
8. **We shipped a conservative answer that could never converge.** The P9/P10 round introduced a
   wall-clock container sync window. On the next real-machine round it answered "cannot determine"
   *forever*: an empty hand was still `container-not-synced` four seconds later, so the plain
   `empty-hand` refusal was unreachable and a caller had no honest answer to wait for (R21, P12). The
   window is now counted in client ticks and closable by evidence (the open menu's state id moves).
   **Conservative must still mean bounded.**
9. **We reported a reduced jar as the canonical artifact.** While freezing the artifact for the P11/P12
   real-machine round we reported guarded **159,308 B / `915BDA5C…`** as canonical. It was not: a
   guardrail-only invocation in the same command had left `build/libs` holding the jar written by
   `:forge.jar`, which **reobfJar had not yet replaced** — and we measured that file. The tester's
   **hash gate caught it** — the on-disk jar (`160,157 B / 7D6CBAFA…`) matched neither our "canonical"
   guarded number nor the unguarded one — and the round was stopped rather than run against an
   unverified artifact. The mismatch was then made concrete rather than argued: running the guardrails
   alone produced **159,308 B / `B63DFE6B…`**, and the following `:forge:build -PwithForge` produced
   **160,157 B / `D5EDB684…`**, content-identical to the pre-rebuild jar, with the mixin config, the
   refmap (SRG member resolved) and 73 `:core` classes present. The "guarded is 896 B smaller than
   unguarded" story that came with it was also ours and also wrong: the only difference between the
   variants is **`META-INF/MANIFEST.MF`**, a **47 B** gap. **Two lessons, both now recorded in `README`
   §9.1: an artifact identity must come from a `:forge:build` output and be re-measured after every
   rebuild; and a variant gap that is not 47 B means a jar is not in its final state.**

10. **We blamed the build graph's `addMixinsToJar` step, and that was wrong too.** Following correction 9 we
   wrote into `README` §9.1 and R21 lesson 3 that the reduced jar was the state *before*
   `addMixinsToJar`, and task-77's first requirement was to assert that the jar contains the mixin products.
   Measurement disproved it: **the non-reobfuscated intermediate already contains both
   `modtest.refmap.json` and `modtest.harness.mixins.json`**, so that assertion passed on the wrong jar;
   the actual discriminator is **reobfuscation** — 0 `m_*_` references in the intermediate against 40 (plus
   10 `f_*_`) in the real artifact, because `build/libs` is written by `jar` and then *replaced* by
   `reobfJar`. The fix was therefore re-aimed at `reobfJar` (dependency **and** an entry-CRC identity
   assertion against `reobfJar`'s output), and the acceptance now includes the old graph failing loudly.
   **Lesson: name the task that writes the bytes you measure before blaming a step in the graph.**
   **The reviewer's lesson, recorded as theirs:** when specifying a mechanical assertion, first ask what
   that assertion looks like **on the bad artifact**. The instruction here was "the jar must contain
   `modtest.refmap.json` + `modtest.harness.mixins.json`, missing either is red" — and that is red-proof
   only against one failure mode, because the un-reobfuscated jar contains both files too. Specify the
   measurement that **discriminates** good from bad (here: SRG references, 0 versus 40/10), not a
   plausible-looking proxy. This is the same family as the "measuring a different path than the one that
   runs in production" mistakes earlier in this log.
11. **A pre-existing read folded "unknown" into a known value: `blockIdAt` reported an unloaded chunk as
   `air`** (found in task-78 while borrowing mineflayer's `blockAt`, whose design is to return `null` for a
   block it cannot see). The P10 read-back implementation read
   `player().level().getBlockState(pos)` and answered `""` (air) whenever `isAir()` was true — but vanilla's
   chunk API **answers air for an unloaded chunk**, and `getBlockState` outside the world's build height is
   air as well. So "this client cannot see that cell" was silently reported as the **fact** "that cell is
   air": the same false-negative family as P9/P12 and the removed `cellOccupied` placement gate
   ("cannot determine" collapsed into a confident answer), except this one had shipped since P10 and would
   have produced a fabricated `state.query` answer the moment the channel was exposed.
   **Fix (`MinecraftClientModel`, and a three-state contract in `ClientModel`):** guard the read with
   `level.hasChunkAt(pos)` and `level.isOutsideBuildHeight(pos)`, and answer `null` (cannot witness) instead
   of `""`; expose the reading as explicit states — `blockKnown` / `block` / `blockIsAir` /
   `blockReplaceable`, with JSON `null` for the last three when the cell cannot be read, and `"minecraft:air"`
   spelled out when it *is* known to be air — plus a companion `blockReplaceableAt` (`canBeReplaced()`)
   behind the same guard. A query with no coordinates fails loudly (`E_BAD_PARAMS`) rather than answering
   about another cell. **Lesson: guard at the observation boundary whenever vanilla defaults to a benign
   value; "I cannot see it" needs its own representable state, and it must be impossible to read it as the
   benign value.**

---

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
