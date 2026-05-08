package com.example.gwp.orchestrator.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레포에 commit 된 AsyncAPI baseline ({@code docs/asyncapi/job-events.yaml}) 이 *현재
 * 코드의 catalog* 와 동일한지 검증.
 *
 * <p>Drift 가 있으면 fail. 메시지에 갱신 명령을 적어 준다 — 개발자는 의식적으로 baseline
 * 을 갱신해야 한다 (자동 덮어쓰기 X — schema 변경은 항상 의식적 결정).</p>
 *
 * <p>이 baseline 파일이 외부 팀 / 대시보드 / docs site 에 공유되는 단일 source of truth.
 * 코드 리뷰에서 "이 PR 이 발행 contract 를 바꾸나?" 를 baseline 의 git diff 로 한눈에 확인.</p>
 */
class AsyncApiSpecBaselineTest {

    private static final Path BASELINE = Path.of("docs/asyncapi/job-events.yaml");

    @Test
    @DisplayName("docs/asyncapi/job-events.yaml 이 EventCatalog 와 동기화되어 있다")
    void baselineYaml_matchesCurrentCatalog() throws IOException {
        Map<String, Object> spec = AsyncApiSpecBuilder.build(
                AsyncApiSpecBuilder.SpecInfo.defaultInfo(),
                "gwp.",
                null,
                EventCatalog.all());
        String currentYaml = AsyncApiSpecWriter.toYaml(spec);

        if (!Files.exists(BASELINE)) {
            // baseline 부재 — fail 시키되, build/ 아래에 *후보 파일* 을 떨궈서 개발자가
            // 의식적으로 git mv 로 옮기게 한다. 자동으로 docs/ 아래에 만들지 않는 이유:
            // contract 출시는 항상 명시적 행위. 운영 중에 *얼결에 만들어진 spec* 을 외부에
            // 공개하면 안 됨.
            Path dump = Path.of("build/asyncapi-baseline.yaml");
            Files.createDirectories(dump.getParent());
            Files.writeString(dump, currentYaml);
            assertThat(false)
                    .as("baseline 파일이 없다. build/asyncapi-baseline.yaml 을 확인하고, 의도한 spec 이 "
                            + "맞으면 docs/asyncapi/job-events.yaml 로 옮긴 뒤 commit 하라.")
                    .isTrue();
            return;
        }

        String committed = Files.readString(BASELINE);

        if (!currentYaml.equals(committed)) {
            // build 디렉토리에 *최신* spec 을 떨궈둠 — 개발자가 그것을 직접 baseline 으로 옮기게.
            Path dump = Path.of("build/asyncapi-baseline.yaml");
            Files.createDirectories(dump.getParent());
            Files.writeString(dump, currentYaml);
        }

        assertThat(currentYaml)
                .as("AsyncAPI baseline drift — EventCatalog 가 바뀌었는데 docs/asyncapi/job-events.yaml "
                        + "이 갱신되지 않음. build/asyncapi-baseline.yaml 을 docs/asyncapi/job-events.yaml "
                        + "로 옮긴 뒤 commit 하라.")
                .isEqualTo(committed);
    }
}
