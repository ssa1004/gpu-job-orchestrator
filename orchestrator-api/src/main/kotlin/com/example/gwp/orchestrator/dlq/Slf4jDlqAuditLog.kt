package com.example.gwp.orchestrator.dlq

import org.slf4j.LoggerFactory

/**
 * 운영 환경에서도 일단 Slf4j logger 로 audit 라인을 남긴다. 별 'audit' logger 명을 써서
 * logback 설정에서 audit appender 로 분리할 수 있다.
 *
 * Kafka audit topic 으로 발행하는 변형은 후속 단계 — 본 ADR '다시 검토할 시점' 참고.
 */
class Slf4jDlqAuditLog : DlqAuditLog {

    override fun log(entry: DlqAuditLog.Entry) {
        log.info(
            "DLQ_AUDIT action={} actor={} target={} outcome={} jobId={} owner={} reason={}",
            entry.action, entry.actor, entry.target, entry.outcome,
            entry.jobId ?: "-", entry.ownerId ?: "-",
            entry.reason ?: "-",
        )
    }

    companion object {
        // logger 이름을 'audit' prefix 로 — logback 에서 별도 appender 로 분리 가능.
        private val log = LoggerFactory.getLogger("audit.dlq")
    }
}
