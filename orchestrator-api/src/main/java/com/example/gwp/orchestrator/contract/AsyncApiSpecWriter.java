package com.example.gwp.orchestrator.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@link AsyncApiSpecBuilder} 가 만든 nested Map 을 YAML 또는 JSON 으로 직렬화.
 *
 * <p>YAML 옵션은 AsyncAPI Studio 와의 호환을 위해:
 * <ul>
 *   <li>{@link YAMLGenerator.Feature#WRITE_DOC_START_MARKER} OFF — 위쪽에 {@code ---} 안 붙임.</li>
 *   <li>{@link YAMLGenerator.Feature#MINIMIZE_QUOTES} ON — 단순 문자열은 quote 없이.</li>
 *   <li>{@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} OFF — {@link java.util.LinkedHashMap}
 *       의 선언 순서 보존 (info → servers → channels → ... 순서).</li>
 * </ul>
 */
public final class AsyncApiSpecWriter {

    private AsyncApiSpecWriter() {}

    public static String toYaml(Map<String, Object> spec) {
        try {
            return yamlMapper().writeValueAsString(spec);
        } catch (IOException e) {
            throw new IllegalStateException("YAML 직렬화 실패", e);
        }
    }

    public static String toJson(Map<String, Object> spec) {
        try {
            return jsonMapper().writeValueAsString(spec);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }

    /** YAML 을 파일로 출력 — docs/asyncapi/job-events.yaml 등의 baseline 용. */
    public static void writeYaml(Map<String, Object> spec, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, toYaml(spec));
    }

    private static ObjectMapper yamlMapper() {
        YAMLFactory factory = new YAMLFactory();
        factory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        factory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        ObjectMapper mapper = new ObjectMapper(factory);
        mapper.disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return mapper;
    }

    private static ObjectMapper jsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }
}
