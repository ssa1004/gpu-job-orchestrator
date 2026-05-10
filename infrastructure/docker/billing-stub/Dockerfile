# Portfolio set 통합 시연용 mock billing-platform.
#
# Kafka 의 gwp.job.jobcompleted 를 consume → 본 레포의 GET /api/v1/cost/jobs/{id} 로
# 단가 박제 lookup → in-memory ledger 에 적재. /ledger endpoint 로 노출.
#
# 실제 billing-platform 의 청구서 / 결제 게이트웨이 / 정산 / 환불 흐름은 빼두었다 — 본
# 레포의 *나가는 빌링 통합점* (cost ledger 가 외부에서 조회 가능한가) 만 닫힘 검증.
FROM python:3.12-alpine

WORKDIR /app
RUN pip install --no-cache-dir flask==3.0.3 kafka-python==2.0.2 requests==2.32.3

COPY app.py /app/app.py

EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["python3", "/app/app.py"]
