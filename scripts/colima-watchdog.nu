#!/Users/lattenero/.local/bin/nu-nightly
# colima-watchdog.nu — garantiza que la VM de colima esté arriba.
# launchd lo llama en cada login (RunAtLoad) y cada 2 minutos (StartInterval):
# si colima está down, lo arranca; si está up, sale 0 sin tocar nada.
# El proceso es de launchd, no de ZCode: la VM sobrevive a reinicios de la app
# (regla AGENTS.md "Procesos que deben sobrevivir a ZCode" — colima JAMÁS desde
# la sesión de ZCode, medido 3/11/2026).

const COLIMA = "/opt/homebrew/bin/colima"

def main [] {
    let st = (run-external $COLIMA "status" | complete)
    if $st.exit_code == 0 {
        exit 0
    }
    print "colima down; arrancando..."
    let up = (run-external $COLIMA "start" | complete)
    if $up.exit_code == 0 {
        print "colima ✓"
    } else {
        print $"colima start falló: ($up.stderr | lines | last 3 | str join ' | ')"
        exit 1
    }
}
