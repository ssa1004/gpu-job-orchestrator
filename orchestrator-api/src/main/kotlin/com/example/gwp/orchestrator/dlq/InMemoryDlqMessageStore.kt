package com.example.gwp.orchestrator.dlq

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 인-메모리 DLQ 메시지 저장 — dev / 단위 테스트 / 콘솔 데모용.
 *
 * 운영에서는 [KafkaDlqMessageStore] (또는 동등한 DB-backed) 으로 교체. `@Profile("!prod")`
 * 같은 분기로 wiring (DlqAdminConfig). 두 구현 모두 같은 [DlqMessageStore] 인터페이스를
 * 만족하므로 컨트롤러 / service / controller test 가 완전히 동일.
 *
 * - [replay] / [discard] 의 *멱등성* — 같은 idempotencyKey 로 다시 호출되면 IGNORED.
 *   gpu 도메인의 idempotent callback path (JobLifecycleService 의 already-terminal
 *   short-circuit) 와 같은 결합.
 * - cursor pagination — `lastSeenAt|id` 를 Base64 로 직렬화. 같은 timestamp 의 충돌은
 *   id 로 tie-break.
 */
class InMemoryDlqMessageStore : DlqMessageStore {

    private val lock = ReentrantReadWriteLock()
    private val messages: LinkedHashMap<String, DlqMessage> = LinkedHashMap()
    private val replayedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val discarded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 테스트 / dev 에서 메시지를 주입하는 helper. 운영 store 에는 같은 메서드 X. */
    fun seed(message: DlqMessage) = lock.write {
        messages[message.id] = message
    }

    fun clear() = lock.write {
        messages.clear()
        replayedKeys.clear()
        discarded.clear()
    }

    override fun list(filter: DlqEntryFilter): DlqListPage = lock.read {
        val matched = messages.values.asSequence()
            .filterNot { it.id in discarded }
            .filter { filter.source == null || it.source == filter.source }
            .filter { filter.topic.isNullOrBlank() || it.topic == filter.topic }
            .filter { filter.errorType.isNullOrBlank() || it.errorType == filter.errorType }
            .filter { filter.from == null || !it.lastSeenAt.isBefore(filter.from) }
            .filter { filter.to == null || it.lastSeenAt.isBefore(filter.to) }
            .sortedWith(compareBy({ it.lastSeenAt }, { it.id }))
            .toList()

        val startIdx = decodeCursor(filter.cursor)
            ?.let { (ts, id) ->
                matched.indexOfFirst {
                    it.lastSeenAt.isAfter(ts) || (it.lastSeenAt == ts && it.id > id)
                }
            }
            ?.takeIf { it >= 0 }
            ?: 0
        val size = filter.size.coerceIn(1, MAX_PAGE_SIZE)
        val endIdx = minOf(startIdx + size, matched.size)
        val items = matched.subList(startIdx, endIdx)
        val nextCursor = if (endIdx < matched.size && items.isNotEmpty()) {
            val last = items.last()
            encodeCursor(last.lastSeenAt, last.id)
        } else {
            null
        }
        DlqListPage(items = items.toList(), nextCursor = nextCursor)
    }

    override fun findById(id: String): DlqMessage? = lock.read {
        if (id in discarded) null else messages[id]
    }

    override fun replay(id: String, idempotencyKey: String, actor: String): DlqMessageStore.ReplayOutcome {
        val message = lock.read { messages[id] } ?: return DlqMessageStore.ReplayOutcome.IGNORED
        if (id in discarded) return DlqMessageStore.ReplayOutcome.IGNORED
        if (!replayedKeys.add(idempotencyKey)) {
            log.debug("dlq replay idempotent hit id={} key={}", id, idempotencyKey)
            return DlqMessageStore.ReplayOutcome.IGNORED
        }
        log.debug(
            "dlq replay (in-memory) id={} source={} topic={} actor={}",
            id, message.source, message.topic, actor,
        )
        return DlqMessageStore.ReplayOutcome.SUCCESS
    }

