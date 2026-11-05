#!/Users/lattenero/.local/bin/nu-nightly
# health-check.nu — veredicto del stack completo en una pantalla.
# Capas: launchd (boot chain) → colima → contenedores → brain serve → ollama → RAG → memoria.
# Uso: nu ~/Projects/agent-brain/scripts/health-check.nu
# Doctrina: complete para externals, try/catch para lo recuperable, sin sleeps.

const BIN = "/Users/lattenero/Projects/agent-brain/core/build/install/agent-brain/bin/agent-brain"

# cada check devuelve record {layer, check, ok, detail, hint}
def checks []: nothing -> list<record> {
    [
        # 1. launchd: la cadena de boot cargada
        {
            layer: "launchd"
            check: "cadena de boot cargada"
            f: {||
                let l = (launchctl list | complete)
                if $l.exit_code != 0 {
                    {ok: false, detail: "launchctl falló", hint: "verificar sesión de GUI"}
                } else {
                    let rows = ($l.stdout | lines | where $it =~ 'com\.zcode' | parse "{pid}\t{exit}\t{label}")
                    let need = ["com.zcode.colima", "com.zcode.stack-up", "com.zcode.agent-brain", "com.zcode.brain-mcp"]
                    let missing = ($need | where { |n| ($rows | where label == $n | length) == 0 })
                    {ok: ($missing | is-empty), detail: (if ($missing | is-empty) { "4/4 agentes" } else { $"faltan: ($missing | str join ', ')" }), hint: "bootstrap con launchctl bootstrap gui/501 <plist>"}
                }
            }
        }
        # 2. colima
        {
            layer: "vm"
            check: "colima running"
            f: {||
                let s = (run-external /opt/homebrew/bin/colima status | complete)
                {ok: ($s.exit_code == 0), detail: (if $s.exit_code == 0 { "up" } else { "down" }), hint: "watchdog la levanta en ≤2min (log ~/Library/Logs/colima-watchdog.log)"}
            }
        }
        # 3. contenedores
        {
            layer: "containers"
            check: "pg/redpanda/prometheus/grafana healthy"
            f: {||
                let ps = (docker ps --format "{{.Names}}\t{{.Status}}" | complete)
                if $ps.exit_code != 0 {
                    {ok: false, detail: "docker no responde", hint: "colima caído"}
                } else {
                    let bad = ($ps.stdout | lines | parse "{name}\t{status}" | where name =~ '^agent-brain' | where status !~ 'healthy')
                    {ok: ($bad | is-empty), detail: (if ($bad | is-empty) { "4/4 healthy" } else { $"mal: ($bad | get name | str join ', ')" }), hint: "docker compose -f ~/Projects/agent-brain/docker-compose.yml up -d --wait"}
                }
            }
        }
        # 4. brain serve HTTP
        {
            layer: "brain"
            check: "serve /health ok (8765)"
            f: {||
                let h = (http get http://127.0.0.1:8765/health)
                {ok: ($h.status == "ok"), detail: $"status ($h.status)", hint: "launchctl kickstart -k gui/501/com.zcode.agent-brain (log ~/Library/Logs/agent-brain.log)"}
            }
        }
        # 5. ollama (embeddings)
        {
            layer: "ollama"
            check: "server embeddings vivo"
            f: {||
                let t = (http get http://127.0.0.1:11434/api/tags)
                {ok: true, detail: $"($t.models | length) modelos", hint: "sh.brew.ollama LaunchAgent"}
            }
        }
        # 6. RAG end-to-end (pg + embeddings)
        {
            layer: "rag"
            check: "query híbrida con citas"
            f: {||
                let q = (run-external $BIN ...["query", "nushell job recv timeout", "--k", "1"] | complete)
                {ok: ($q.exit_code == 0), detail: (if $q.exit_code == 0 { "ok" } else { $q.stderr | lines | last 1 }), hint: "correr agent-brain index (corpus.txt)"}
            }
        }
        # 7. memoria durable
        {
            layer: "memory"
            check: "memory search híbrida"
            f: {||
                let m = (run-external $BIN ...["memory", "search", "kap functional design", "--k", "1"] | complete)
                {ok: ($m.exit_code == 0), detail: (if $m.exit_code == 0 { "ok" } else { $m.stderr | lines | last 1 }), hint: "verificar pg y ollama"}
            }
        }
    ]
}

def main [] {
    let results = (checks | each {|c|
        let r = (try { do $c.f } catch {|err| {ok: false, detail: $"excepción: ($err.msg)", hint: "revisar script"}})
        {layer: $c.layer, check: $c.check, estado: (if $r.ok { "✓" } else { "✗" }), detail: $r.detail, pista: $r.hint}
    })
    print ($results | select layer check estado detail | table --index false)
    let bad = ($results | where estado == "✗")
    if ($bad | is-empty) {
        print "SISTEMA SANO ✓ — todas las capas responden"
    } else {
        print $"--- ($bad | length) capa(s) caída(s); pistas:"
        $bad | each {|b| print $"· ($b.layer): ($b.pista)" }
        exit 1
    }
}
