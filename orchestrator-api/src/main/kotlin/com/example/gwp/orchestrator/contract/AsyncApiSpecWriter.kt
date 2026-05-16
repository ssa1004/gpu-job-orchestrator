package com.example.gwp.orchestrator.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * [AsyncApiSpecBuilder] 가 만든 nested Map 을 YAML 또는 JSON 으로 직렬화.
 *
 * YAML 옵션은 AsyncAPI Studio 와의 호환을 위해:
 * - [YAMLGenerator.Feature.WRITE_DOC_START_MARKER] OFF — 위쪽에 `---` 안 붙임.
 * - [YAMLGenerator.Feature.MINIMIZE_QUOTES] ON — 단순 문자열은 quote 없이.
 * - [SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS] OFF — [java.util.LinkedHashMap]
 *   의 선언 순서 보존 (info → servers → channels → ... 순서).
 *
 * Java 호출자 (`AsyncApiSpecWriter.toYaml(spec)` 등) 그대로 동작 — Kotlin `object` 의
 * `@JvmStatic` 메서드가 같은 static 시그니처를 노출.
 */
object AsyncApiSpecWriter {

    @JvmStatic
    fun toYaml(spec: Map<String, Any>): String =
        try {
            yamlMapper().writeValueAsString(spec)
        } catch (e: IOException) {
            throw IllegalStateException("YAML 직렬화 실패", e)
        }

    @JvmStatic
    fun toJson(spec: Map<String, Any>): String =
        try {
            jsonMapper().writeValueAsString(spec)
        } catch (e: IOException) {
            throw IllegalStateException("JSON 직렬화 실패", e)
        }

    /** YAML 을 파일로 출력 — docs/asyncapi/job-events.yaml 등의 baseline 용. */
    @JvmStatic
    @Throws(IOException::class)
    fun writeYaml(spec: Map<String, Any>, target: Path) {
        Files.createDirectories(target.parent)
        Files.writeString(target, toYaml(spec))
    }

    private fun yamlMapper(): ObjectMapper {
        val factory = YAMLFactory()
        factory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        factory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
        val mapper = ObjectMapper(factory)
        mapper.disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        return mapper
    }

    private fun jsonMapper(): ObjectMapper {
        val mapper = ObjectMapper()
        mapper.disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        mapper.enable(SerializationFeature.INDENT_OUTPUT)
        return mapper
    }
}
