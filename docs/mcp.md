# The MCP surface: stdio plugin and a token-gated HTTP endpoint

`brain-mcp` exposes the brain as four read-only MCP tools. It has always done
that as a **stdio** plugin for this machine's ZCode (P5). U8 adds the second
half: the **same four tools over the MCP streamable HTTP transport**, on
loopback, behind a **bearer token** — so another machine or another MCP client
can use the brain without shipping it a plugin directory.

```bash
# the plugin (unchanged, the default: no arguments = stdio)
brain-mcp

# the service (new): MCP over streamable HTTP on http://127.0.0.1:8767/mcp
AB_MCP_TOKEN_FILE=~/.zcode/agent-brain/mcp.token brain-mcp serve --transport http --port 8767
```

- `docs/install-brain-mcp.md` — installing the stdio plugin in ZCode.
- this document — the HTTP endpoint, its token, its client contract, and the
  decision record behind both.

---

## 1. Two transports, one surface

| | stdio plugin | streamable HTTP service |
|---|---|---|
| Process | spawned by the harness, one per session | long-lived, its own process |
| Auth | none: the harness owns the pipes | **bearer token, mandatory** |
| Address | — | `http://127.0.0.1:8767/mcp` (loopback only) |
| Tools | `rag_query`, `memory_search`, `memory_recent`, `bus_status` | identical, same schemas |
| Response shape | the status envelope | the status envelope |
| Client | this machine's ZCode | any MCP client, including another machine via `ssh -L` |

The two transports share ONE implementation: `newServer(wiring, http, traces,
served)` in `brain-mcp/src/main/kotlin/dev/lattenero/brain/mcp/Main.kt`, the
`BrainTools` registry in `Tools.kt`, and the envelope. A tool cannot exist on
one transport and not the other, and the HTTP endpoint cannot answer with a
different shape than the plugin — there is no second handler to drift.

## 2. Why this shape (decisions and the rejected alternatives)

### 2a. A separate process and port, not a route on `agent-brain serve`

The mission allowed either. Mounting `/mcp` on the brain's own ktor server
(:8765) would have saved a process, but it fails the security rule the owner
set for U8:

- The brain's HTTP API is **unauthenticated by design** (it is loopback-only)
  and it **mutates**: `POST /memory/save`, `POST /rag/index`, `POST /rag/query`
  with arbitrary globs. The documented path to a remote client is `ssh -L`, and
  a tunnel to :8765 exposes the whole API, not just MCP.
- A tunnel to **:8767** exposes one narrow, token-gated, read-only surface. That
  is the difference between "opening a door" and "opening the house".

Secondary benefit: the module boundary stays intact — core owns the brain,
brain-mcp owns the MCP surface — and the running LaunchAgent
(`com.zcode.agent-brain`) is untouched by this work.

Cost: one more process to keep alive (see §3 for the launchd shape).

### 2b. A facade, not a second brain

`brain-mcp serve --transport http` builds **no** `Brain`: no connection pool, no
embedding provider, no second bus tailer. Every tool makes the same loopback
HTTP call the stdio plugin makes (`/rag/query`, `/memory/search`,
`/memory/recent`, `/bus/status`).

The alternative — an in-process `Brain` in the MCP process — was rejected on
correctness: `bus_status` reads the tailer's own counters, and a second tailer
that is not tailing would report a lag of zero forever. A lie in a health tool
is worse than an extra hop (the hop is loopback and ≈1 ms).

### 2c. Streamable HTTP with sessions (not SSE, not stateless)

- **SSE (2024-11-05)** is deprecated by the MCP Kotlin SDK (`@Deprecated` on
  `Application.mcp`), so a new endpoint should not start there.
- **Stateless streamable HTTP** (`mcpStatelessStreamableHttp`) serves one request
  per session and rejects `GET`/`DELETE`; it also gives up the server→client
  stream clients may open.
- **Stateful streamable HTTP** (`mcpStreamableHttp`) is what the SDK recommends
  and what mainstream clients implement: `POST /mcp` for messages, `GET /mcp` for
  the optional stream, `DELETE /mcp` to end a session, `Mcp-Session-Id` in a
  header. Sessions are the SDK's, bounded to **64** and idle-expired after
  **30 minutes**, so an abandoned client cannot pin memory.

The 401 gate runs per **request**, not per session: a valid `Mcp-Session-Id` is
not a credential.

