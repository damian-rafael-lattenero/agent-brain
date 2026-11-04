.PHONY: bootstrap up down nuke migrate serve index index-repos index-repos-dry query bridge plugin eval eval-suite eval-matrix eval-matrix-retrieval eval-latest test bb detekt e2e-real e2e-real-login check ci clean

BIN := core/build/install/agent-brain/bin/agent-brain

# The chat models the matrix compares by default (the extraction/answer axis).
MODELS ?= qwen2.5:1.5b,qwen2.5:3b
# The suites the matrix runs by default: the memory turn and the composed answer.
SUITES ?= extraction-core,grounded-citation
# Embedding models for the retrieval axis; the @dim is the vector width.
RECALL_MODELS ?= nomic-embed-text@768,all-minilm@384

bootstrap:          ## verify JDK/docker/ollama and build the distribution
	./bootstrap.nu

up:                 ## start the stack (pgvector + redpanda + prometheus + grafana) and apply migrations
	docker compose up -d --wait
	./gradlew --quiet :core:installDist
	$(BIN) migrate

down:               ## stop the containers (dashboards included), keep the data
	docker compose down

nuke:               ## stop the containers AND drop the data (a new embedding model needs this)
	docker compose down -v

migrate:            ## apply pending migrations
	./gradlew --quiet :core:installDist
	$(BIN) migrate

serve:              ## run the HTTP service in the foreground
	./gradlew --quiet :core:installDist
	$(BIN) serve

index:              ## index the corpus (~/.zcode/agent-brain/corpus.txt, see corpus.example.txt)
	./gradlew --quiet :core:installDist
	$(BIN) index

index-repos:        ## index the owner's repos under ~/Projects (kap, bot, zcode-bot-kotlin, ZCode, agent-brain)
	./gradlew --quiet :core:installDist
	$(BIN) index-repos

index-repos-dry:    ## scan the repos and report documents/bytes per repo; no Postgres needed
	./gradlew --quiet :core:installDist
	$(BIN) index-repos --dry-run

query:              ## hybrid retrieval with citations: make query Q="how does the tailer handle truncation"
	./gradlew --quiet :core:installDist
	$(BIN) query $(Q)

bridge:             ## tail the bus file into the Kafka topic once (bus_transport=kafka deployments)
	./gradlew --quiet :core:installDist
	$(BIN) bridge --once

plugin:             ## build the ZCode plugin (brain-mcp) into brain-mcp/build/plugin; then add that marketplace in ZCode
	./gradlew --quiet :brain-mcp:plugin
	@echo "plugin ready: $(CURDIR)/brain-mcp/build/plugin (add it as a local marketplace in ZCode, then install brain-mcp)"

eval:               ## the cheap CI gate: recall-core (20 questions, gate 0.9) → eval_runs + evals/artifacts/latest.{md,json}
	./gradlew --quiet :core:installDist
	$(BIN) eval

eval-suite:         ## any suite: make eval-suite SUITE=extraction-core
	./gradlew --quiet :core:installDist
	$(BIN) eval --suite $(or $(SUITE),extraction-core)

eval-matrix:        ## the model comparison, ONE command: make eval-matrix [MODELS=..] [SUITES=..]
	./gradlew --quiet :core:installDist
	$(BIN) eval --suite $(SUITES) --matrix $(MODELS)

eval-matrix-retrieval: ## the same on the retrieval axis: make eval-matrix-retrieval [MODELS=nomic-embed-text@768,all-minilm@384]
	./gradlew --quiet :core:installDist
	$(BIN) eval --suite recall-core --matrix $(RECALL_MODELS)

eval-latest:        ## the last persisted run(s): make eval-latest [SUITE=recall-core] [MATRIX=1]
	./gradlew --quiet :core:installDist
	$(BIN) eval latest $(if $(SUITE),--suite $(SUITE)) $(if $(MATRIX),--matrix) --md

test:               ## tiers 1-3: unit, integration, e2e (skips a tier when its service is down)
	./gradlew test

bb:                 ## tier 4: black-box local, real processes, services REQUIRED
	./gradlew bbLocal

detekt:             ## static analysis: baselined findings tolerated, new violations fail (detekt 2.0)
	./gradlew detektAll

E2E_PY := tools/e2e-real/.venv/bin/python3

$(E2E_PY):
	python3 -m venv tools/e2e-real/.venv && tools/e2e-real/.venv/bin/pip install -q -r tools/e2e-real/requirements.txt

e2e-real-login: $(E2E_PY)  ## one-time: create AB_TG_SESSION for your account (needs AB_TG_API_ID/AB_TG_API_HASH)
	$(E2E_PY) tools/e2e-real/login.py

e2e-real: $(E2E_PY)  ## tier 5: your Telegram account drives the bot, the brain is observed through it
	AB_E2E_PYTHON=$(E2E_PY) ./gradlew e2eReal

check: test bb detekt   ## everything CI runs on every push

ci:                 ## what CI runs, with services required (no silent skips)
	AB_REQUIRE_SERVICES=1 ./gradlew test bbLocal detektAll

clean:
	./gradlew clean

help:
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/\t/' | expand -t 14
