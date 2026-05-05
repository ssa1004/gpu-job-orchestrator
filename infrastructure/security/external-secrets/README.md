# External Secrets Operator

K8s `Secret` 을 직접 commit 하지 않고 외부 secret manager (AWS Secrets Manager / HashiCorp
Vault / GCP Secret Manager) 에서 동기화. 운영 자격 증명이 git history 에 절대 노출되지
않게 보장합니다.

## 구성

```
external-secrets/
├── secret-store-aws.yaml      AWS Secrets Manager backend (IRSA 인증)
├── secret-store-vault.yaml    HashiCorp Vault backend (Kubernetes auth)
└── external-secrets/
    ├── orchestrator-db.yaml         DB 자격 증명 (5분 주기 동기화)
    ├── orchestrator-callback.yaml   워커 콜백 시크릿
    └── orchestrator-oauth.yaml      OAuth Issuer client secret
```

## 흐름

```
AWS Secrets Manager (gwp/orchestrator/db)
        ↓
ExternalSecret CR (5분 주기 polling 또는 ESO push 모드)
        ↓
K8s Secret (orchestrator-api-secret) 자동 생성/갱신
        ↓
Pod env 또는 mount
```

## 자격 증명 회전

운영자가 AWS Secrets Manager 에서 값을 변경하면 ExternalSecret 이 다음 polling 주기에
새 값을 K8s Secret 으로 동기화. Pod 의 env 는 자동 반영되지 않으므로 (env 는 Pod 시작 시
주입), Secret 변경 후 Deployment 를 rolling restart 가 필요합니다. 다음 옵션:

1. **Reloader** (https://github.com/stakater/Reloader) 추가 — Secret/ConfigMap 변경 감지
   시 Pod 자동 재시작
2. **Volume mount + 파일 watch** — Secret 을 file 로 마운트하고 application 이 SIGHUP 등
   으로 reload (현재 구조에서는 미적용)
3. **Manual rollout** — `kubectl rollout restart deploy/orchestrator-api`