## 3. Running the endpoint

### 3a. The token

The token is a secret with the same treatment as `GLM_API_KEY` (ADR-002): it is
**never** in `config.json`, never in argv, never in a log line, never in a JSON
body or an error message. `McpToken.toString()` is redacted; the only thing that
may be logged is the SOURCE (`env` or `file`).

Two ways to provide it, `env` winning over the file:

```bash
# 1. environment (good for a shell, containers, CI)
export AB_MCP_TOKEN="$(openssl rand -hex 32)"

# 2. a file (better for launchd: the plist is world-readable, `ps -E` is not
#    the place for a secret, and rotation is editing one file)
umask 077
openssl rand -hex 32 > ~/.zcode/agent-brain/mcp.token
chmod 600 ~/.zcode/agent-brain/mcp.token
export AB_MCP_TOKEN_FILE=~/.zcode/agent-brain/mcp.token
```

Rules enforced at startup (**fail closed**, exit code 2 with an actionable
message, no server bound):

| input | result |
|---|---|
| neither variable | refuses: `no token: set AB_MCP_TOKEN or AB_MCP_TOKEN_FILE …` |
| empty / whitespace | refuses (same message) |
| shorter than 16 chars | refuses: `the token is too short (n chars, minimum 16) …` |
| unreadable file | refuses with the path (never the contents) |

Rotation: write the new value to the file (or restart with the new env), then
`launchctl kickstart -k gui/$(id -u)/com.zcode.brain-mcp`. Old clients get 401
from the first request after restart; nothing is cached.

### 3b. Start it

```bash
# foreground
AB_MCP_TOKEN_FILE=~/.zcode/agent-brain/mcp.token \
  brain-mcp serve --transport http --port 8767

# a LaunchAgent that survives reboots (the brain's own agent is the model)
```

```xml
<!-- ~/Library/LaunchAgents/com.zcode.brain-mcp.plist (sketch) -->
<key>ProgramArguments</key>
<array>
  <string>/path/to/brain-mcp/bin/brain-mcp</string>
  <string>serve</string><string>--transport</string><string>http</string>
  <string>--port</string><string>8767</string>
</array>
<key>EnvironmentVariables</key>
<dict>
  <!-- the token goes in the file, not here: the plist is readable -->
  <key>AB_MCP_TOKEN_FILE</key><string>/Users/you/.zcode/agent-brain/mcp.token</string>
</dict>
<key>RunAtLoad</key><true/>
<key>KeepAlive</key><true/>
```

Flags and their defaults:

| flag | default | meaning |
|---|---|---|
| `--transport stdio\|http` | `stdio` | no arguments is still the plugin: compat is a requirement |
| `--port N` | `8767` | the HTTP port |
| `--host H` | `127.0.0.1` | loopback only; `0.0.0.0`, `::`, `10.x` are **refused** |
| `serve` (verb) | implied | accepted for symmetry with `agent-brain serve` |
| `--ab-base DIR`, `--ab-config FILE` | `~/.zcode/agent-brain` | where the brain's config lives (same as before) |

A non-loopback `--host` is not a warning, it is an error:

```
$ brain-mcp serve --transport http --host 0.0.0.0 --port 8768
brain-mcp: refusing to bind 0.0.0.0: the MCP endpoint stays on loopback (127.0.0.1, ::1, localhost).
For another machine use an SSH tunnel — ssh -L 8767:127.0.0.1:8767 <host> — see docs/mcp.md
$ echo $?
2
```

### 3c. Liveness

`GET /health` needs no token and carries no configuration (no base URL, no
token, no paths) — it answers "is the process up, and how many tool calls has it
served":

```json
{"status":"ok","server":"brain-mcp","version":"0.1.0","transport":"streamable-http","endpoint":"/mcp","tools":4,"calls":2}
```

A tool call against a down brain is NOT a 5xx: it is the ordinary
`brain_unavailable` envelope, so a client keeps a working MCP session through a
brain restart.

## 4. Connecting an external client

Any MCP client that speaks streamable HTTP with custom headers. The two things
it needs: the **URL** and the **header**.

### 4a. Generic configuration (most clients accept this JSON shape)

```json
{
  "mcpServers": {
    "agent-brain": {
      "type": "http",
      "url": "http://127.0.0.1:8767/mcp",
      "headers": {
        "Authorization": "Bearer <paste the token here>"
      }
    }
  }
}
```

