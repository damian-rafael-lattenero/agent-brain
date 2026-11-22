#!/Users/lattenero/.local/bin/nu-nightly
# health-check.nu — the whole stack's verdict in one screen.
# Layers: launchd (boot chain) → colima → containers → brain serve → ollama →
# RAG → memory. Check names, details and hints stay Spanish: they are the
# owner-facing report and the Telegram alert text (data, not code identity).
# Usage: nu ~/Projects/agent-brain/scripts/health-check.nu
# Doctrine: complete for externals, try/catch for recoverables, zero sleeps.

const BIN: string = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

# The type aliases (the owner's 22/11 law): the shapes read once, the
# signatures spell the name. Each check row carries its closure; each
# closure answers the uniform payload. Rule rows compose: a named row type
# + a named book type (measured 22/11 — the owner's subtype-alias ask).
type LayerCheck = record<layer: string, check: string, f: closure>
type DetailRule = record<when: closure, render: closure>
type DetailRules = list<DetailRule>

# each check answers the uniform {layer, check, ok, detail, hint} payload;
# the Spanish check names/details/hints are the owner-facing report voice
# (the header's declared law — data, not code identity)
# exported so brain-sentinel.nu can reuse the whole engine
export def checks []: nothing -> list<LayerCheck> {
    [
        # 1. launchd: la cadena de boot cargada
        {
            layer: "launchd"
            check: "cadena de boot cargada"
            f: {||
                let l: record = (launchctl list | complete)
                if $l.exit_code != 0 {
                    {ok: false, detail: "launchctl falló", hint: "verificar sesión de GUI"}
                } else {
                    let rows: table = ($l.stdout | lines | where {|row| $row =~ 'com\.zcode' } | parse "{pid}\t{exit}\t{label}")
                    let need: list<string> = ["com.zcode.colima", "com.zcode.stack-up", "com.zcode.agent-brain", "com.zcode.brain-mcp"]
                    let missing: list<string> = ($need | where {|n| ($rows | where label == $n | length) == 0 })
                    {ok: ($missing | is-empty), detail: (if ($missing | is-empty) { "4/4 agentes" } else { $"faltan: ($missing | str join ', ')" }), hint: "bootstrap con launchctl bootstrap gui/501 <plist>"}
                }
            }
        }
        # 2. colima
        {
            layer: "vm"
            check: "colima running"
            f: {||
                let s: record = (run-external /opt/homebrew/bin/colima status | complete)
                {ok: ($s.exit_code == 0), detail: (if $s.exit_code == 0 { "up" } else { "down" }), hint: "watchdog la levanta en ≤2min (log ~/Library/Logs/colima-watchdog.log)"}
            }
        }
        # 3. contenedores
        {
            layer: "containers"
            check: "pg/redpanda/prometheus/grafana healthy"
            f: {||
                let ps: record = (docker ps --format "{{.Names}}\t{{.Status}}" | complete)
                if $ps.exit_code != 0 {
                    {ok: false, detail: "docker no responde", hint: "colima caído"}
                } else {
                    let bad: table = ($ps.stdout | lines | parse "{name}\t{status}" | where name =~ '^agent-brain' | where status !~ 'healthy')
                    {ok: ($bad | is-empty), detail: (if ($bad | is-empty) { "4/4 healthy" } else { $"mal: ($bad | get name | str join ', ')" }), hint: "docker compose -f ~/Projects/agent-brain/docker-compose.yml up -d --wait"}
                }
            }
        }
        # 4. brain serve HTTP
        {
            layer: "brain"
            check: "serve /health ok (8765)"
            f: {||
                let h: record = (http get -m 5sec http://127.0.0.1:8765/health)
                {ok: ($h.status == "ok"), detail: $"status ($h.status)", hint: "launchctl kickstart -k gui/501/com.zcode.agent-brain (log ~/Library/Logs/agent-brain.log)"}
            }
        }
        # 5. ollama (embeddings + a CHAT probe — the 15/11 blind spot: the tags
        # endpoint stayed green for 4 h while every chat call starved behind a
        # degraded runner; a tiny say-ok with a latency budget speaks the truth)
        {
            layer: "ollama"
            check: "embeddings + chat vivo"
            f: {||
                let t: record = (http get -m 5sec http://127.0.0.1:11434/api/tags)
                let chat_body: string = ({model: "qwen2.5:1.5b", stream: false, messages: [{role: "user", content: "say ok"}], options: {temperature: 0, num_predict: 8}} | to json -r)
                let t0: datetime = (date now)
                let probe: record = (try {
                    {answer: (http post -m 15sec --content-type application/json http://127.0.0.1:11434/api/chat $chat_body | get message.content), ok: true}
                } catch {|err|
                    {answer: $err.msg, ok: false}
                })
                let took: duration = ((date now) - $t0)
                # 15 s for an 8-token answer on a loaded box is already generous:
                # healthy = sub-second, the 15/11 degradation measured 25-60 s.
                let chat_ok: bool = ($probe.ok and ($took < 15sec))
                # the detail classifier as a predicate table — precedence in
                # row order, catch-all last (the mode-table law).
                let detailRows: DetailRules = [
                    {when: {|p: record| not $p.ok}, render: {|p: record| $"chat DOWN: ($p.answer | str substring 0..60)"}}
                    {when: {|p: record| not $chat_ok}, render: {|p: record| $"chat LENTO: ($took) — runner degradado o cola (15/11: inanición de qwen)"}}
                    {when: {|p: record| true}, render: {|p: record| $"($t.models | length) modelos, chat ($took)"}}
                ]
                let hit: record = ($detailRows | where {|r| do $r.when $probe } | first)
                let detail: string = (do $hit.render $probe)
                {ok: $chat_ok, detail: $detail, hint: "brew services restart ollama; si persiste: presión de RAM (daemons idle) — ver ~/Library/Logs/agent-brain.log"}
            }
        }
        # 6. RAG end-to-end (pg + embeddings)
        {
            layer: "rag"
            check: "query híbrida con citas"
            f: {||
                let q: record = (run-external $BIN ...["query", "nushell job recv timeout", "--k", "1"] | complete)
                {ok: ($q.exit_code == 0), detail: (if $q.exit_code == 0 { "ok" } else { $q.stderr | lines | last 1 }), hint: "correr agent-brain index (corpus.txt)"}
            }
        }
        # 7. memoria durable
        {
            layer: "memory"
            check: "memory search híbrida"
            f: {||
                let m: record = (run-external $BIN ...["memory", "search", "kap functional design", "--k", "1"] | complete)
                {ok: ($m.exit_code == 0), detail: (if $m.exit_code == 0 { "ok" } else { $m.stderr | lines | last 1 }), hint: "verificar pg y ollama"}
            }
        }
        # 8. index freshness — the corpus must not rot silently (born from the
        # human-num episode: a stale index reads like a bug everywhere else)
        {
            layer: "index"
            check: "documents frescos (max updated_at < 7 días)"
            f: {||
                let q: record = ("SELECT to_char(max(updated_at), 'YYYY-MM-DD') FROM documents;"
                    | docker exec -i agent-brain-pg psql -U brain -d agent_brain -t | complete)
                if $q.exit_code != 0 {
                    {ok: false, detail: "psql no respondió", hint: "capa contenedores/pg"}
                } else {
                    let d: string = ($q.stdout | lines | where {|l| ($l | str trim) != ""} | first | str trim)
                    if ($d | is-empty) {
                        {ok: false, detail: "sin documentos", hint: "correr agent-brain index (corpus.txt)"}
                    } else {
                        let age: duration = ((date now) - ($d | into datetime))
                        {ok: ($age < 7day), detail: $"última actualización ($d)", hint: "correr agent-brain index (corpus.txt)"}
                    }
                }
            }
        }
    ]
}

def main []: nothing -> nothing {
    let results: table = (checks | each {|c|
        let r: record = (try { do $c.f } catch {|err| {ok: false, detail: $"excepción: ($err.msg)", hint: "revisar script"}})
        {layer: $c.layer, check: $c.check, estado: (if $r.ok { "✓" } else { "✗" }), detail: $r.detail, pista: $r.hint}
    })
    print ($results | select layer check estado detail | table --index false)
    let bad: table = ($results | where estado == "✗")
    if ($bad | is-empty) {
        print "SISTEMA SANO ✓ — todas las capas responden"
    } else {
        print $"--- ($bad | length) capa(s) caída(s); pistas:"
        $bad | each {|b| print $"· ($b.layer): ($b.pista)" }
        exit 1
    }
}
