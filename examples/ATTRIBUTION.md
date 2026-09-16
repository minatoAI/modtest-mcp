# Attribution and provenance

The design of `modtest-bridge/1.0` and the analysis tools in `tools/` was distilled from a private,
single-project development rig built for the **TacLight** Minecraft mod (a spotlight/volumetric-light
mod). That rig is not redistributed here; what is redistributed is the *generalised* result:

* the wire protocol was redesigned from scratch (JSON-Schema op descriptions, explicit error codes,
  version negotiation, safety vocabulary) — it is **not** wire-compatible with the rig's v0.2
  bridge, and the rig itself was left untouched;
* the analysis tools were generalised: absolute paths removed, fixed run/instance names removed,
  the recorder header signature made configurable, and internal-only references dropped;
* no game code, no mod code and no third-party source is included.

Licensing: this repository is GPL-3.0-or-later. Prior art that informed the *ideas* (Baritone,
LGPL-3.0; Meteor Client, GPL-3.0) is documented in [`../THIRD_PARTY.md`](../THIRD_PARTY.md), together
with the recomputable "zero verbatim copying" evidence pointer (similarity audit §P7).

Trademark note: "Minecraft" is a trademark of Mojang Synergies AB / Microsoft. This project is not
an official Minecraft product and is not approved by or associated with Mojang or Microsoft;
"Minecraft" appears only as a secondary descriptor.
