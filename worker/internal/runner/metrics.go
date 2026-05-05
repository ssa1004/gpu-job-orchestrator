package runner

import (
	"net/http"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
	jobsStarted = promauto.NewCounter(prometheus.CounterOpts{
		Namespace: "gwp_worker",
		Name:      "jobs_started_total",
		Help:      "Total Jobs started by this worker.",
	})
	jobsSucceeded = promauto.NewCounter(prometheus.CounterOpts{
		Namespace: "gwp_worker",
		Name:      "jobs_succeeded_total",
		Help:      "Total Jobs that completed successfully.",
	})
	jobsFailed = promauto.NewCounter(prometheus.CounterOpts{
		Namespace: "gwp_worker",
		Name:      "jobs_failed_total",
		Help:      "Total Jobs that failed.",
	})
	jobDurationSeconds = promauto.NewHistogram(prometheus.HistogramOpts{
		Namespace: "gwp_worker",
		Name:      "job_duration_seconds",
		Help:      "Job processing duration (excluding callback time).",
		Buckets:   []float64{1, 5, 10, 30, 60, 120, 300, 600, 1800, 3600, 7200},
	})
	callbackRetries = promauto.NewCounter(prometheus.CounterOpts{
		Namespace: "gwp_worker",
		Name:      "callback_retries_total",
		Help:      "Total callback retry attempts (transient failures).",
	})
)

func PromHandler() http.Handler { return promhttp.Handler() }
