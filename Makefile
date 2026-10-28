.PHONY: bootstrap up down nuke migrate serve test bb e2e-real check ci clean

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

test:               ## tiers 1-3: unit, integration, e2e (skips a tier when its service is down)
	./gradlew test

bb:                 ## tier 4: black-box local, real processes, services REQUIRED
	./gradlew bbLocal

e2e-real:           ## tier 5: black-box real over your Telegram account (from P1)
	./gradlew e2eReal

check: test bb      ## everything CI runs on every push

ci:                 ## what CI runs, with services required (no silent skips)
	AB_REQUIRE_SERVICES=1 ./gradlew test bbLocal

clean:
	./gradlew clean

help:
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | sed 's/:.*##/\t/' | expand -t 14
