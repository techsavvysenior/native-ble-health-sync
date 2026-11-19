package com.example.pulselog.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UploadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun hr(at: Long, bpm: Int = 72) =
        Reading.from("AA:BB:CC:DD:EE:01", ReadingKind.HEART_RATE, at, byteArrayOf(0x06, bpm.toByte()))

    @Test
    fun `UC-08 the same reading queued twice is stored once`() {
        val queue = UploadQueue(MemoryQueueStore())
        assertTrue(queue.add(hr(1_000)))
        assertFalse(queue.add(hr(1_000)))
        assertEquals(1, queue.size)
    }

    @Test
    fun `UC-08 batches come out oldest first and leave only when acknowledged`() {
        val queue = UploadQueue(MemoryQueueStore())
        (1..5).forEach { queue.add(hr(it * 1_000L)) }
        val batch = queue.nextBatch(limit = 2)
        assertEquals(listOf(1_000L, 2_000L), batch.map { it.receivedAtMs })
        assertEquals(5, queue.size) // nothing removed until the upload succeeds
        queue.acknowledge(batch.map { it.id })
        assertEquals(listOf(3_000L, 4_000L, 5_000L), queue.nextBatch().map { it.receivedAtMs })
    }

    @Test
    fun `UC-08 acknowledging unknown ids changes nothing and skips the write`() {
        val store = MemoryQueueStore()
        val queue = UploadQueue(store)
        queue.add(hr(1_000))
        val saves = store.saves
        queue.acknowledge(listOf("not-a-real-id"))
        assertEquals(saves, store.saves)
        assertEquals(1, queue.size)
    }

    @Test
    fun `UC-08 a full queue drops the oldest readings and counts them`() {
        val queue = UploadQueue(MemoryQueueStore(), capacity = 3)
        (1..5).forEach { queue.add(hr(it * 1_000L)) }
        assertEquals(3, queue.size)
        assertEquals(2, queue.dropped)
        assertEquals(3_000L, queue.nextBatch().first().receivedAtMs)
    }

    @Test
    fun `UC-08 the queue survives an app restart through the file store`() {
        val file = tmp.root.resolve("queue/pending.jsonl")
        UploadQueue(FileQueueStore(file)).apply {
            add(hr(1_000))
            add(Reading.from("AA", ReadingKind.BLOOD_PRESSURE, 2_000, "00780050005d00".hexToBytes()))
        }
        val reopened = UploadQueue(FileQueueStore(file))
        assertEquals(2, reopened.size)
        assertEquals(ReadingKind.BLOOD_PRESSURE, reopened.nextBatch()[1].kind)
        assertFalse(tmp.root.resolve("queue/pending.jsonl.tmp").exists())
    }

    @Test
    fun `UC-08 a damaged line in the file is skipped, not fatal`() {
        val file = tmp.root.resolve("pending.jsonl")
        UploadQueue(FileQueueStore(file)).add(hr(1_000))
        file.appendText("{ this is not json\n")
        assertEquals(1, UploadQueue(FileQueueStore(file)).size)
    }

    @Test
    fun `UC-08 heart rate is kept once per interval per device`() {
        val sampler = HeartRateSampler(intervalMs = 5_000)
        val kept = listOf(0L, 1_000, 4_999, 5_000, 6_000, 10_000).filter { sampler.shouldKeep("A", it) }
        assertEquals(listOf(0L, 5_000, 10_000), kept)
        assertTrue("another device has its own window", sampler.shouldKeep("B", 6_000))
        assertTrue("a clock that jumps backwards does not block readings", sampler.shouldKeep("A", 2_000))
    }

    @Test
    fun `UC-09 the Firestore document has the shape the rules expect`() {
        val doc = hr(1_795_113_924_796).toDocument(platform = "android")
        assertEquals(setOf("schema", "deviceId", "kind", "receivedAtMs", "payloadHex", "platform", "heartRate"), doc.keys)
        @Suppress("UNCHECKED_CAST")
        val heart = doc["heartRate"] as Map<String, Any?>
        assertEquals(72L, heart["bpm"])
        assertEquals("detected", heart["sensorContact"])

        val bp = Reading.from("A", ReadingKind.BLOOD_PRESSURE, 1L, "02790050005d00ea070b130e0509".hexToBytes())
            .toDocument("android")
        @Suppress("UNCHECKED_CAST")
        val pressure = bp["bloodPressure"] as Map<String, Any?>
        assertEquals("2026-11-19T14:05:09", pressure["deviceTime"])
        assertEquals(121.0, pressure["systolic"])
    }
}
