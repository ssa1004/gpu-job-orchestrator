# gpu-job-orchestrator — 자주 쓰는 명령 단일 진입점
#
#   make up          통합 stack 기동 (orchestrator-api + Postgres + Kafka + 3 stub, 모두 컨테이너)
#   make ps          컨테이너 상태
#   make logs        stack 로그 follow
#   make down        stack 정지 (볼륨 유지)
#   make clean       stack 정지 + 볼륨 삭제 (옛 데이터 제거)
#   make build       orchestrator-api(gradle) + worker(go) 빌드
#   make test        orchestrator-api + worker 테스트
#   make run-api     orchestrator-api 호스트 실행 (:8080, H2 + Mock K8s, 외부 의존 0)
#   make run-worker  worker 시뮬레이터 호스트 실행 (orchestrator-api 가 :8080 에 떠 있어야 함)
#   make demo        도메인 데모 (run-api 로 띄운 :8080 대상)
#   make integration-demo  통합 데모 (make up 으로 띄운 stack 대상)
#
# 두 가지 실행 형상이 있다 — README "빠른 실행" 참고:
#   1) 로컬 dev  : make run-api  → H2 + Mock K8s, Kafka 불필요, 외부 의존 0. 도메인 데모는 make demo.
#   2) 통합 시연 : make up       → orchestrator-api 자기 자신까지 컨테이너로 띄워 Postgres + Kafka
#                  + auth/notification/billing stub 와 한 docker 네트워크에서 닫힘. 통합 데모는
#                  make integration-demo. 이 형상에서는 모든 컴포넌트가 컨테이너라 Kafka 도
#                  kafka:9092 (내부 listener) 로 붙으면 된다 — 호스트에서 Kafka 를 직접 볼 일이 없다.

COMPOSE := docker compose -f infrastructure/docker/docker-compose.integration.yml
GRADLE  := ./gradlew

.DEFAULT_GOAL := help
.PHONY: help up ps logs down clean build test \
        build-api build-worker test-api test-worker \
        run-api run-worker demo integration-demo urls

help: ## 이 도움말
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

up: ## 통합 stack 기동 (orchestrator-api + Postgres + Kafka + auth/notification/billing stub)
	$(COMPOSE) up -d --build
	@echo "→ orchestrator-api http://localhost:8080/swagger · auth-stub :18080 · notification-stub :18081 · billing-stub :18082"

ps: ## 컨테이너 상태
	$(COMPOSE) ps

logs: ## stack 로그 follow
	$(COMPOSE) logs -f --tail=100

down: ## stack 정지 (볼륨 유지)
	$(COMPOSE) down

clean: ## stack 정지 + 볼륨 삭제 (다음 기동 시 깨끗한 상태)
	$(COMPOSE) down -v

build: build-api build-worker ## 전체 빌드 (orchestrator-api + worker)

build-api: ## orchestrator-api gradle 빌드 (테스트 제외)
	cd orchestrator-api && $(GRADLE) build -x test

build-worker: ## worker(Go) 빌드
	cd worker && go build -o bin/worker ./cmd/worker

test: test-api test-worker ## 전체 테스트 (orchestrator-api + worker)

test-api: ## orchestrator-api 테스트
	cd orchestrator-api && $(GRADLE) test

test-worker: ## worker(Go) 테스트
	cd worker && go test ./...

run-api: ## orchestrator-api 호스트 실행 (:8080 · H2 + Mock K8s, 외부 의존 0)
	cd orchestrator-api && $(GRADLE) bootRun

run-worker: ## worker 시뮬레이터 1회 실행 (run-api 가 :8080 에 떠 있어야 함)
	cd worker && go run ./cmd/worker \
	  --job-id=$$(uuidgen) \
	  --orchestrator-url=http://localhost:8080 \
	  --callback-secret=dev-secret-change-me \
	  --duration=10s

demo: ## 도메인 데모 (run-api 로 띄운 :8080 대상)
	./scripts/demo.sh

integration-demo: ## 통합 데모 (make up 으로 띄운 stack 대상)
	./scripts/integration-demo.sh

urls: ## 주요 UI / 엔드포인트
	@echo "orchestrator-api   http://localhost:8080  (/swagger · /actuator)"
	@echo "auth-stub          http://localhost:18080  (/oauth2/jwks · /oauth2/token)"
	@echo "notification-stub  http://localhost:18081"
	@echo "billing-stub       http://localhost:18082"
