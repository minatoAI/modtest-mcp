# Third-party notices and prior-art statement

This project is licensed **GPL-3.0-or-later** (see `LICENSE`). Everything in this repository was
written for this project. Where the design was informed by prior art, the rule is: **ideas only,
zero verbatim copying**.

## 1. Prior art consulted (ideas only)

| Project | License | What was taken | What was NOT taken |
|---|---|---|---|
| Baritone | LGPL-3.0 | Conceptual only: a long-lived *tick-driven task queue* pattern (a queue of steps advanced one per client tick) so automation state is visible and interruptible | **No source code, no copied functions, no copied comments, no copied identifiers.** Baritone is not linked, not bundled, not vendored |
| Meteor Client | GPL-3.0 | Conceptual only: module metadata conventions (name/description/per-module settings) as an ergonomic pattern for self-describing capabilities | **No source code, no copied text/identifiers.** Meteor is not linked, not bundled, not vendored |

Neither project is a dependency of this repository: the runtime dependency list is **empty**
(standard library only), and no third-party binary, jar or source tree is redistributed here.

## 2. Recomputable evidence for the "zero verbatim copying" claim

The claim above is not an assurance — it is checked by a **reproducible textual-similarity audit**
whose inputs (upstream commit ids) and criteria are recorded so anyone can re-run it:

* Audit record: `docs/review/oldtrack-opt-audit-phase5.md` — **§P7** (in the development
  repository that produced this tool).
* Upstream references pinned by the audit:
  * Baritone `efed17c8…`
  * Meteor Client `8819b2e1…` and `6d76982a…`
* Criteria used, and the results that must hold to keep the claim true:
  * shared word windows of **≥ 12 words**: **0**
  * consecutive identical lines (**≥ 2 lines**): **0**
  * whole-line matches of **≥ 6 words**: **7**, every one of them a **generic idiom**
    (e.g. ordinary Java/GLSL boilerplate), reviewed individually.

If a future change to this repository copies text from a third-party project, this table and the
audit record must be updated in the same commit. Do not cite any *other* similarity figure: an
earlier internal count ("2178 / 8 处") came from a superseded method and is **deprecated** — §P7 is
the single source of truth.

## 3. Names and trademarks

Minecraft is a trademark of Mojang Synergies AB / Microsoft. This project is **not** an official
Minecraft product and is not approved by or associated with Mojang or Microsoft. "Minecraft" is
used here only as a **secondary descriptor** (as required by the Minecraft Usage Guidelines naming
rules); it is not used as a product name, logo or dominant element. No Minecraft assets or artwork
are redistributed.

Model Context Protocol (MCP) is a protocol specification; this repository implements a client-side
server for it using only the Python standard library.
