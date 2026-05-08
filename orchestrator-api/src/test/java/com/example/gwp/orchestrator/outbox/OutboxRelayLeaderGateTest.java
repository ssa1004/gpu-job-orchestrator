package com.example.gwp.orchestrator.outbox;

import com.example.gwp.orchestrator.config.properties.GwpProperties;
import com.example.gwp.orchestrator.leader.LeaderElector;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Leader election 게이트 검증 — 비-리더 인스턴스의 tick 은 DB 접근 / Kafka send 어떤
 * 비용도 일으키지 않아야 한다.
 *
 * <p>K8s Lease 환경의 다중 Pod 시나리오: 3개 Pod 가 같은 시각에 polling 깨어나도, lease
 * 를 보유한 1개 Pod 만 SELECT / send 를 실행. 나머지 2개는 isLeader()=false 로 즉시 return.
 * 이 테스트가 그 직렬화를 회귀 방지.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayLeaderGateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T10:00:00Z"), ZoneOffset.UTC);

    @Mock OutboxRepository outboxRepository;
    @Mock @SuppressWarnings("rawtypes") KafkaTemplate kafkaTemplate;
    @Mock PlatformTransactionManager txManager;

    private static GwpProperties props() {
        return new GwpProperties(
                new GwpProperties.Kubernetes(false, "ns", 86400, "http://cb"),
                new GwpProperties.Storage(false, 3600),
                new GwpProperties.Callback("secret"),
                new GwpProperties.Security(new GwpProperties.Security.Jwt(false)),
                new GwpProperties.Outbox(new GwpProperties.Outbox.Relay(true, 1000, 100, 5000, "gwp.", 3)),
                new GwpProperties.Quota(10, 16, false),
                new GwpProperties.Leader("shedlock", "gwp", "gwp-orchestrator-leader", "test", 15, 10, 2)
        );
    }

    /** 비-리더는 SELECT 도 안 한다 (트랜잭션 시작 자체 X). */
    @SuppressWarnings("unchecked")
    @Test
    void publishPending_skipsWhenNotLeader() {
        LeaderElector notLeader = () -> false;

        OutboxRelay relay = new OutboxRelay(outboxRepository, kafkaTemplate, CLOCK,
                props(), txManager, null, notLeader);

        relay.publishPending();

        // tick 이 즉시 끝나야 — DB / Kafka 어떤 호출도 없다.
        verify(outboxRepository, never()).findUnpublished(any(Pageable.class));
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }
}
