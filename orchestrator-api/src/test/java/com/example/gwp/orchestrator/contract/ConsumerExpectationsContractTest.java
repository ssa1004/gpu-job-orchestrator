package com.example.gwp.orchestrator.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-driven contract verification — 실제 운영 consumer 들이 자기 expectations.json
 * 을 {@code src/test/resources/contracts/expectations/} 에 두면 producer 빌드가 그것을
 * 카탈로그에 대해 매번 검증한다.
 *
 * <p>이 테스트가 *fail* 하면 producer 의 변경이 한 명 이상의 consumer 를 깨뜨릴 수 있다는
 * 의미. 메시지에 어떤 consumer / 어떤 필드 / 어떤 enum 값인지 적혀 있으니 그걸 봐서
 * (1) producer 의 변경을 backward-compat 형태로 다듬거나, (2) consumer 와 사전 협의 후
 * expectations.json 을 갱신한다.</p>
 *
 * <p>운영 환경에서 더 정교한 워크플로우가 필요하면 Pact Broker / Spring Cloud Contract
 * 를 도입한다 — ADR-0020 의 "다시 검토할 시점" 참고.</p>
 */
class ConsumerExpectationsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path EXPECTATIONS_DIR =
            Path.of("src/test/resources/contracts/expectations");

    @Test
    @DisplayName("모든 consumer expectations 가 producer catalog 를 만족한다")
    void allConsumerExpectations_areSatisfiedByCurrentCatalog() throws IOException {
        List<ConsumerExpectation> expectations = loadAllExpectations();
        assertThat(expectations).as("expectations 디렉토리가 비어있음").isNotEmpty();

        List<String> violations = ContractVerifier.verifyAll(
                EventCatalog.all(), expectations);

        assertThat(violations)
                .as("contract 위반 — producer 변경이 consumer 를 깨뜨렸음:\n  - %s",
                        String.join("\n  - ", violations))
                .isEmpty();
    }

    /**
     * {@code src/test/resources/contracts/expectations/<consumer>.json} 모두 읽어서
     * {@link ConsumerExpectation} 리스트로 변환.
     */
    static List<ConsumerExpectation> loadAllExpectations() throws IOException {
        if (!Files.isDirectory(EXPECTATIONS_DIR)) return List.of();
        try (Stream<Path> stream = Files.list(EXPECTATIONS_DIR)) {
            return stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .flatMap(ConsumerExpectationsContractTest::expectationsFromFile)
                    .collect(Collectors.toList());
        }
    }

    private static Stream<ConsumerExpectation> expectationsFromFile(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            JsonNode root = MAPPER.readTree(in);
            String consumerName = root.path("consumerName").asText();
            JsonNode expectations = root.path("expectations");
            List<ConsumerExpectation> result = new ArrayList<>();
            for (JsonNode node : expectations) {
                String eventType = node.path("eventType").asText();
                List<String> required = readStringList(node.path("requiredFields"));
                Map<String, List<String>> enumValues = readEnumMap(node.path("enumValues"));
                result.add(new ConsumerExpectation(consumerName, eventType, required, enumValues));
            }
            return result.stream();
        } catch (IOException e) {
            throw new IllegalStateException("expectations 파일 읽기 실패: " + file, e);
        }
    }

    private static List<String> readStringList(JsonNode array) {
        List<String> list = new ArrayList<>();
        if (array.isArray()) array.forEach(n -> list.add(n.asText()));
        return list;
    }

    private static Map<String, List<String>> readEnumMap(JsonNode object) {
        Map<String, List<String>> map = new java.util.LinkedHashMap<>();
        if (!object.isObject()) return map;
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            map.put(key, readStringList(object.get(key)));
        }
        return map;
    }
}
