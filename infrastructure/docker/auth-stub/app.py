"""Portfolio set 통합 시연용 mock IdP.

본 레포 (gpu-job-orchestrator) 의 SecurityConfig 가 OAuth2 resource server 로 동작할 때,
JWK Set URI 만 있으면 JWT 서명 검증이 가능하다. 이 stub 은 그 최소 인터페이스만 노출.

실제 운영의 auth-service 가 갖는 JWK rotation / refresh / revoke / scope 정책 / introspect
같은 기능은 일부러 빼두었다 — 이 stub 은 본 레포의 *들어오는 인증 통합점* 만 닫힘
검증한다. 운영에서는 auth-service 자체를 띄움.
"""
from __future__ import annotations

import base64
import time
import uuid
from typing import Any

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from flask import Flask, jsonify, request

# 컨테이너 시작 시 1회 생성. 재시작하면 새 kid 가 발급되어 orchestrator 가 새로 캐싱.
_PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_KID = "integration-demo-" + uuid.uuid4().hex[:8]
_ISSUER = "http://auth-stub:8080"
_DEFAULT_AUDIENCE = "gpu-job-orchestrator"
_DEFAULT_TTL_SECONDS = 600

app = Flask(__name__)


def _b64url_uint(value: int) -> str:
    """RSA modulus / exponent 를 JWK 의 base64url-uint 형식으로 변환 (RFC 7518 §6.3.1)."""
    byte_length = (value.bit_length() + 7) // 8
    raw = value.to_bytes(byte_length, "big")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _public_jwk() -> dict[str, Any]:
    numbers = _PRIVATE_KEY.public_key().public_numbers()
    return {
        "kty": "RSA",
        "use": "sig",
        "alg": "RS256",
        "kid": _KID,
        "n": _b64url_uint(numbers.n),
        "e": _b64url_uint(numbers.e),
    }


def _private_pem() -> bytes:
    return _PRIVATE_KEY.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )


@app.get("/oauth2/jwks")
def jwks() -> Any:
    """공개 JWK Set. orchestrator-api 의 SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI 가 가리키는 곳."""
    return jsonify({"keys": [_public_jwk()]})


@app.get("/.well-known/openid-configuration")
def discovery() -> Any:
    """Spring 의 issuer-uri 자동 discovery 호환. 시연 단순화를 위해 minimal 만."""
    return jsonify({
        "issuer": _ISSUER,
        "jwks_uri": f"{_ISSUER}/oauth2/jwks",
        "token_endpoint": f"{_ISSUER}/oauth2/token",
        "id_token_signing_alg_values_supported": ["RS256"],
    })


@app.post("/oauth2/token")
def token() -> Any:
    """access_token 즉석 발급.

    실제 OAuth2 client_credentials 처럼 form 데이터 (grant_type / scope) 를 받지만, 시연
    단순화를 위해 검증은 생략. ``sub`` 와 ``scope`` 만 그대로 받아 JWT 에 박는다.
    """
    sub = request.values.get("sub", "demo-user")
    scope = request.values.get("scope", "jobs.write jobs.read")
    audience = request.values.get("aud", _DEFAULT_AUDIENCE)
    ttl = int(request.values.get("ttl", _DEFAULT_TTL_SECONDS))

    now = int(time.time())
    payload = {
        "iss": _ISSUER,
        "sub": sub,
        "aud": audience,
        "iat": now,
        "exp": now + ttl,
        "scope": scope,
    }
    encoded = jwt.encode(payload, _private_pem(), algorithm="RS256", headers={"kid": _KID})
    return jsonify({
        "access_token": encoded,
        "token_type": "Bearer",
        "expires_in": ttl,
        "scope": scope,
    })


if __name__ == "__main__":
    # debug=False — 시연용이라도 reloader 로 인한 키페어 재생성을 막아 demo 도중 키 변경 방지.
    app.run(host="0.0.0.0", port=8080, debug=False)