    override fun discard(id: String, reason: String, actor: String): DlqMessageStore.DiscardOutcome {
        val present = lock.read { messages.containsKey(id) }
        if (!present) return DlqMessageStore.DiscardOutcome.IGNORED
        if (!discarded.add(id)) return DlqMessageStore.DiscardOutcome.IGNORED
        log.debug("dlq discard (in-memory) id={} actor={} reason={}", id, actor, reason)
        return DlqMessageStore.DiscardOutcome.SUCCESS
    }

    override fun stats(filter: DlqEntryFilter, bucket: String): DlqStats = lock.read {
        val from = filter.from ?: Instant.EPOCH
        val to = filter.to ?: Instant.now().plusSeconds(1)
        val matched = messages.values.asSequence()
            .filterNot { it.id in discarded }
            .filter { filter.source == null || it.source == filter.source }
            .filter { filter.topic.isNullOrBlank() || it.topic == filter.topic }
            .filter { filter.errorType.isNullOrBlank() || it.errorType == filter.errorType }
            .filter { !it.lastSeenAt.isBefore(from) && it.lastSeenAt.isBefore(to) }
            .toList()

        val bySource = matched.groupingBy { it.source }.eachCount()
            .mapValues { (_, v) -> v.toLong() }
            .toSortedMap()
        val byTopic = matched.groupingBy { it.topic }.eachCount()
            .mapValues { (_, v) -> v.toLong() }
            .toSortedMap()
        val byErrorType = matched.groupingBy { it.errorType }.eachCount()
            .mapValues { (_, v) -> v.toLong() }
            .toSortedMap()
        val byOwner = matched.groupingBy { it.ownerId ?: "" }.eachCount()
            .mapValues { (_, v) -> v.toLong() }
            .filterKeys { it.isNotEmpty() }
            .toSortedMap()
        val byGpuClass = matched.groupingBy { it.gpuClass ?: "" }.eachCount()
            .mapValues { (_, v) -> v.toLong() }
            .filterKeys { it.isNotEmpty() }
            .toSortedMap()

        val bucketDuration = parseBucket(bucket)
        val buckets = TreeMap<Instant, Long>()
        for (msg in matched) {
            val start = bucketStart(from, msg.lastSeenAt, bucketDuration)
            buckets.merge(start, 1L) { a, b -> a + b }
        }

        DlqStats(
            from = from,
            to = to,
            total = matched.size.toLong(),
            bySource = bySource,
            byTopic = byTopic,
            byErrorType = byErrorType,
            byOwner = byOwner,
            byGpuClass = byGpuClass,
            buckets = buckets.map { (start, c) -> DlqStats.DlqStatsBucket(start, c) },
        )
    }

    private fun parseBucket(bucket: String): Duration =
        try {
            Duration.parse(bucket).takeIf { !it.isZero && !it.isNegative }
                ?: throw DlqAdminBadRequestException("bucket must be positive ISO-8601 duration")
        } catch (_: java.time.format.DateTimeParseException) {
            throw DlqAdminBadRequestException("invalid bucket: $bucket")
        }

    private fun bucketStart(epoch: Instant, ts: Instant, bucket: Duration): Instant {
        val deltaMs = Duration.between(epoch, ts).toMillis()
        val bucketMs = bucket.toMillis().coerceAtLeast(1)
        val floored = (deltaMs / bucketMs) * bucketMs
        return epoch.plusMillis(floored)
    }

    private fun encodeCursor(ts: Instant, id: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${ts.toEpochMilli()}|$id".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String?): Pair<Instant, String>? {
        if (cursor.isNullOrBlank()) return null
        return try {
            val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val (tsStr, id) = decoded.split('|', limit = 2)
            Instant.ofEpochMilli(tsStr.toLong()) to id
        } catch (_: IllegalArgumentException) {
            throw DlqAdminBadRequestException("invalid cursor")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(InMemoryDlqMessageStore::class.java)
        private const val MAX_PAGE_SIZE = 100
    }
}
