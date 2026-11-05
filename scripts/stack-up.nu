#!/Users/lattenero/.local/bin/nu-nightly
# stack-up.nu — orquestador de fin de boot: cuando Postgres responde, kickstartea
# los agentes one-shot que corrieron demasiado temprano en el login (el index del
# brain y los jobs del bot) y termina.
#
# Patrón launchd, sin sleeps: si pg no responde sale 75 (EX_TEMPFAIL) y el plist
# (KeepAlive SuccessfulExit=false, ThrottleInterval 15) lo relanza hasta lograrlo.
# Cuando tiene éxito sale 0 y launchd deja de relanzarlo.

const PG_HOST = "127.0.0.1"
const PG_PORT = 5433

def kick [label: string] {
    let r = (^/bin/launchctl kickstart $"gui/501/($label)" | complete)
    print $"kick ($label): exit ($r.exit_code)"
}

def main [] {
    let probe = (^/usr/bin/nc -z $PG_HOST $PG_PORT | complete)
    if $probe.exit_code != 0 {
        print $"stack-up: pg ($PG_HOST):($PG_PORT) no responde; reintento por launchd"
        exit 75
    }
    print "stack-up: pg listo; disparando one-shots"
    kick "com.zcode.agent-brain-index"
    kick "com.zcode.bot-recorder"
    kick "com.zcode.bot-comparator"
    kick "com.zcode.bot-healer"
}
