// Package main 은 GPU Job worker 시뮬레이터.
//
// 실 환경에서는 Kubernetes Job 으로 띄워져 GPU 작업 (학습 / 추론) 을 수행하고
// orchestrator-api 의 콜백 endpoint 로 진행 상태를 통지한다. 본 시뮬레이터는 GPU 작업 대신
// 설정 가능한 시간만큼 sleep + CPU burn 으로 작업 시간을 흉내내고, 실제 콜백 흐름은 운영
// 워커와 동일하다.
//
// 사용:
//
//	worker --job-id=<uuid> --orchestrator-url=http://orchestrator-api.gwp:8080 \
//	       --callback-secret=$GWP_CALLBACK_SECRET --duration=30s
//
// 컨테이너 entrypoint 로 사용. K8s Job 의 args 로 위 플래그가 전달된다.
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ssa1004/gpu-job-orchestrator/worker/internal/runner"
)

func main() {
	var (
		jobID            = flag.String("job-id", "", "처리할 Job 의 UUID (필수)")
		orchestratorURL  = flag.String("orchestrator-url", envOr("ORCHESTRATOR_URL", "http://orchestrator-api:8080"), "orchestrator-api base URL")
		callbackSecret   = flag.String("callback-secret", os.Getenv("GWP_CALLBACK_SECRET"), "콜백 인증 시크릿")
		duration         = flag.Duration("duration", 30*time.Second, "GPU 작업 시뮬레이션 시간")
		failProbability  = flag.Float64("fail-probability", 0.0, "0.0~1.0 작업 실패 확률 (chaos test 용)")
		outputURI        = flag.String("output-uri", "", "결과 파일 URI (없으면 demo URI 자동 생성)")
		metricsPort      = flag.Int("metrics-port", 9090, "Prometheus metrics 노출 포트")
	)
	flag.Parse()

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	if *jobID == "" {
		slog.Error("--job-id required")
		os.Exit(2)
	}
	if *callbackSecret == "" {
		slog.Error("callback secret required (--callback-secret 또는 GWP_CALLBACK_SECRET)")
		os.Exit(2)
	}

	go startMetricsServer(*metricsPort)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go handleSignals(cancel)

	cb := runner.NewHTTPCallback(*orchestratorURL, *callbackSecret, http.DefaultClient)
	r := runner.New(*jobID, *duration, *failProbability, *outputURI, cb)

	if err := r.Run(ctx); err != nil {
		slog.Error("worker run failed", "error", err)
		os.Exit(1)
	}
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func handleSignals(cancel context.CancelFunc) {
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	slog.Info("signal received, cancelling", "signal", sig.String())
	cancel()
}

func startMetricsServer(port int) {
	mux := http.NewServeMux()
	mux.Handle("/metrics", runner.PromHandler())
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		_, _ = fmt.Fprint(w, "ok")
	})
	addr := fmt.Sprintf(":%d", port)
	slog.Info("metrics server starting", "addr", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		slog.Error("metrics server failed", "error", err)
	}
}
