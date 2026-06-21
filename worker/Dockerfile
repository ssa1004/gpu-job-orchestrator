# multi-stage Go build → distroless 최종 이미지 (~20MB)
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download || true
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags='-s -w' \
    -o /out/worker ./cmd/worker

FROM gcr.io/distroless/static-debian12:nonroot
WORKDIR /

# OCI image annotations — 레지스트리 / 스캐너 / SBOM 도구가 출처를 추적.
LABEL org.opencontainers.image.title="gpu-worker" \
      org.opencontainers.image.description="GPU job 실행 워커 (Go, distroless static)" \
      org.opencontainers.image.source="https://github.com/ssa1004/gpu-job-orchestrator" \
      org.opencontainers.image.licenses="Apache-2.0"

# distroless static 런타임에는 shell / apk / wget 이 없다. 따라서:
#   - apk upgrade: 적용 불가 (패키지 매니저 없음). 베이스 자체가 최소 + 정적 바이너리라
#     OS 패키지 CVE 표면이 사실상 없음 → distroless 갱신으로 패치 흡수.
#   - HEALTHCHECK: shell/curl 부재로 docker HEALTHCHECK 부적합. 워커 헬스는
#     K8s probe (별도 HTTP 핸들러) 로 관리한다.
COPY --from=build /out/worker /worker
USER nonroot:nonroot
EXPOSE 9090
ENTRYPOINT ["/worker"]
