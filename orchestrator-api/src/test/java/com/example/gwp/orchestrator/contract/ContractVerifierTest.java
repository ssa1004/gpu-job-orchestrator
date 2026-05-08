package com.example.gwp.orchestrator.contract;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContractVerifier} 의 matcher 동작 단위 테스트. *깨졌을 때 fail 한다*, *건강할 때
 * 통과한다* 두 방향 다 검증.
 */
class ContractVerifierTest {

    @Test
    void verify_passesWhenConsumerExpectationFullyMet() {
        ConsumerExpectation exp = new ConsumerExpectation(
                "worker", "JobSubmitted",
                List.of("jobId", "image"),
                Map.of("priority", List.of("NORMAL", "HIGH")));

        List<String> violations = ContractVerifier.verify(EventCatalog.jobSubmitted(), exp);

        assertThat(violations).isEmpty();
    }

    @Test
    void verify_flagsMissingRequiredField() {
        ConsumerExpectation exp = new ConsumerExpectation(
                "worker", "JobSubmitted",
                List.of("jobId", "nonexistentField"),
                Map.of());

        List<String> violations = ContractVerifier.verify(EventCatalog.jobSubmitted(), exp);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0))
                .contains("worker")
                .contains("nonexistentField");
    }

    @Test
    void verify_flagsMissingEnumValueAtProducer() {
        // consumer 는 priority 의 "GODLIKE" 값을 가정 — producer enum 에는 없으니 fail.
        ConsumerExpectation exp = new ConsumerExpectation(
                "worker", "JobSubmitted",
                List.of("jobId"),
                Map.of("priority", List.of("NORMAL", "GODLIKE")));

        List<String> violations = ContractVerifier.verify(EventCatalog.jobSubmitted(), exp);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("GODLIKE");
    }

    @Test
    void verify_ignoresExtraProducerFields_openWorld() {
        // Producer 가 새 필드 'newField' 를 추가했음. consumer 는 모름 → expectation 에 없음.
        // 이 경우 backward-compat 한 추가이므로 verifier 는 *fail 시키지 않아야* 한다.
        Map<String, Map<String, Object>> producerProps = new LinkedHashMap<>(
                EventCatalog.jobSubmitted().properties());
        producerProps.put("newField", Map.of("type", "string"));
        EventSchema producerWithExtra = new EventSchema(
                "JobSubmitted",
                "테스트용 — 새 필드 추가",
                producerProps,
                EventCatalog.jobSubmitted().required());

        ConsumerExpectation exp = new ConsumerExpectation(
                "worker", "JobSubmitted",
                List.of("jobId", "image"),
                Map.of());

        List<String> violations = ContractVerifier.verify(producerWithExtra, exp);

        assertThat(violations).isEmpty();
    }

    @Test
    void verifyAll_collectsAllViolationsFromMultipleConsumers() {
        ConsumerExpectation worker = new ConsumerExpectation(
                "worker", "JobSubmitted",
                List.of("jobId", "ghostField1"), Map.of());
        ConsumerExpectation billing = new ConsumerExpectation(
                "billing", "JobCompleted",
                List.of("jobId", "ghostField2"), Map.of());

        List<String> violations = ContractVerifier.verifyAll(
                EventCatalog.all(),
                List.of(worker, billing));

        assertThat(violations).hasSize(2);
        assertThat(violations).anyMatch(v -> v.contains("ghostField1"));
        assertThat(violations).anyMatch(v -> v.contains("ghostField2"));
    }

    @Test
    void verifyAll_flagsUnknownEventType() {
        ConsumerExpectation rogue = new ConsumerExpectation(
                "future-consumer", "JobAchievedSentience",
                List.of("jobId"), Map.of());

        List<String> violations = ContractVerifier.verifyAll(
                EventCatalog.all(), List.of(rogue));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("future-consumer").contains("JobAchievedSentience");
    }
}
