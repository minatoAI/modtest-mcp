"""Offline conformance smoke test for modtest-mcp / modtest-bridge 1.0.

Spawns ``server.py`` as a subprocess with a temporary bridge dir, drives it over stdio
JSON-RPC, and asserts the wire contract. No game, no network, no third-party packages.

Run:  python src/modtest_mcp/smoke.py     (prints MCP-SMOKE-ALL-PASS on success)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "server.py")
PROTOCOL = "modtest-bridge/1.0"

_pass = 0


def check(cond: bool, what: str, extra: object = "") -> None:
    global _pass
    if not cond:
        raise AssertionError(f"FAIL: {what} {extra}")
    _pass += 1
    print(f"  ok {_pass:2d}. {what}")


class Client:
    def __init__(self, proc: subprocess.Popen) -> None:
        self.proc = proc
        self._id = 0

    def call(self, method: str, params: dict | None = None) -> dict:
        self._id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self._id, "method": method,
                           "params": params or {}}).encode()
        assert self.proc.stdin and self.proc.stdout
        self.proc.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode("latin-1") + body)
        self.proc.stdin.flush()
        headers: dict[str, str] = {}
        while True:
            line = self.proc.stdout.readline().decode("latin-1").strip()
            if not line:
                break
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.strip().lower()] = value.strip()
        return json.loads(self.proc.stdout.read(int(headers["content-length"])).decode("utf-8"))

    def tool(self, name: str, arguments: dict) -> dict:
        resp = self.call("tools/call", {"name": name, "arguments": arguments})
        return json.loads(resp["result"]["content"][0]["text"])


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="modtest-mcp-smoke-")
    env = dict(os.environ, MODTEST_AGENT_DIR=tmp)
    proc = subprocess.Popen([sys.executable, SERVER, "--dir", tmp],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, env=env)
    print(f"bridge dir: {tmp}")
    try:
        c = Client(proc)

        init = c.call("initialize", {"protocolVersion": "2024-11-05"})
        check(init["result"]["serverInfo"]["name"] == "modtest-mcp", "initialize: server name")
        bridge = init["result"]["capabilities"]["experimental"]["modtestBridge"]
        check(bridge["protocol"] == PROTOCOL, "initialize: advertised protocol", bridge)

        tools = c.call("tools/list")
        names = sorted(t["name"] for t in tools["result"]["tools"])
        check(names == ["drop_ticket", "list_queue", "read_receipt"], "tools/list: 3 tools", names)

        # --- positive path -------------------------------------------------
        drop = c.tool("drop_ticket", {"ticket": "smoke-1",
                                      "payload": {"ops": [{"op": "state.query",
                                                           "params": {"what": ["pose"]}}]}})
        check(drop["ok"] and drop["accepted"] == "smoke-1", "drop_ticket: accepted", drop)
        check(drop["protocol"] == PROTOCOL, "drop_ticket: protocol stamped")
        check(drop["ops"] == ["op1"], "drop_ticket: op id defaulted")
        inbox = os.path.join(tmp, "inbox")
        check(os.path.isfile(os.path.join(inbox, "smoke-1.json")), "drop_ticket: ticket on disk")
        check(not os.path.exists(os.path.join(inbox, "smoke-1.json.tmp")), "drop_ticket: no .tmp left")
        with open(os.path.join(inbox, "smoke-1.json"), encoding="utf-8") as fh:
            t = json.load(fh)
        check(t["protocol"] == PROTOCOL and t["ticket"] == "smoke-1" and t["ops"][0]["id"] == "op1",
              "ticket shape matches PROTOCOL 3.1", t)
        check(t["timeout_ms"] == 10000 and t["on_error"] == "abort", "ticket defaults applied")

        # --- receipt path (fake the game side, exactly as the executor would) ---
        os.makedirs(os.path.join(tmp, "outbox"), exist_ok=True)
        receipt = {"protocol": PROTOCOL, "ticket": "smoke-1", "trial": "t9", "ok": True,
                   "executor": {"id": "fake", "version": "1.0.0"},
                   "duration_ms": 1, "ops": [{"id": "op1", "op": "state.query", "ok": True,
                                              "result": {"pose": {"yaw": 12.5}}, "duration_ms": 1}]}
        with open(os.path.join(tmp, "outbox", "smoke-1.result.json"), "w", encoding="utf-8") as fh:
            json.dump(receipt, fh)
        read = c.tool("read_receipt", {"ticket": "smoke-1", "timeout_s": 3})
        check(read["ok"] is True and read["trial"] == "t9", "read_receipt: parsed", read.get("trial"))
        check(read["ops"][0]["result"]["pose"]["yaw"] == 12.5, "read_receipt: result payload")

        lst = c.tool("list_queue", {"which": "outbox"})
        check("smoke-1.result.json" in lst["files"], "list_queue: outbox listing", lst)
        miss = c.tool("read_receipt", {"ticket": "nope", "timeout_s": 0.4})
        check(miss["ok"] is False and miss["error"]["code"] == "E_TIMEOUT", "read_receipt: timeout path")

        # --- negative paths -------------------------------------------------
        bad = c.tool("drop_ticket", {"ticket": "../evil", "payload": {"ops": [{"op": "x"}]}})
        check(bad["error"]["code"] == "E_BAD_TICKET", "reject bad ticket name", bad)
        empty = c.tool("drop_ticket", {"ticket": "t-empty", "payload": {"ops": []}})
        check(empty["error"]["code"] == "E_BAD_TICKET", "reject empty ops", empty)
        wrong = c.tool("drop_ticket", {"ticket": "t-proto", "payload": {"protocol": "other/9.9",
                                                                        "ops": [{"op": "state.query"}]}})
        check(wrong["error"]["code"] == "E_PROTOCOL", "reject protocol mismatch", wrong)
        extra = c.tool("drop_ticket", {"ticket": "t-extra",
                                       "payload": {"ops": [{"op": "state.query", "bogus": 1}]}})
        check(extra["error"]["code"] == "E_BAD_PARAMS", "reject unknown op field", extra)
        dup = c.tool("drop_ticket", {"ticket": "t-dup",
                                     "payload": {"ops": [{"id": "a", "op": "state.query"},
                                                         {"id": "a", "op": "state.query"}]}})
        check(dup["error"]["code"] == "E_BAD_OP_ID", "reject duplicate op id", dup)

        # --- catalog validation --------------------------------------------
        catalog = {"protocol": PROTOCOL, "protocols": [PROTOCOL], "catalog_version": "1.0.0",
                   "executor": {"id": "fake", "version": "1.0.0"},
                   "ops": [{"name": "state.query", "title": "Read state",
                            "paramsSchema": {"type": "object", "properties": {"what": {"type": "array"}},
                                             "required": ["what"], "additionalProperties": False},
                            "resultSchema": {"type": "object"},
                            "preconditions": [{"kind": "singleplayer"}],
                            "sideEffects": ["none"], "executorId": "fake", "since": "1.0"}]}
        with open(os.path.join(tmp, "catalog.json"), "w", encoding="utf-8") as fh:
            json.dump(catalog, fh)
        unknown = c.tool("drop_ticket", {"ticket": "t-unknown", "payload": {"ops": [{"op": "world.place"}]}})
        check(unknown["error"]["code"] == "E_UNKNOWN_OP", "catalog: reject unknown op", unknown)
        missing = c.tool("drop_ticket", {"ticket": "t-missing", "payload": {"ops": [{"op": "state.query"}]}})
        check(missing["error"]["code"] == "E_BAD_PARAMS", "catalog: reject missing required param", missing)
        ok = c.tool("drop_ticket", {"ticket": "t-ok",
                                    "payload": {"ops": [{"op": "state.query", "params": {"what": ["pose"]}}]}})
        check(ok["ok"] is True, "catalog: accept valid ticket", ok)
        check(c.tool("list_queue", {"which": "inbox"})["files"] == ["smoke-1.json", "t-ok.json"],
              "list_queue: only valid tickets landed")
    finally:
        proc.kill()
    print("MCP-SMOKE-ALL-PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
