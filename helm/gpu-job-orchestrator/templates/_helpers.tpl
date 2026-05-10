{{/*
공통 helper — 이름 / 라벨 / serviceaccount 이름 등.
다른 템플릿에서 {{ include "gpu-job-orchestrator.fullname" . }} 식으로 참조.
*/}}

{{/*
chart name 짧은 표현 — DNS 라벨 63자 제한 안에 맞춤.
*/}}
{{- define "gpu-job-orchestrator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
release-aware fullname.
release name 이 chart name 으로 시작하면 중복 prefix 제거.
*/}}
{{- define "gpu-job-orchestrator.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
chart 식별자 (chart 이름 + 버전). 표준 라벨 helm.sh/chart 에 박힘.
*/}}
{{- define "gpu-job-orchestrator.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
공통 라벨 — 모든 리소스의 metadata.labels 에 들어감.
app.kubernetes.io/* 는 K8s 표준 라벨 셋 (Recommended Labels).
*/}}
{{- define "gpu-job-orchestrator.labels" -}}
helm.sh/chart: {{ include "gpu-job-orchestrator.chart" . }}
{{ include "gpu-job-orchestrator.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: gwp-platform
{{- end -}}

{{/*
selector 라벨 — Deployment / Service / NetworkPolicy 의 selector 에 사용.
selector 는 *immutable* 이라 한번 박힌 라벨을 바꾸면 upgrade 가 깨진다 →
labels 와 분리해서 보수적으로 유지.
*/}}
{{- define "gpu-job-orchestrator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "gpu-job-orchestrator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
ServiceAccount 이름 — create=true 면 fullname, false 면 직접 지정한 이름.
*/}}
{{- define "gpu-job-orchestrator.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "gpu-job-orchestrator.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
이미지 reference — tag 가 비면 Chart.appVersion 으로 fallback.
*/}}
{{- define "gpu-job-orchestrator.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}