Notes for specific clients:

- **Claude Desktop / Claude Code** (`claude_desktop_config.json` /
  `.mcp.json`): the block above goes under `mcpServers`; `type` may be omitted.
- **Cursor**: same shape under `mcpServers` (`url` + `headers`).
- **VS Code / Cline / Continue**: "Streamable HTTP" transport, URL and an
  `Authorization` header.
- **Zed**: `context_servers` entry with `"command"` for stdio; for HTTP use its
  `custom` server with the URL and header.
- **Official inspector** (quick check):

  ```bash
  npx @modelcontextprotocol/inspector \
    --cli http://127.0.0.1:8767/mcp --transport http \
    --header "Authorization: Bearer $AB_MCP_TOKEN" --method tools/list
  ```

Two configured guards worth knowing about, because they answer 403 (not 401)
before our token is even looked at: the SDK's DNS-rebinding protection only
accepts `Host: localhost`, `127.0.0.1` or `[::1]`, and validates `Origin` against
the same set. That is why the tunnel below is the supported path: a reverse
proxy that preserves its own hostname (`Host: brain.example.com`) is rejected —
deliberately, since it is the rebinding scenario.

A client that DOESN'T send the header gets `401` with
`WWW-Authenticate: Bearer realm="brain-mcp"`. Some clients (the inspector among
them) read that as "OAuth discovery" and try `/.well-known/...`, which this
endpoint does not serve (404). Our auth is a pre-shared token, not OAuth: the
header is configured statically, there is no login flow.

### 4b. From another machine: `ssh -L`, never a rebind

The endpoint binds loopback by construction, which is the point. The supported
path from a second machine is a tunnel — the same shape for an SSH client
config, a mobile client, or a container on the same host:

```bash
# on the remote machine (or in its ssh config: LocalForward 8767 127.0.0.1:8767)
ssh -N -L 8767:127.0.0.1:8767 you@brain-host
```

Then point the client at `http://127.0.0.1:8767/mcp` **on the remote machine**,
with the token. The tunnel authenticates the transport (SSH), the token
authenticates the MCP client (defence in depth: a leaked port forward is not
enough, and a leaked token is not enough without SSH access).

Do NOT re-bind the endpoint to `0.0.0.0` to "make it simpler": `--host` refuses
it, and the reason is in §2a — the port would become a public read of the whole
indexed corpus, memory and bus state.

## 5. The tool contract

Every tool is read-only. Every response is the same envelope:

```json
{
  "status": "ok",
  "result": { ... },
  "warnings": [],
  "nextStep": "cite the uri and heading of what you use; …",
  "meta": { "trace_id": "trace-1793760680030-1", "tool_version": 1 }
}
```

```json
{
  "status": "error",
  "error": { "kind": "invalid_input | brain_unavailable | internal_error", "message": "…" },
  "nextStep": "…",
  "meta": { "trace_id": "…", "tool_version": 1 }
}
```

`status: "error"` is still a **successful MCP call** (`isError` is not set): the
transport worked, the tool answered. Clients branch on the envelope, not on the
JSON-RPC result.

### 5.1 `rag_query` — hybrid retrieval with citations

```json
{"name":"rag_query","arguments":{"query":"how does the bus tailer detect a truncated file","k":3}}
```

`query` (required, string), `k` (1..50, default 5).

```json
{
  "status": "ok",
  "result": {
    "query": "how does the bus tailer detect a truncated file",
    "embedModel": "nomic-embed-text",
    "hits": [ {"chunkId": 10759, "docId": 2751, "uri": "/…/tailer-truncation.md",
               "title": "tailer-truncation.md", "heading": "… > Bus tailer and truncation",
               "ordinal": 0, "content": "…", "score": 0.0164, "vectorRank": 1, "textRank": null} ],
    "citations": [ {"uri": "/…/tailer-truncation.md", "title": "tailer-truncation.md",
                    "heading": "… > Bus tailer and truncation", "ordinal": 0, "score": 0.0164} ]
  },
  "warnings": [],
  "nextStep": "cite the uri and heading of what you use; index more sources with `agent-brain index`",
  "meta": { "trace_id": "trace-1793760680030-1", "tool_version": 1 }
}
```

Cite from `citations` (uri + heading); `hits` carries the chunk text.

