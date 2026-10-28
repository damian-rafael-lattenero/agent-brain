#!/bin/bash
# bootstrap.sh: checks and prepares what the Makefile assumes. Idempotent.
# Nothing here needs sudo. JDKs are Amazon Corretto only (house rule).
set -euo pipefail
cd "$(dirname "$0")"

ok()   { printf '  ✓ %s\n' "$1"; }
miss() { printf '  ✗ %s\n' "$1"; MISSING=1; }
MISSING=0

echo "▶ JDK 25 (Amazon Corretto)"
if JAVA25=$(/usr/libexec/java_home -v 25 2>/dev/null) && "$JAVA25/bin/java" -version 2>&1 | grep -q Corretto; then
  ok "$JAVA25"
else
  miss "install with: brew install --cask corretto@25"
fi

echo "▶ Docker runtime (colima)"
if docker info >/dev/null 2>&1; then
  ok "docker reachable"
else
  miss "start it with: colima start   (install: brew install colima docker docker-compose)"
fi

echo "▶ Ollama + nomic-embed-text (default embedding provider, 768 dims)"
if command -v ollama >/dev/null 2>&1; then
  if curl -sf http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    if ollama list 2>/dev/null | grep -q '^nomic-embed-text'; then
      ok "model present"
    else
      echo "  … pulling nomic-embed-text"; ollama pull nomic-embed-text && ok "model pulled"
    fi
  else
    miss "server not running: run 'ollama serve' (or: brew services start ollama)"
  fi
else
  miss "install with: brew install ollama"
fi

echo "▶ Gradle wrapper + dependencies"
./gradlew --quiet :core:installDist && ok "core/build/install/agent-brain/bin/agent-brain"

if [ "$MISSING" -ne 0 ]; then
  echo "bootstrap: something is missing (see ✗ above)"; exit 1
fi
echo "✓ ready: make up && make test && make bb"
