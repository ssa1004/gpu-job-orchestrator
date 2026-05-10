"""Portfolio set 통합 시연용 mock billing-platform.

본 레포 (gpu-job-orchestrator) 의 OutboxRelay 가 발행하는 ``gwp.job.jobcompleted`` 를
consume → 본 레포의 ``GET /api/v1/cost/jobs/{id}`` 로 단가 박제 lookup → in-memory
ledger 에 적재. `/ledger` GET 으로 노출.

실제 billing-platform 의 ledger / 청구서 / 결제 게이트웨이 / 환불 흐름은 빼두었다 —
이 stub 은 본 레포의 *나가는 빌링 통합점* (cost record 가 외부에서 조회 가능한가) 만
닫힘 검증한다.
"""
from __future__ import annotations

import json
import os
import threading
import time
from collections import OrderedDict
from typing import Any

import requests
from flask import Flask, jsonify
from kafka import KafkaConsumer
from kafka.errors import NoBrokersAvailable

KAFKA_BOOTSTRAP = os.environ.get("KAFKA_BOOTSTRAP", "kafka:9092")
ORCHESTRATOR_BASE = os.environ.get("ORCHESTRATOR_BASE", "http://orchestrator-api:8080")
AUTH_STUB_BASE = os.environ.get("AUTH_STUB_BASE", "http://auth-stub:8080")
# orchestrator-api 의 demo owner 이름. cost record 는 owner 별 isolation 이라 lookup 시
# 같은 sub 으로 발급받은 토큰이어야 한다. 데모 스크립트의 OWNER 와 일치해야 함.
COST_LOOKUP_SUB = os.environ.get("COST_LOOKUP_SUB", "demo-user")
TOPIC = "gwp.job.jobcompleted"

# jobId → ledger row. OrderedDict 로 삽입 순 보존.
_ledger: "OrderedDict[str, dict[str, Any]]" = OrderedDict()
_lock = threading.Lock()
# 토큰 캐시 — 매 lookup 마다 발급하면 auth-stub 부하 + log 노이즈. 만료 임박 전 재사용.
_token_cache: dict[str, Any] = {"value": None, "exp": 0.0}
_token_lock = threading.Lock()

app = Flask(__name__)


def _get_token() -> str | None:
    """auth-stub 에서 client_credentials 토큰 발급 (간이). 만료 30 초 전 재발급."""
    with _token_lock:
        now = time.time()
        if _token_cache["value"] and _token_cache["exp"] - now > 30:
            return _token_cache["value"]
        try:
            resp = requests.post(
                f"{AUTH_STUB_BASE}/oauth2/token",
                data={"sub": COST_LOOKUP_SUB, "scope": "jobs.read", "ttl": 600},
                timeout=5,
            )
        except requests.RequestException as exc:
            print(f"[billing-stub] token fetch failed: {exc}", flush=True)
            return None
        if resp.status_code != 200:
            print(f"[billing-stub] token fetch status={resp.status_code}", flush=True)
            return None
        body = resp.json()
        _token_cache["value"] = body.get("access_token")
        _token_cache["exp"] = now + int(body.get("expires_in", 600))
        return _token_cache["value"]


def _lookup_cost(job_id: str) -> dict[str, Any] | None:
    """orchestrator-api 의 cost endpoint 호출. 운영 모드 (JWT 필수) 라 토큰 발급 후 호출."""
    token = _get_token()
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{ORCHESTRATOR_BASE}/api/v1/cost/jobs/{job_id}"
    try:
        resp = requests.get(url, headers=headers, timeout=5)
    except requests.RequestException as exc:
        print(f"[billing-stub] cost lookup network error jobId={job_id}: {exc}", flush=True)
        return None
    if resp.status_code == 404:
        # CostAttributionService 의 INSERT 가 OutboxRelay 발행보다 약간 늦을 수 있어 재시도.
        time.sleep(0.7)
        try:
            resp = requests.get(url, headers=headers, timeout=5)
        except requests.RequestException as exc:
            print(f"[billing-stub] cost lookup retry network error jobId={job_id}: {exc}", flush=True)
            return None
    if resp.status_code != 200:
        print(f"[billing-stub] cost lookup failed jobId={job_id} status={resp.status_code}", flush=True)
        return None
    return resp.json()


def _make_consumer() -> KafkaConsumer:
    """Kafka 가 늦게 뜰 수 있어 backoff 로 재시도."""
    delay = 1.0
    while True:
        try:
            return KafkaConsumer(
                TOPIC,
                bootstrap_servers=KAFKA_BOOTSTRAP.split(","),
                group_id="billing-stub",
                auto_offset_reset="earliest",
                enable_auto_commit=True,
                value_deserializer=lambda v: v,
            )
        except NoBrokersAvailable:
            print(f"[billing-stub] kafka not yet available, retry in {delay:.1f}s", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 10.0)


def _consumer_loop() -> None:
    consumer = _make_consumer()
    print(f"[billing-stub] subscribed: {TOPIC}", flush=True)
    for msg in consumer:
        try:
            payload = json.loads(msg.value.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            print(f"[billing-stub] decode failed err={exc}", flush=True)
            continue
        job_id = payload.get("jobId")
        status = payload.get("status")
        if not job_id:
            continue
        # SUCCEEDED / FAILED / CANCELLED 모두 cost record 가 적재됨 (CostAttributionService 가 종착 hook 에서 호출).
        cost = _lookup_cost(job_id)
        row = {
            "jobId": job_id,
            "status": status,
            "finishedAt": payload.get("finishedAt"),
            "cost": cost,
        }
        with _lock:
            _ledger[job_id] = row
        cost_summary = "n/a" if cost is None else f"{cost.get('computedCost')} {cost.get('currency')}"
        print(f"[billing-stub] ledger += jobId={job_id} status={status} cost={cost_summary}", flush=True)


@app.get("/health")
def health() -> Any:
    return jsonify({"status": "ok"})


@app.get("/ledger")
def ledger() -> Any:
    with _lock:
        rows = list(_ledger.values())
    return jsonify({"count": len(rows), "rows": rows})


@app.get("/ledger/<job_id>")
def ledger_one(job_id: str) -> Any:
    with _lock:
        row = _ledger.get(job_id)
    if row is None:
        return jsonify({"error": "not yet recorded"}), 404
    return jsonify(row)


if __name__ == "__main__":
    threading.Thread(target=_consumer_loop, daemon=True).start()
    app.run(host="0.0.0.0", port=8080, debug=False)
