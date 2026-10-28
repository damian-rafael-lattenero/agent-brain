.PHONY: bootstrap up down nuke migrate serve index query test bb e2e-real e2e-real-login check ci clean

BIN := core/build/install/agent-brain/bin/agent-brain

bootstrap:          ## verify JDK/docker/ollama and build the distribution
	./bootstrap.sh

up:                 ## start pgvector (compose) and apply migrations
	docker compose up -d --wait
	./gradlew --quiet :core:installDist
	$(BIN) migrate

down:               ## stop the containers, keep the data
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

query:              ## hybrid retrieval with citations: make query Q="how does the tailer handle truncation"
	./gradlew --quiet :core:installDist
	$(BIN) query $(Q)

test:               ## tiers 1-3: unit, integration, e2e (skips a tier when its service is down)
	./gradlew test

bb:                 ## tier 4: black-box local, real processes, services REQUIRED
	./gradlew bbLocal

E2E_PY := tools/e2e-real/.venv/bin/python3

$(E2E_PY):
	python3 -m venv tools/e2e-real/.venv && tools/e2e-real/.venv/bin/pip install -q -r tools/e2e-real/requirements.txt

e2e-real-login: $(E2E_PY)  ## one-time: create AB_TG_SESSION for your account (needs AB_TG_API_ID/AB_TG_API_HASH)
	$(E2E_PY) tools/e2e-real/login.py

e2e-real: $(E2E_PY)  ## tier 5: your Telegram account drives the bot, the brain is observed through it
	AB_E2E_PYTHON=$(E2E_PY) ./gradlew e2eReal

check: test bb      ## everything CI runs on every push

ci:                 ## what CI runs, with services required (no silent skips)
	AB_REQUIRE_SERVICES=1 ./gradlew test bbLocal

clean:
	./gradlew clean

help:
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/\t/' | expand -t 14
