"""modtest-mcp: MCP server + reference agent side for the modtest-bridge/1.0 protocol."""

from .server import Bridge, PROTOCOL_ID, __version__, configure, main  # noqa: F401

__all__ = ["Bridge", "PROTOCOL_ID", "__version__", "configure", "main"]
