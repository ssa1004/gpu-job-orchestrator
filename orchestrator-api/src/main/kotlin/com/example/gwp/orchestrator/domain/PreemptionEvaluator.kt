package com.example.gwp.orchestrator.domain

import java.util.Comparator

/**
 * 한 대기 중 (QUEUED) 잡을 살리기 위해 어떤 ACTIVE 잡들을 preempt (양보 시킴) 할지 결정.
 *
 * **알고리즘** (그리디 — 매 단계마다 가장 좋아 보이는 선택을 누적, Slurm (HPC 작업
 * 스케줄러) / Kueue (K8s 배치 큐잉 시스템) 도 큰 틀은 같음):
 * 1. 대상 후보: ACTIVE + PREEMPTABLE + priority < preemptor.priority 인 잡들
 * 2. 정렬: priority 낮은 순 → 같으면 *늦게 시작한 순* (덜 진행됐으니 손실 적음)
 * 3. 위에서부터 누적 GPU 가 preemptor.gpuCount 도달할 때까지 victim 추가
 * 4. 모자라면 noop (아무 것도 안 함) 반환 (preempt 해도 자리가 안 남)
 *
 * **왜 같은 / 높은 priority 는 절대 preempt 안 하나**: NORMAL 로 제출한 잡은
 * 자기보다 같은 NORMAL / 더 낮은 LOW 에게는 안 죽음. HIGH 가 들어왔을 때만 양보.
 * NORMAL 끼리 서로 죽이면 운영 예측 불가.
 *
 * **왜 늦게 시작한 victim 우선**: 학습 잡이 90% 진행됐는데 죽이면 손실 큼. 갓 시작한
 * 잡은 재시작해도 손실 적음. 더 정교하게 하려면 *체크포인트 (중간 저장본) 가 있는 잡*
 * 우선 (현재 모델엔 없음).
 *
 * **NEVER 잡은 무조건 보호**: 후보에서 제외. 즉 NEVER 잡들이 GPU 점유 중이면 더 높은
 * 우선순위 잡도 양보 못 받고 그냥 큐에서 대기. 운영자가 NEVER 를 신중하게 부여해야 한다.
 *
 * Java 호출자 (`PreemptionEvaluator.evaluate(preemptor, activeJobs)`) 그대로 동작 —
 * `object` + `@JvmStatic` 으로 static 메서드 합성.
 */
object PreemptionEvaluator {

    /**
     * @param preemptor    QUEUED 상태의 새 잡 (또는 dispatch 시도 중인 잡)
     * @param activeJobs   현재 ACTIVE (QUEUED 제외, RUNNING + DISPATCHING) 잡 전체
     * @return preempt 결정
     */
    @JvmStatic
    fun evaluate(preemptor: Job, activeJobs: List<Job>): PreemptionDecision {
        // 1. 후보 필터: 자기 자신 제외 + PREEMPTABLE + 더 낮은 priority + ACTIVE.
        //
        // 2. 정렬 — 죽이기 좋은 순서 (먼저 줄에 세움):
        //    primary key   : priority weight 오름차순           (낮은 priority 가 먼저)
        //    secondary key : startedAt 내림차순 + null 을 맨 위 (늦게 시작 / 아직 시작 전이 먼저)
        //
        // null 처리: startedAt == null 은 DISPATCHING 상태 (Pod 제출했지만 아직 RUNNING 못 함).
        // 진행도 0% 라 손실이 가장 적으므로 "맨 위" 로 보낸다 → nullsFirst(reverseOrder()).
        val candidates: List<Job> = activeJobs.asSequence()
            .filter { it.id != preemptor.id }
            .filter { it.isPreemptable() }
            .filter { preemptor.priority.higherThan(it.priority) }
            .sortedWith(
                Comparator.comparingInt<Job> { it.priority.weight() }
                    .thenComparing(
                        Comparator.comparing(
                            { j: Job -> j.startedAt },
                            Comparator.nullsFirst(Comparator.reverseOrder()),
                        ),
                    ),
            )
            .toList()

        // 2. 누적 GPU 가 필요량 채울 때까지 victim 모으기
        val chosen: MutableList<Job> = ArrayList()
        var freed = 0
        for (c in candidates) {
            if (freed >= preemptor.gpuCount) break
            chosen.add(c)
            freed += c.gpuCount
        }

        // 3. 모자라면 preempt 안 함 (양보해도 GPU 가 모자라 어차피 dispatch 못 함)
        if (freed < preemptor.gpuCount) {
            return PreemptionDecision.noop(preemptor)
        }
        return PreemptionDecision(preemptor, chosen)
    }
}
