package com.example.gwp.orchestrator.api.dto

import com.example.gwp.orchestrator.domain.JobStatus
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 워커가 orchestrator-api 로 보내는 상태 콜백 페이로드. `X-GWP-Callback-Secret`
 * 헤더로 1차 인증한 뒤 이 본문이 검증된다.
 *
 * **resultUri 패턴 강제**: SUCCEEDED 콜백의 결과 경로는 우리 storage 의 `s3://`
 * scheme 만 허용. 워커가 (실수 / 손상 / 적대적 워커 이미지) 으로 임의 URL (`https://`,
 * `file:`, `gopher:`) 을 박으면 후속 presigner 가 비-S3 입력을 받아 예상치 못한
 * 동작 / SSRF 유사 위험. JobSubmissionRequest 의 inputUri 와 동일 패턴으로 통일 — 입력 /
 * 출력 양쪽 trust boundary 에서 동일 검증 (API10 Unsafe Consumption of APIs 방어).
 *
 * errorMessage 는 자유 형식 문자열이지만 길이 상한으로 로그 / DB 폭주 방어.
 */
@JvmRecord
data class StatusCallbackRequest(
    @field:NotNull val status: JobStatus,
    @field:Size(max = 1024)
    @field:Pattern(
        regexp = "^${'$'}|^s3://[a-z0-9.\\-]+/.+",
        message = "resultUri must be empty or s3://bucket/key",
    )
    val resultUri: String?,
    @field:Size(max = 2048) val errorMessage: String?,
)
