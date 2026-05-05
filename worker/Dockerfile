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
COPY --from=build /out/worker /worker
USER nonroot:nonroot
EXPOSE 9090
ENTRYPOINT ["/worker"]
