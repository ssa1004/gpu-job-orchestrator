package runner

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"
)

// HTTPCallback 은 orchestrator-api 의 /internal/jobs/{id}/status endpoint 로 콜백을 전송.
//
// 재시도 정책: 지수 백오프, 최대 5회. 5xx / 네트워크 오류만 재시도. 4xx 는 즉시 실패.
type HTTPCallback struct {
	baseURL        string
	callbackSecret string
	client         *http.Client
}

func NewHTTPCallback(baseURL, secret string, client *http.Client) *HTTPCallback {
	return &HTTPCallback{baseURL: baseURL, callbackSecret: secret, client: client}
}

func (c *HTTPCallback) Send(ctx context.Context, jobID string, payload StatusPayload) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal payload: %w", err)
	}
	url := fmt.Sprintf("%s/internal/jobs/%s/status", c.baseURL, jobID)

	const maxAttempts = 5
	backoff := 500 * time.Millisecond
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, url,
			bytes.NewReader(body))
		if err != nil {
			return fmt.Errorf("build request: %w", err)
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-GWP-Callback-Secret", c.callbackSecret)

		resp, err := c.client.Do(req)
		if err != nil {
			lastErr = err
			callbackRetries.Inc()
			slog.Warn("callback transient error, retrying",
				"attempt", attempt, "error", err)
			time.Sleep(backoff)
			backoff *= 2
			continue
		}

		respBody, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()

		switch {
		case resp.StatusCode >= 200 && resp.StatusCode < 300:
			slog.Info("callback delivered", "jobId", jobID, "status", payload.Status)
			return nil
		case resp.StatusCode >= 400 && resp.StatusCode < 500:
			// 4xx — 클라이언트 에러, 재시도 무의미
			return fmt.Errorf("callback rejected by server (status=%d body=%s)",
				resp.StatusCode, string(respBody))
		default:
			lastErr = fmt.Errorf("server error status=%d body=%s",
				resp.StatusCode, string(respBody))
			callbackRetries.Inc()
			slog.Warn("callback 5xx, retrying", "attempt", attempt, "error", lastErr)
			time.Sleep(backoff)
			backoff *= 2
		}
	}
	return fmt.Errorf("callback failed after %d attempts: %w", maxAttempts, lastErr)
}
