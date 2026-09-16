# examples/ — how a mod-side executor plugs in

`modtest-mcp` is only half of the protocol: the **agent side**. The other half is a small
**executor** inside the game client. This folder shows the shape of that half without shipping any
game code.

| File | What it is |
|---|---|
| `catalog.example.json` | A complete, valid `modtest-bridge/1.0` op catalog (4 ops: read-only state, pose set, screenshot capture, bench sample) with JSON-Schema params/results, preconditions and side effects |
| `ATTRIBUTION.md` | Where this design came from, and the licensing/evidence pointer |

## The four responsibilities of an executor

1. **Poll** `<BRIDGE_DIR>/inbox/` (2 Hz is a good default for a client tick loop) and ignore
   `*.json.tmp`.
2. **Validate**: `protocol` (§5), ticket id vs file name, `ops[]` non-empty, op ids unique, op
   names in your catalog, params against the op's `paramsSchema`.
3. **Execute** ops **in order**, apply `expect` assertions, honour `on_error`, enforce the safety
   requirements (§7.2 of the protocol):
   * mutating ops (`sideEffects` other than `none` / `telemetry.recording`) MUST fail with
     `E_PRECONDITION` when the client is **not** in a single-player session;
   * mutating ops MUST also require an explicit `allow-mutate` opt-in at executor startup.
4. **Answer** with exactly one receipt at `outbox/<ticket>.result.json`, archiving any previous
   receipt, and publish `catalog.json` at the bridge root.

## Sketch (any language; this is the whole loop)

```text
for each tick:
    for file in listing(inbox/*.json) ordered by name:
        ticket = parse(file)                      # ignore *.tmp
        receipt = {"protocol": "modtest-bridge/1.0", "ticket": ticket.ticket,
                   "trial": ticket.trial, "ok": True,
                   "executor": {"id": EXECUTOR_ID, "version": VERSION}, "ops": []}
        for op in ticket.ops:
            if not allowed(op):                   # catalog + preconditions + §7.2 safety
                receipt.ops.append(fail(op, "E_PRECONDITION", reason))
                if on_error(ticket, op) == "abort": break
                continue
            try:
                result = dispatch(op.op, op.params)      # your mod's implementation
                receipt.ops.append(ok(op, result, assert_expect(op, result)))
            except UnknownOp:
                receipt.ops.append(fail(op, "E_UNKNOWN_OP"))
            except Exception as e:
                receipt.ops.append(fail(op, "E_EXEC", str(e)))
        receipt.ok = all(o.ok for o in receipt.ops)
        write_atomic(outbox/<ticket>.ticket + ".result.json", receipt)   # tmp + rename
        move(file -> done/ or failed/)
```

## Registering a new op (that is the whole extension point)

1. Implement the behaviour behind a `name` of the form `domain.verb` (`state.query`,
   `world.place`, `render.flag`, …).
2. Add one catalog entry: `name`, `paramsSchema`, `resultSchema`, `preconditions`, `sideEffects`,
   `executorId`, optional `divergence`/`llmSummary`.
3. Publish the updated `catalog.json`. The agent side then validates tickets before they reach the
   game, and the receipt's `result` is checked against your `resultSchema`.

No registration API, no build-time code generation, no fixed op list: **the catalog is the
extension point**.

## Safety rules that are not optional

* Never ship the executor in a release build of a mod that players install for normal play; gate it
  behind a development flag/build variant (see `docs/STAGE2-TODO.md` for the CI assertion that
  enforces this).
* Refuse to run mutating ops on remote/multiplayer sessions, even if a ticket asks nicely.
* Never bypass authentication, account checks or the client's network layer.
* Log every executed op locally (ticket id, op id, name, outcome) so a test run stays auditable.

## Try it without a game

```bash
python src/modtest_mcp/server.py --dir ./.modtest-agent     # terminal 1 (MCP host attaches here)
# terminal 2: drop a ticket exactly like an MCP host would
python - <<'PY'
import json, pathlib
d = pathlib.Path(".modtest-agent"); (d/"inbox").mkdir(parents=True, exist_ok=True)
(d/"inbox"/"demo.json").write_text(json.dumps({
  "protocol": "modtest-bridge/1.0", "ticket": "demo", "ops": [{"op": "state.query", "params": {"what": ["pose"]}}]}))
PY
# then, as the game would: write a receipt and read it back through the MCP tool
```

The conformance smoke test does exactly this end-to-end (with a fake executor):
`python src/modtest_mcp/smoke.py` → `MCP-SMOKE-ALL-PASS`.
