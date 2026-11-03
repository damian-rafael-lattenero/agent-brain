# Installing brain-mcp in ZCode

`brain-mcp` is a local ZCode plugin: an MCP server over stdio that exposes the
running agent-brain as four read-only tools — `rag_query` (hybrid retrieval
with citations), `memory_search`, `memory_recent` and `bus_status`. The plugin
owns no database of its own; every tool is an HTTP call to `agent-brain serve`.

This document is the whole install: build the plugin directory, add it as a
local marketplace, install `brain-mcp`, verify with `rag_query`. Nothing here
is run by the repo itself — the commands are yours to run, and they never need
to write into `~/.zcode/agent-brain`.

## Prerequisites

- A JDK 21+ on `PATH` (or `JAVA_HOME` set). The distribution is built with
  Amazon Corretto 25, and its bytecode targets 21.
- The brain running: `make up` (Postgres + migrations) and `make serve`
  (`http://127.0.0.1:8765` by default). The plugin starts fine without it; the
  tool calls answer `brain_unavailable` until it is up.
- Something indexed, or `rag_query` has nothing to cite: `make index-repos`
  (the owner's repositories) or `make index` (the corpus file).

## 1. Build the plugin

```bash
cd /path/to/agent-brain
make plugin          # == ./gradlew :brain-mcp:plugin
```

The task produces:

```
brain-mcp/build/plugin/
├── marketplace.json                 # the local marketplace catalog
└── brain-mcp/                       # the plugin (the marketplace's only entry)
    ├── .zcode-plugin/plugin.json    # the plugin manifest
    ├── bin/brain-mcp                # launcher (the installed distribution)
    ├── bin/brain-mcp.bat
    └── lib/*.jar                    # the server and its dependencies
```

`marketplace.json` and `.zcode-plugin/plugin.json` are copied from
`brain-mcp/plugin/` in the repo; `brain-mcp/build/plugin` is the directory you
add to ZCode.

## 2. The two JSON files (exact)

`brain-mcp/build/plugin/marketplace.json` — the marketplace catalog. The
marketplace is named `dev-agent-brain`, and the plugin below it is `brain-mcp`:

```json
{
  "name": "dev-agent-brain",
  "plugins": [
    {
      "name": "brain-mcp",
      "source": "./brain-mcp",
      "version": "0.1.0",
      "description": "agent-brain tools for the harness: rag_query, memory_search, memory_recent, bus_status.",
      "displayName": "Agent Brain"
    }
  ]
}
```

`source` is relative to the marketplace root (the directory that contains
`marketplace.json`), so the plugin id used by the CLI is
`brain-mcp@dev-agent-brain`.

`brain-mcp/build/plugin/brain-mcp/.zcode-plugin/plugin.json` — the plugin
manifest, including the MCP server declaration:

```json
{
  "name": "brain-mcp",
  "version": "0.1.0",
  "description": "agent-brain inside the harness: rag_query (hybrid retrieval with citations), memory_search / memory_recent (durable memories about you and your projects), bus_status (event bus health). Every answer is a status envelope with a trace id.",
  "author": { "name": "Damian Lattenero" },
  "license": "MIT",
  "mcpServers": {
    "brain": {
      "type": "stdio",
      "command": "${ZCODE_PLUGIN_ROOT}/bin/brain-mcp",
      "args": [],
      "env": {
        "AB_HTTP_PORT": "8765"
      }
    }
  },
  "homepage": "https://github.com/damian-rafael-lattenero/agent-brain",
  "keywords": ["rag", "memory", "agent", "bus", "kotlin"]
}
```

`${ZCODE_PLUGIN_ROOT}` resolves to the installed copy of the `brain-mcp`
directory. `env.AB_HTTP_PORT` pins the port the plugin calls: if the brain
listens on another port, change that value (the manifest overwrites the
`http_port` from the config file). For a non-default brain base, add an
argument instead of editing the config path:

```json
"args": ["--ab-base", "/custom/agent-brain-base"]
```

The server accepts only `--ab-base DIR` and `--ab-config FILE`; any other
argument refuses to start. The manifest and the marketplace entry must agree
on `name`, `version` and `description`; bump both in
`brain-mcp/plugin/.zcode-plugin/plugin.json` and
`brain-mcp/plugin/marketplace.json` when you change the plugin.

## 3. Add the local marketplace and install

UI: open a workspace (the plugin manager asks for one), then Settings → Plugins
→ Create → Add marketplace. Enter or choose the absolute path to
`brain-mcp/build/plugin` (the directory containing `marketplace.json`, not its
parent). ZCode validates the catalog, shows `dev-agent-brain` under
"Personal", and `brain-mcp` ("Agent Brain") appears there; click Install.
Newly installed plugins are enabled by default. Start a new session so the MCP
server joins it.

CLI, when the `zcode` binary is on `PATH` (it is not installed on the machine
this repo was verified on; the UI does the same thing):

```bash
cd /path/to/agent-brain
zcode plugins marketplace add "$PWD/brain-mcp/build/plugin" --scope user
zcode plugins install brain-mcp@dev-agent-brain --scope user
zcode plugins enable brain-mcp@dev-agent-brain --scope user
zcode plugins list --json
```

Use `--scope workspace` instead of `--scope user` to enable it only for the
current workspace. The last command must list `brain-mcp` as installed and
enabled, with the `brain` MCP server attached.

## 4. Verify

1. In Settings → Plugins → "Manage installed", `brain-mcp` is listed with its
   MCP server (`brain`); Settings → MCP should show that server once a session
   runs. Start a new ZCode session (plugins are loaded at session start) and
   ask the model to call `rag_query`, for example: *"use rag_query to find how
   the corpus resolves the walk root, k=3"*.
2. The answer is the standard envelope, `status: "ok"` and citations with
   `uri`, `heading`, `ordinal` and `score`, plus `meta.trace_id`:

   ```json
   {"status":"ok","result":{"hits":[...],"citations":[...]},
    "warnings":[],"nextStep":"cite the uri and heading of what you use; index more sources with `agent-brain index`",
    "meta":{"trace_id":"trace-1793228960047-3","tool_version":1}}
   ```

3. Without ZCode, the same binary over the real MCP protocol (no brain
   needed for this step — it only proves the plugin loads and lists its
   tools; press Ctrl-D to close):

   ```bash
   printf '%s\n%s\n' \
     '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"cli","version":"0"}}}' \
     '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
     | brain-mcp/build/plugin/brain-mcp/bin/brain-mcp --ab-base "$(mktemp -d)"
   ```

   It prints the initialize result (`serverInfo.name = "brain-mcp"`) and the
   four tools: `rag_query`, `memory_search`, `memory_recent`, `bus_status`.
   The project's own e2e (`./gradlew :brain-mcp:test`) does this against the
   installed binary and every tool.

## 5. Update after a rebuild

Installed plugins are copies of the marketplace source, not symlinks. After a
code change:

```bash
make plugin
zcode plugins marketplace update dev-agent-brain
zcode plugins install brain-mcp@dev-agent-brain --scope user
```

In the UI: gear icon → "Marketplace sources" → "Refresh this marketplace",
then reinstall `brain-mcp` from the plugin card if the installed copy still
carries the old files. Start a new session either way.

Uninstall:

```bash
zcode plugins uninstall brain-mcp@dev-agent-brain
zcode plugins marketplace remove dev-agent-brain
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Every tool answers `{"status":"error","error":{"kind":"brain_unavailable",...}}` | `agent-brain serve` is not running, or the port differs | `make serve`; align `AB_HTTP_PORT` in the manifest with the brain's `http_port` |
| The marketplace is not accepted | The path points at `build/plugin/brain-mcp` or at the repo root | Add the directory that directly contains `marketplace.json`: `brain-mcp/build/plugin` |
| `brain-mcp` is listed but the tools are missing | The session started before install/enable | Start a new ZCode session |
| The launcher exits with a Java error | No JDK 21+ on `PATH` and no `JAVA_HOME` | Install/point to a JDK 21+ (Corretto 25 is what the repo builds with) |
| `brain-mcp: unknown arguments: [...]` | The manifest passes something other than `--ab-base DIR` / `--ab-config FILE` | Fix `mcpServers.brain.args` |
| MCP errors about malformed stdout | Something wrote a banner to stdout before the transport | Not expected: the server redirects stdout to stderr during startup and logs on stderr; check the plugin's stderr in ZCode |
| `rag_query` returns no hits | Nothing indexed yet, or the query embedding provider is down | `make index-repos` / `make index`; check `agent-brain health` |

All four tools are read-only; nothing in the plugin writes to your repository
or to `~/.zcode`.