### 5.2 `memory_search` — durable memories by relevance

```json
{"name":"memory_search","arguments":{"query":"embedding provider preference","k":2}}
```

`query` (required), `k` (1..50, default 5). `result.memories[]` with
`id, kind, content, sessionId, turnId, salience, createdAt, score, weight`.
Recall is hybrid: the fused relevance (`score`) orders the hits, and
`weight` (`salience` tempered by a half-life decay on age) only breaks ties.

### 5.3 `memory_recent` — newest first, optional kind filter

```json
{"name":"memory_recent","arguments":{"limit":2,"kind":"preference"}}
```

`limit` (1..50, default 5), `kind` (`fact | decision | preference | summary`,
optional). `result` is the array of memories (no `score` when the read is not a
search).

### 5.4 `bus_status` — the tailer/consumer counters

```json
{"name":"bus_status","arguments":{}}
```

`nowait` (boolean, ignored — schema symmetry). `result` is the tailer snapshot:

```json
{"transport":"file","file":"/Users/you/.zcode/agent-brain/bus-events.jsonl","fileExists":true,
 "fileBytes":4496623,"offset":4496623,"lagBytes":0,"linesRead":559,"malformed":0,"shrinks":0,
 "seen":559,"inserted":559,"duplicates":0,"dbEvents":7538,"lastTs":"2026-11-04T02:53:35.646Z",
 "lastKind":"tool.call","restarts":0,"heartbeatAgeMs":2038,"kafkaCommits":null,"kafkaOffsets":null}
```

A growing `lagBytes` means the brain is down or the bus moved; `malformed` and
`duplicates` are the pipeline's own error counters. This is the reason the
facade calls the real brain (§2b): these numbers belong to the one live tailer.

## 6. Security properties (what the token does and does not protect)

- **Mandatory.** No token, a blank one, a short one, or an unreadable file means
  no server (fail closed). There is no "auth off" flag.
- **Constant time.** The guard SHA-256s the expected token once and the candidate
  on every request, then compares two fixed-width digests with
  `MessageDigest.isEqual`. The comparison is independent of the candidate's
  content and length, so it reveals neither a correct prefix nor the token's
  size. Measured evidence: the median batch ratio between a wrong first byte and
  a wrong last byte is **0.99** (a short-circuit would be ≈0.015 for a 64-char
  token); the test asserts a median above 0.2 and prints the batches
  (`TokenAuthTest`).
- **Per request, before routing.** The guard is a ktor interceptor in the
  `Plugins` phase: it runs before route resolution and before any MCP handler
  sees a byte. It applies to `POST`, `GET` and `DELETE`, and to a request that
  reuses a valid `Mcp-Session-Id` (a session id is not a credential).
- **No oracle.** Missing header, empty header, another scheme (`Basic`), a
  truncated token and a wrong token all get the same
  `401 {"error":"unauthorized","message":"missing or invalid bearer token"}`.
- **Never logged, never stored.** The token is not in `config.json`, not in
  files the repo ignores, not in argv, not in the log (the startup line records
  only `token from env|file`), not in a 401 body, not in `/health`.
- **Loopback only.** `--host` is validated against `InetAddress.isLoopbackAddress`
  for every resolved address; a non-loopback value exits 2 with the tunnel hint.
  The DNS-rebinding protection the SDK installs stays on (Host/Origin allowlist
  `localhost`, `127.0.0.1`, `[::1]`).
- **Read-only surface.** Four tools, all GET-shaped: the endpoint cannot save
  a memory, index a corpus, or send anything. A stolen token reads the corpus
  and the memories; it cannot write.
- **What it does NOT protect:** other local processes (they can read the token
  file if the mode allows it — hence `chmod 600`), and the brain's own API on
  :8765 (unauthenticated loopback, unchanged, never tunneled). Rate limiting and
  per-client tokens are deliberately absent: one owner, one token; adding
  multiple tokens without a reason would be surface without a use.

## 7. Evidence (the U8 gate run, 2026-11-04)

A **separate process** (`brain-mcp serve --transport http`, the installed
distribution, port 8767) talking to the **real running brain** (`:8765`, real
Postgres, real `nomic-embed-text`, the real index). The token below is the
contents of `AB_MCP_TOKEN_FILE`; it is elided as `$TOKEN` everywhere.

