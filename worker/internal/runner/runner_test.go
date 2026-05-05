package runner

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type stubCallback struct {
	mu       sync.Mutex
	received []StatusPayload
	failOn   string // 이 상태 콜백 시 에러 반환
}

func (s *stubCallback) Send(_ context.Context, _ string, payload StatusPayload) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.received = append(s.received, payload)
	if s.failOn == payload.Status {
		return errors.New("stub callback error")
	}
	return nil
}

func (s *stubCallback) Statuses() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, len(s.received))
	for i, p := range s.received {
		out[i] = p.Status
	}
	return out
}

func TestRun_HappyPath_RunningThenSucceeded(t *testing.T) {
	cb := &stubCallback{}
	r := New("job-1", 50*time.Millisecond, 0, "s3://out/x", cb)
	if err := r.Run(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	got := cb.Statuses()
	want := []string{"RUNNING", "SUCCEEDED"}
	if len(got) != 2 || got[0] != want[0] || got[1] != want[1] {
		t.Errorf("got %v, want %v", got, want)
	}
}

func TestRun_AlwaysFailProbability_SendsFAILED(t *testing.T) {
	cb := &stubCallback{}
	r := New("job-1", 10*time.Millisecond, 1.0, "", cb)
	if err := r.Run(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	got := cb.Statuses()
	if len(got) != 2 || got[1] != "FAILED" {
		t.Errorf("expected FAILED at end, got %v", got)
	}
}

func TestRun_ContextCancel_PropagatesError(t *testing.T) {
	cb := &stubCallback{}
	r := New("job-1", 5*time.Second, 0, "", cb)
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(20 * time.Millisecond)
		cancel()
	}()
	err := r.Run(ctx)
	if err == nil {
		t.Fatal("expected error from canceled context")
	}
}

func TestRun_RunningCallbackFailure_AbortsBeforeWork(t *testing.T) {
	cb := &stubCallback{failOn: "RUNNING"}
	r := New("job-1", 100*time.Millisecond, 0, "", cb)
	err := r.Run(context.Background())
	if err == nil {
		t.Fatal("expected error from failed RUNNING callback")
	}
	got := cb.Statuses()
	if len(got) != 1 || got[0] != "RUNNING" {
		t.Errorf("expected only RUNNING attempt, got %v", got)
	}
}
