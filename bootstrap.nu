#!/Users/lattenero/.local/bin/nu-nightly
# bootstrap.nu: checks and prepares what the Makefile assumes. Idempotent.
# Nothing here needs sudo. JDKs are Amazon Corretto only (house rule).
#
# Migrated from bootstrap.sh on 2026-11-02.

def ok [msg: string]: nothing -> nothing {
    print $"  ✓ ($msg)"
}

def miss [msg: string]: nothing -> nothing {
    print $"  ✗ ($msg)"
}

def is-exec [path: string]: nothing -> bool {
    (do { ^test -x $path } | complete).exit_code == 0
}

def main []: nothing -> nothing {
    let repo = $env.FILE_PWD
    cd $repo
    mut missing: bool = false

    print "▶ JDK 25 \(Amazon Corretto\)"
    let home_java = (do { ^/usr/libexec/java_home -v 25 } | complete)
    if $home_java.exit_code == 0 {
        let java_home = ($home_java.stdout | str trim)
        let java_bin = ($java_home | path join "bin/java")
        let version = (do { ^$java_bin -version } | complete)
        if ($version.stderr | str contains "Corretto") {
            ok $java_home
        } else {
            miss "install with: brew install --cask corretto@25"
            $missing = true
        }
    } else {
        miss "install with: brew install --cask corretto@25"
        $missing = true
    }

    print "▶ Docker runtime \(colima\)"
    let docker = (do { ^docker info } | complete)
    if $docker.exit_code == 0 {
        ok "docker reachable"
    } else {
        miss "start it with: colima start   \(install: brew install colima docker docker-compose\)"
        $missing = true
    }

    print "▶ Ollama + nomic-embed-text \(default embedding provider, 768 dims\)"
    if (which ollama | is-empty) {
        miss "install with: brew install ollama"
        $missing = true
    } else {
        let tags = (try { http get -m 2sec http://127.0.0.1:11434/api/tags | ignore; true } catch { false })
        if $tags {
            let listed = (do { ^ollama list } | complete)
            if ($listed.stdout | str contains "nomic-embed-text") {
                ok "model present"
            } else {
                print "  … pulling nomic-embed-text"
                let pull = (do { ^ollama pull nomic-embed-text } | complete)
                if $pull.exit_code == 0 {
                    ok "model pulled"
                } else {
                    miss "ollama pull nomic-embed-text failed"
                    $missing = true
                }
            }
        } else {
            miss "server not running: run 'ollama serve' \(or: brew services start ollama\)"
            $missing = true
        }
    }

    print "▶ Gradle wrapper + dependencies"
    ^./gradlew --quiet :core:installDist
    ok "core/build/install/agent-brain/bin/agent-brain"

    if $missing {
        print "bootstrap: something is missing \(see ✗ above\)"
        exit 1
    }
    print "✓ ready: make up && make test && make bb"
}
