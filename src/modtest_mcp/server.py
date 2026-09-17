"""modtest-mcp — an MCP server for a local mod-test file bridge.

This module is the reference *agent side* implementation of the `modtest-bridge/1.0`
protocol (see docs/PROTOCOL.md). It exposes three MCP tools over stdio JSON-RPC:

    drop_ticket   write a ticket into <dir>/inbox atomically (.json.tmp -> rename)
    read_receipt  poll <dir>/outbox/<ticket>.result.json
    list_queue    list <dir>/inbox | outbox | done

It performs file I/O only: no sockets, no subprocesses, no shell. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from typing import Any, Callable

__version__ = "1.0.0a4"

PROTOCOL_ID = "modtest-bridge/1.0"
SERVER_NAME = "modtest-mcp"
TICKET_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
DEFAULT_TIMEOUT_MS = 10000
MAX_TIMEOUT_S = 300.0
TICKET_FIELDS = {"protocol", "ticket", "trial", "created_utc", "timeout_ms", "on_error", "ops"}
OP_FIELDS = {"id", "op", "params", "expect", "timeout_ms", "on_error"}
ERROR_CODES = (
    "E_PROTOCOL", "E_BAD_TICKET", "E_BAD_OP_ID", "E_UNKNOWN_OP", "E_BAD_PARAMS",
    "E_PRECONDITION", "E_TIMEOUT", "E_BUSY", "E_EXEC", "E_ASSERT", "E_UNSUPPORTED",
    # Non-failure terminations: the task ended without completing and that is NOT a defect — it was
    # superseded, already stopped, unroutable or stuck. NON_FAILURE_TERMINATIONS mirrors the core enum's
    # nonFailureTermination(); callers should not treat these as harness failures.
    "E_SUPERSEDED", "E_STOPPED", "E_NO_PATH", "E_STUCK",
)
NON_FAILURE_TERMINATIONS = frozenset({"E_SUPERSEDED", "E_STOPPED", "E_NO_PATH", "E_STUCK"})


def _err(code: str, message: str, **detail: Any) -> dict:
    out = {"code": code, "message": message}
    if detail:
        out["detail"] = detail
    return out


class Bridge:
    """Filesystem side of the protocol. All paths stay inside ``base``."""

    def __init__(self, base: str, protocol: str = PROTOCOL_ID, check_catalog: bool = True) -> None:
        self.base = os.path.abspath(base)
        self.protocol = protocol
        self.check_catalog = check_catalog

    # ---- paths -----------------------------------------------------------
    def _dir(self, kind: str) -> str:
        d = os.path.join(self.base, kind)
        os.makedirs(d, exist_ok=True)
        return d

    def ticket_path(self, name: str) -> str:
        return os.path.join(self._dir("inbox"), name + ".json")

    def receipt_path(self, name: str) -> str:
        return os.path.join(self._dir("outbox"), name + ".result.json")

    # ---- catalog ---------------------------------------------------------
    def catalog(self) -> dict | None:
        path = os.path.join(self.base, "catalog.json")
        if not os.path.isfile(path):
            return None
        try:
            with open(path, encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _validate_ops_against_catalog(self, ops: list) -> dict | None:
        if not self.check_catalog:
            return None
        cat = self.catalog()
        if not cat:
            return None
        specs = {o.get("name"): o for o in cat.get("ops", []) if isinstance(o, dict)}
        if not specs:
            return None
        for i, op in enumerate(ops):
            name = op.get("op")
            spec = specs.get(name)
            if spec is None:
                return _err("E_UNKNOWN_OP", f"op {i + 1} ('{name}') is not in catalog.json",
                            known=sorted(k for k in specs if k))
            schema = spec.get("paramsSchema") or {}
            params = op.get("params", {}) or {}
            if not isinstance(params, dict):
                return _err("E_BAD_PARAMS", f"op {i + 1} params must be an object")
            for req in schema.get("required", []) or []:
                if req not in params:
                    return _err("E_BAD_PARAMS", f"op {i + 1} missing required param '{req}'",
                                path=f"ops[{i}].params.{req}")
            if schema.get("additionalProperties") is False:
                allowed = set((schema.get("properties") or {}).keys())
                extra = sorted(set(params) - allowed)
                if extra:
                    return _err("E_BAD_PARAMS", f"op {i + 1} has unknown param(s): {', '.join(extra)}")
        return None

    # ---- tools -----------------------------------------------------------
    def drop_ticket(self, args: dict) -> dict:
        name = str(args.get("ticket", "")).strip()
        if not TICKET_RE.match(name) or name.startswith("."):
            return {"ok": False, "error": _err("E_BAD_TICKET", "bad ticket name")}
        payload = args.get("payload")
        if not isinstance(payload, dict):
            return {"ok": False, "error": _err("E_BAD_TICKET", "payload must be an object")}
        unknown = sorted(set(payload) - TICKET_FIELDS - {"comment"})
        if unknown:
            return {"ok": False, "error": _err("E_BAD_TICKET", "unknown ticket field(s): " + ", ".join(unknown))}
        protocol = payload.get("protocol", self.protocol)
        if protocol != self.protocol:
            return {"ok": False, "error": _err("E_PROTOCOL", "unsupported protocol",
                                               supported=[self.protocol], got=protocol)}
        ops = payload.get("ops")
        if not isinstance(ops, list) or not ops:
            return {"ok": False, "error": _err("E_BAD_TICKET", "payload.ops must be a non-empty list")}
        seen: set[str] = set()
        norm_ops = []
        for i, op in enumerate(ops, start=1):
            if not isinstance(op, dict):
                return {"ok": False, "error": _err("E_BAD_TICKET", f"ops[{i - 1}] must be an object")}
            bad = sorted(set(op) - OP_FIELDS)
            if bad:
                return {"ok": False, "error": _err("E_BAD_PARAMS", f"ops[{i - 1}] unknown field(s): " + ", ".join(bad))}
            if not isinstance(op.get("op"), str) or not op["op"]:
                return {"ok": False, "error": _err("E_BAD_PARAMS", f"ops[{i - 1}].op must be a string")}
            oid = str(op.get("id") or f"op{i}")
            if not TICKET_RE.match(oid) or oid in seen:
                return {"ok": False, "error": _err("E_BAD_OP_ID", f"bad or duplicate op id '{oid}'")}
            seen.add(oid)
            norm = dict(op)
            norm["id"] = oid
            norm_ops.append(norm)
        err = self._validate_ops_against_catalog(norm_ops)
        if err:
            return {"ok": False, "error": err}
        ticket = {
            "protocol": protocol,
            "ticket": name,
            "trial": str(payload.get("trial") or "t1"),
            "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "timeout_ms": int(payload.get("timeout_ms", DEFAULT_TIMEOUT_MS)),
            "on_error": payload.get("on_error", "abort"),
            "ops": norm_ops,
        }
        if payload.get("comment"):
            ticket["comment"] = payload["comment"]
        dst = self.ticket_path(name)
        tmp = dst + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(ticket, fh, ensure_ascii=False, indent=1)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, dst)  # atomic: the executor only ever sees complete .json
        return {"ok": True, "accepted": name, "path": dst, "protocol": protocol,
                "ops": [o["id"] for o in norm_ops]}

    def read_receipt(self, args: dict) -> dict:
        name = str(args.get("ticket", "")).strip()
        try:
            timeout = float(args.get("timeout_s", 5))
        except (TypeError, ValueError):
            timeout = 5.0
        timeout = max(0.0, min(timeout, MAX_TIMEOUT_S))
        path = self.receipt_path(name)
        deadline = time.time() + timeout
        while True:
            if os.path.isfile(path):
                try:
                    with open(path, encoding="utf-8") as fh:
                        data = json.load(fh)
                    if isinstance(data, dict):
                        data["ok"] = bool(data.get("ok"))
                        data["_receipt"] = path
                        return data
                except (OSError, ValueError):
                    pass
            if time.time() >= deadline:
                return {"ok": False, "error": _err("E_TIMEOUT", "receipt timeout", ticket=name)}
            time.sleep(0.2)

    def list_queue(self, args: dict) -> dict:
        which = str(args.get("which", "inbox"))
        if which not in ("inbox", "outbox", "done"):
            return {"ok": False, "error": _err("E_BAD_PARAMS", "which must be inbox|outbox|done")}
        files = sorted(f for f in os.listdir(self._dir(which)) if f.endswith(".json"))
        return {"ok": True, "which": which, "files": files}


TOOLS: dict[str, dict] = {
    "drop_ticket": {
        "desc": ("Write a modtest-bridge ticket into <dir>/inbox atomically (bridge picks it up). "
                 "Ops that touch the world act the way the player does: world.place only places the "
                 "block that is in the selected slot, and only within the player's block reach "
                 "(about 4.5 blocks; 5 in creative). Stay within reach instead of reaching across the "
                 "map: move the player first (input.set/pose.set) or place an adjacent cell, otherwise "
                 "the op is refused with E_PRECONDITION reason=out-of-reach, and a wrong/missing item "
                 "in hand is refused with reason=held-item-mismatch / empty-hand. "
                 "state.query can read a block cell (what=['block'],x,y,z -> blockKnown/block/"
                 "blockReplaceable, where unknown is null and NEVER air) and a cheap moving bit; "
                 "input.stop{mode:'safe'|'immediate'} cancels injected movement, and it reports "
                 "armed:true (not stopped) when it could only wait for a safe point. E_SUPERSEDED/"
                 "E_STOPPED/E_NO_PATH/E_STUCK are non-failure terminations, not defects."),
        "schema": {"type": "object",
                   "properties": {"ticket": {"type": "string"},
                                  "payload": {"type": "object"}},
                   "required": ["ticket", "payload"]},
    },
    "read_receipt": {
        "desc": "Read <dir>/outbox/<ticket>.result.json (polls up to timeout_s).",
        "schema": {"type": "object",
                   "properties": {"ticket": {"type": "string"},
                                  "timeout_s": {"type": "number"}},
                   "required": ["ticket"]},
    },
    "list_queue": {
        "desc": "List inbox/outbox/done (self-check for a broken link).",
        "schema": {"type": "object",
                   "properties": {"which": {"type": "string"}},
                   "required": []},
    },
}
TOOL_FNS: dict[str, Callable[[dict], dict]] = {
    "drop_ticket": lambda a: _BRIDGE.drop_ticket(a),
    "read_receipt": lambda a: _BRIDGE.read_receipt(a),
    "list_queue": lambda a: _BRIDGE.list_queue(a),
}
_BRIDGE: Bridge = Bridge("./.modtest-agent")


def configure(base: str, protocol: str = PROTOCOL_ID, check_catalog: bool = True) -> Bridge:
    global _BRIDGE
    _BRIDGE = Bridge(base, protocol, check_catalog)
    return _BRIDGE


def handle(msg: dict) -> dict | None:
    mid = msg.get("id")
    method = msg.get("method", "")
    params = msg.get("params", {}) or {}
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid,
                "result": {"protocolVersion": "2024-11-05",
                           "capabilities": {"tools": {},
                                            "experimental": {"modtestBridge": {
                                                "protocol": _BRIDGE.protocol,
                                                "dir": _BRIDGE.base,
                                                "catalog": bool(_BRIDGE.catalog())}}},
                           "serverInfo": {"name": SERVER_NAME, "version": __version__}}}
    if method == "notifications/initialized":
        return None
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": mid,
                "result": {"tools": [{"name": n, "description": t["desc"],
                                      "inputSchema": t["schema"]} for n, t in TOOLS.items()]}}
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments", {}) or {}
        if name not in TOOL_FNS:
            return {"jsonrpc": "2.0", "id": mid,
                    "error": {"code": -32602, "message": f"unknown tool: {name}"}}
        try:
            result = TOOL_FNS[name](args)
        except Exception as exc:  # noqa: BLE001 - the receipt channel must never throw
            result = {"ok": False, "error": _err("E_EXEC", f"{type(exc).__name__}: {exc}")}
        return {"jsonrpc": "2.0", "id": mid,
                "result": {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]}}
    if method.startswith("notifications/"):
        return None
    return {"jsonrpc": "2.0", "id": mid,
            "error": {"code": -32601, "message": f"unknown method: {method}"}}


def _respond(obj: dict, out) -> None:
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    out.write(f"Content-Length: {len(body)}\r\n\r\n".encode("latin-1"))
    out.write(body)
    out.flush()


def _read_msg(inp) -> dict | None:
    headers: dict[str, str] = {}
    while True:
        raw = inp.readline()
        if not raw:
            return None
        line = raw.decode("latin-1").strip()
        if not line:
            break
        if ":" in line:
            key, value = line.split(":", 1)
            headers[key.strip().lower()] = value.strip()
    try:
        length = int(headers.get("content-length", "0"))
    except ValueError:
        return None
    if length <= 0:
        return None
    data = inp.read(length)
    if not data:
        return None
    return json.loads(data.decode("utf-8"))


def serve() -> int:
    inp, out = sys.stdin.buffer, sys.stdout.buffer
    while True:
        msg = _read_msg(inp)
        if msg is None:
            return 0
        resp = handle(msg)
        if resp is not None:
            _respond(resp, out)


def _default_dir() -> str:
    return os.environ.get("MODTEST_AGENT_DIR", os.path.join(os.getcwd(), ".modtest-agent"))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="modtest-mcp",
                                 description="MCP server for a local mod-test file bridge "
                                             f"({PROTOCOL_ID}); stdio, stdlib only.")
    ap.add_argument("--dir", default=_default_dir(),
                    help="bridge directory (default: $MODTEST_AGENT_DIR or ./.modtest-agent)")
    ap.add_argument("--protocol-version", default=PROTOCOL_ID,
                    help=f"protocol id written into tickets (default: {PROTOCOL_ID})")
    ap.add_argument("--no-catalog-check", action="store_true",
                    help="do not validate op names/params against <dir>/catalog.json")
    ap.add_argument("--version", action="version", version=f"{SERVER_NAME} {__version__}")
    args = ap.parse_args(argv)
    configure(args.dir, args.protocol_version, not args.no_catalog_check)
    return serve()


if __name__ == "__main__":
    sys.exit(main())
