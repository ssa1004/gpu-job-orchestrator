"""Portfolio set 통합 시연용 mock notification-hub.

본 레포 (gpu-job-orchestrator) 의 OutboxRelay 가 발행하는 세 종류 Job 이벤트를 consume
해서 in-memory 리스트에 쌓는다. `/received` GET 으로 누적된 이벤트를 노출.

실제 notification-hub 의 채널별 fan-out (이메일 / Slack / SMS / push) 은 빼두었다 —
이 stub 은 본 레포의 *나가는 이벤트 통합점* 만 검증한다.
"""
from __future__ import annotations

import json
import os
import threading
import time
from collections import deque
from typing import Any, Deque

from flask import Flask, jsonify
from kafka import KafkaConsumer
from kafka.errors import NoBrokersAvailable

KAFKA_BOOTSTRAP = os.environ.get("KAFKA_BOOTSTRAP", "kafka:9092")
TOPICS = [
    "gwp.job.jobsubmitted",
    "gwp.job.jobcompleted",
    "gwp.job.jobpreempted",
]
_BUFFER_MAX = 500

# topic 별 deque — 마지막 N 개만 유지.
_received: dict[str, Deque[dict[str, Any]]] = {topic: deque(maxlen=_BUFFER_MAX) for topic in TOPICS}
_lock = threading.Lock()

app = Flask(__name__)


def _make_consumer() -> KafkaConsumer:
    """Kafka 가 늦게 뜰 수 있어 backoff 로 재시도."""
    delay = 1.0
    while True:
        try:
            return KafkaConsumer(
                *TOPICS,
                bootstrap_servers=KAFKA_BOOTSTRAP.split(","),
                group_id="notification-stub",
                # 시연 단순화 — 컨테이너 재시작 시 매번 처음부터 읽도록 earliest.
                auto_offset_reset="earliest",
                enable_auto_commit=True,
                value_deserializer=lambda v: v,
            )
        except NoBrokersAvailable:
            print(f"[notification-stub] kafka not yet available, retry in {delay:.1f}s", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 10.0)


def _consumer_loop() -> None:
    consumer = _make_consumer()
    print(f"[notification-stub] subscribed: {TOPICS}", flush=True)
    for msg in consumer:
        topic = msg.topic
        try:
            payload = json.loads(msg.value.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            print(f"[notification-stub] decode failed topic={topic} err={exc}", flush=True)
            continue
        # traceparent 헤더 살아있는지 확인용 — orchestrator 가 ADR-0018 대로 박았는지.
        traceparent = None
        for key, value in (msg.headers or []):
            if key == "traceparent" and value is not None:
                traceparent = value.decode("utf-8")
                break
        record = {"topic": topic, "payload": payload, "traceparent": traceparent}
        with _lock:
            _received[topic].append(record)
        # stdout 으로도 흘려서 docker logs 만 봐도 흐름 추적 가능.
        print(f"[notification-stub] received topic={topic} jobId={payload.get('jobId')}", flush=True)


@app.get("/health")
def health() -> Any:
    return jsonify({"status": "ok"})


@app.get("/received")
def received_all() -> Any:
    """전체 수신 이벤트. demo 검증의 단일 진실."""
    with _lock:
        snapshot = {topic: list(items) for topic, items in _received.items()}
    counts = {topic: len(items) for topic, items in snapshot.items()}
    return jsonify({"counts": counts, "events": snapshot})


@app.get("/received/<topic>")
def received_one(topic: str) -> Any:
    if topic not in _received:
        return jsonify({"error": f"unknown topic {topic}"}), 404
    with _lock:
        items = list(_received[topic])
    return jsonify({"topic": topic, "count": len(items), "events": items})


if __name__ == "__main__":
    threading.Thread(target=_consumer_loop, daemon=True).start()
    app.run(host="0.0.0.0", port=8080, debug=False)