```
### 1. POST /mcp with NO token
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="brain-mcp"
Content-Length: 68
Content-Type: application/json

{"error":"unauthorized","message":"missing or invalid bearer token"}

### 2. POST /mcp with a WRONG token
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer realm="brain-mcp"
Content-Length: 68
Content-Type: application/json

{"error":"unauthorized","message":"missing or invalid bearer token"}

### 3. initialize WITH the token
$ curl -s -D headers -X POST http://127.0.0.1:8767/mcp \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl-external","version":"0"}}}'
HTTP/1.1 200 OK
mcp-session-id: 081b5eaa-340c-4dc0-84ee-3deeb85e4ad7

{"id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"brain-mcp","version":"0.1.0"},…},"jsonrpc":"2.0"}

### 4. tools/list (same session, same token)
- rag_query      | required: ['query'] | Hybrid (semantic + full-text) search over the indexed corpus
- memory_search  | required: ['query'] | Recall durable memories (facts, decisions, preferences) …
- memory_recent  | required: []         | The most recent memories (newest first) …
- bus_status     | required: []         | The event bus tailer/consumer counters …

### 5. tools/call rag_query — REAL citations from the REAL index
{"status":"ok","result":{"query":"how does the bus tailer detect a truncated file",
 "embedModel":"nomic-embed-text",
 "citations":[
  {"uri":"/Users/…/agent-brain/core/src/main/resources/eval/corpus/tailer-truncation.md",
   "heading":"… > Bus tailer and truncation","score":0.0161},
  {"uri":"/Users/…/agent-brain/core/src/test/kotlin/dev/lattenero/brain/bus/FileTailerTest.kt",
   "heading":"…","score":0.0159}]},
 "nextStep":"cite the uri and heading of what you use; …",
 "meta":{"trace_id":"trace-1793760680030-1","tool_version":1}}

### 6. A VALID session id without the token is still 401
$ curl -i -X POST http://127.0.0.1:8767/mcp -H "Mcp-Session-Id: 081b5eaa-…" …
HTTP/1.1 401 Unauthorized
{"error":"unauthorized","message":"missing or invalid bearer token"}

### 7. The official MCP inspector, as an independent client
$ npx @modelcontextprotocol/inspector --cli http://127.0.0.1:8767/mcp --transport http \
    --header "Authorization: Bearer $TOKEN" --method tools/list
{ "tools": [ { "name": "rag_query", … }, { "name": "memory_search", … },
             { "name": "memory_recent", … }, { "name": "bus_status", … } ] }

$ npx @modelcontextprotocol/inspector --cli http://127.0.0.1:8767/mcp --transport http \
    --header "Authorization: Bearer $TOKEN" --method tools/call \
    --tool-name rag_query --tool-arg query="memory half-life decay" k=2
{ "content": [ { "type": "text", "text": "{\"status\":\"ok\",\"result\":{\"query\":\"memory half-life decay\",
  \"embedModel\":\"nomic-embed-text\",\"hits\":[…],\"citations\":[…]},\"nextStep\":\"…\",
  \"meta\":{\"trace_id\":\"trace-1793760680030-2\",\"tool_version\":1}}" } ] }

### 8. Fail closed on the real binary
$ brain-mcp serve --transport http --host 0.0.0.0 --port 8768
brain-mcp: refusing to bind 0.0.0.0: the MCP endpoint stays on loopback (127.0.0.1, ::1, localhost).
For another machine use an SSH tunnel — ssh -L 8767:127.0.0.1:8767 <host> — see docs/mcp.md
$ echo $?   # 2

$ env -u AB_MCP_TOKEN_FILE brain-mcp serve --transport http --port 8768
brain-mcp: no token: set AB_MCP_TOKEN or AB_MCP_TOKEN_FILE (generate one with `openssl rand -hex 32`)
$ echo $?   # 2

### 9. The process's own log (28 lines) contains no token; the 401s are there, redacted
$ grep -Fq "$(cat ~/.zcode/agent-brain/mcp.token)" brain-mcp.log && echo LEAK || echo "log: no token"
log: no token
23:51:49.259 WARN  agent-brain.mcp.http - rejected an MCP request from localhost (POST) without a valid token
23:53:18.046 INFO  agent-brain.mcp - brain-mcp exiting        # SIGTERM: graceful stop, port released
```

