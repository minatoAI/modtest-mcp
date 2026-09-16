# modtest-mcp — a development harness for Minecraft mod authors (MCP)

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
