// Package runner 는 worker 의 핵심 라이프사이클을 담당.
//
// 흐름:
//
//	1. RUNNING 콜백 발송
//	2. (시뮬레이션) 지정된 시간 sleep + CPU burn 으로 GPU 작업 흉내
//	3. SUCCEEDED 또는 FAILED 콜백 발송
//
// chaos test 를 위해 fail probability 옵션을 둠. 0.3 이면 30% 확률로 실패.
package runner

import (
	"context"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"time"
)

type Runner struct {
	jobID           string
	duration        time.Duration
	failProbability float64
	outputURI       string
	callback        Callback
	now             func() time.Time
}

// Callback 은 orchestrator-api 로 상태를 통지하는 인터페이스.
type Callback interface {
	Send(ctx context.Context, jobID string, payload StatusPayload) error
}

type StatusPayload struct {
	Status       string `json:"status"`
	ResultURI    string `json:"resultUri,omitempty"`
	ErrorMessage string `json:"errorMessage,omitempty"`
}

func New(jobID string, duration time.Duration, failProbability float64,
	outputURI string, callback Callback) *Runner {
	return &Runner{
		jobID:           jobID,
		duration:        duration,
		failProbability: failProbability,
		outputURI:       outputURI,
		callback:        callback,
		now:             time.Now,
	}
}

func (r *Runner) Run(ctx context.Context) error {
	slog.Info("worker started", "jobId", r.jobID, "duration", r.duration.String())
	jobsStarted.Inc()

	// 1. RUNNING 콜백
	if err := r.callback.Send(ctx, r.jobID, StatusPayload{Status: "RUNNING"}); err != nil {
		jobsFailed.Inc()
		return fmt.Errorf("send RUNNING callback: %w", err)
	}

	// 2. 작업 시뮬레이션
	startedAt := r.now()
	if err := simulateGpuWork(ctx, r.duration); err != nil {
		jobsFailed.Inc()
		_ = r.callback.Send(context.Background(), r.jobID, StatusPayload{
			Status:       "FAILED",
			ErrorMessage: err.Error(),
		})
		return err
	}

	// 3. fail probability 체크 (chaos test)
	if r.failProbability > 0 && rand.Float64() < r.failProbability {
		slog.Info("simulating failure", "jobId", r.jobID, "probability", r.failProbability)
		jobsFailed.Inc()
		return r.callback.Send(context.Background(), r.jobID, StatusPayload{
			Status:       "FAILED",
			ErrorMessage: "simulated failure (chaos)",
		})
	}

	// 4. SUCCEEDED 콜백
	resultURI := r.outputURI
	if resultURI == "" {
		resultURI = fmt.Sprintf("s3://demo-output/%s.bin", r.jobID)
	}
	jobsSucceeded.Inc()
	jobDurationSeconds.Observe(r.now().Sub(startedAt).Seconds())

	return r.callback.Send(context.Background(), r.jobID, StatusPayload{
		Status:    "SUCCEEDED",
		ResultURI: resultURI,
	})
}

// simulateGpuWork 는 sleep + 가벼운 CPU burn 으로 GPU 작업을 흉내. context cancel 시 즉시 반환.
func simulateGpuWork(ctx context.Context, duration time.Duration) error {
	deadline := time.Now().Add(duration)
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-tick.C:
			// CPU burn 약간 (최적화 회피)
			x := 0
			for i := 0; i < 1_000_000; i++ {
				x += i
			}
			_ = x
			if time.Now().After(deadline) {
				return nil
			}
		}
	}
}
