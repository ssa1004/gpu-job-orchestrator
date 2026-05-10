# Portfolio set 통합 시연용 mock notification-hub.
#
# Kafka 의 gwp.job.jobsubmitted / jobcompleted / jobpreempted 세 토픽을 구독해서 in-memory
# 리스트에 쌓고, /received endpoint 로 노출. 실제 notification-hub 의 fan-out (이메일 /
# Slack / SMS) 은 빼두었다 — 본 레포의 *나가는 알림 통합점* 만 닫힘 검증.
FROM python:3.12-alpine

WORKDIR /app
RUN pip install --no-cache-dir flask==3.0.3 kafka-python==2.0.2

COPY app.py /app/app.py

EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["python3", "/app/app.py"]
