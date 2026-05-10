# Portfolio set 통합 시연용 mock IdP.
#
# Spring Authorization Server (auth-service) 의 / oauth2/jwks 와 / oauth2/token
# 두 endpoint 만 흉내내는 최소 stub. 실제 검증 흐름:
#   1. /oauth2/jwks   — 공개 JWK Set 노출 (orchestrator-api 가 캐싱하고 서명 검증)
#   2. /oauth2/token  — RS256 으로 서명한 access_token 즉석 발급 (sub / scope 박음)
#
# RSA 키페어는 컨테이너 시작 시 1회 생성 (in-memory). 시연용이므로 영속성 / rotation 없음.
# 실제 IdP 의 JWK rotation / refresh / revoke 는 본 레포가 아닌 auth-service 책임.
FROM python:3.12-alpine

WORKDIR /app
RUN pip install --no-cache-dir flask==3.0.3 PyJWT==2.9.0 cryptography==43.0.1

COPY app.py /app/app.py

EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=10 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/oauth2/jwks || exit 1

ENTRYPOINT ["python3", "/app/app.py"]