Reproduce the automated tiers with `./gradlew :brain-mcp:test`: `TokenAuthTest`
(9 cases: load/trim/env-over-file/fail-closed/Bearer parsing/constant-time),
`McpHttpTest` (10: 401 matrix, session-is-not-a-credential, GET/DELETE gated,
404 vs 401, health, initialize/tools-list/rag_query/invalid-arg over HTTP,
loopback refusal), `McpHttpProcessTest` (3: the installed binary as a separate
process — 401, handshake, tools/list, rag_query, bind and token refusals), and
the original `BrainMcpStdioTest` (4) still green: **stdio compat is asserted, not
assumed**.

## 8. The second MCP: evaluating Nushell vs the Telegram/agent system

The plan asks for a second MCP, with Nushell as the new candidate (native MCP
since 0.116). Both were evaluated on the same five criteria: surface, auth,
permissions, effort, risk. The Nushell column is measured, not read: `nu` 0.116.1
is installed here and was probed on 2026-11-04 (`nu --mcp --mcp-transport http
--mcp-port 8799`, `initialize` + `tools/list`).

### (a) The Telegram/agent system as an MCP server

Surface: the bot's verbs — send a message, read turns, `/brain`, `/memory`,
status, mode. Every one of them **mutates** user-visible state or leaks a chat.

- **Surface**: attractive (it is the thing the owner actually operates), but it
  is a control plane, not a query surface: `send` alone is impersonation.
- **Auth**: the daemon has a bot token and a socket with its own auth work
  (B3a); an MCP wrapper needs its own token AND a per-chat identity model.
- **Permissions**: the hard part. Each tool needs an allowlist (which chat, which
  verb, rate limits, confirmation for `send`), and the tools are invoked by a
  model, so the allowlist must be enforced server-side with no free-form text
  passing through to Telegram.
- **Effort**: medium-high — a new module, a permission model, its own tests, and
  a new audit trail; the bot's send path is exactly the one U9 will red-team.
- **Risk**: high today. Incoming Telegram text is untrusted input; wiring an MCP
  server that can *send* on the owner's behalf, before U9's auth and red-team
  work, is how a prompt injection becomes a message to a human.

### (b) Nushell as an MCP server

`nu --mcp` exposes three tools — `evaluate` (run arbitrary nu/external
commands), `list_commands`, `command_help` — over stdio **or** streamable HTTP
(`--mcp-transport http --mcp-host … --mcp-port …`), with a `$history` ring
buffer for long output and background-job promotion for long calls. Zero code to
write.

- **Surface**: maximal and unbounded: `evaluate` is a shell with the user's
  rights. Great as a local harness tool, unusable as a published API.
- **Auth**: **none.** The probe answered `initialize` and `tools/list` with no
  `Authorization` header, and the server has no token flag; `--mcp-host 0.0.0.0`
  is accepted. Anything that can reach the port is a remote shell.
- **Permissions**: there is no permission model to compose with — no allowlist of
  commands, no read-only mode. The blast radius is the process's.
- **Effort**: ~0 to start, unbounded to make safe (a wrapper with auth + a
  command allowlist + a sandbox would be a bigger project than this one).
- **Risk**: unacceptable as a network surface; fine as a local tool where the
  trust boundary is "already on this machine with my UID".

### Verdict

**No second MCP endpoint now.** Written reasons:

1. **Nushell does not need to be built, and must not be exposed.** It is already
   a local MCP server (stdio/HTTP) that a harness can spawn itself; pointing an
   external client at it would be handing out a shell. If it is used, it is used
   locally, spawned per session with no credentials in its env — not as the
   brain's second public surface.
2. **The Telegram/agent MCP is the right second surface, but after U9.** It is
   the only one with a real capability the owner lacks over MCP (operate the
   channel), and it needs the permission model + auth + red-team drills U9
   exists to produce. Shipping it first would put the highest-risk surface online
   before the security work that is supposed to gate it.
3. **A cheap, safe alternative for "one more MCP" exists and is documented
   here:** the same brain surface can grow read-only tools (`/rag/stats`,
   `/memory/status`, `/eval/latest` are already HTTP routes) with the auth and
   shape proven in this document — that is a 1-hour change, not a system. The
   decision for the coordinator: if U8 wants a visibly "second MCP", this is the
   lowest-risk way to get one; if not, the honest state is "one MCP surface, two
   transports, and a written no for the two candidates".
