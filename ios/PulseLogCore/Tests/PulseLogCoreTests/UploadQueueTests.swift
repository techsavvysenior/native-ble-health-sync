import Foundation
import Testing
@testable import PulseLogCore

struct UploadQueueTests {
    private func hr(_ at: Int64, bpm: UInt8 = 72) -> Reading {
        Reading.from(deviceId: "AA:BB:CC:DD:EE:01", kind: .heartRate, receivedAtMs: at, payload: [0x06, bpm])
    }

    @Test("UC-08 the same reading queued twice is stored once")
    func duplicates() {
        let queue = UploadQueue(store: MemoryQueueStore())
        #expect(queue.add(hr(1_000)))
        #expect(!queue.add(hr(1_000)))
        #expect(queue.count == 1)
    }

    @Test("UC-08 batches come out oldest first and leave only when acknowledged")
    func batches() {
        let queue = UploadQueue(store: MemoryQueueStore())
        for i in 1...5 { queue.add(hr(Int64(i) * 1_000)) }
        let batch = queue.nextBatch(limit: 2)
        #expect(batch.map(\.receivedAtMs) == [1_000, 2_000])
        #expect(queue.count == 5)
        queue.acknowledge(batch.map(\.id))
        #expect(queue.nextBatch().map(\.receivedAtMs) == [3_000, 4_000, 5_000])
    }

    @Test("UC-08 acknowledging unknown ids changes nothing and skips the write")
    func unknownAcknowledge() {
        let store = MemoryQueueStore()
        let queue = UploadQueue(store: store)
        queue.add(hr(1_000))
        let saves = store.saves
        queue.acknowledge(["not-a-real-id"])
        #expect(store.saves == saves)
        #expect(queue.count == 1)
    }

    @Test("UC-08 a full queue drops the oldest readings and counts them")
    func capacity() {
        let queue = UploadQueue(store: MemoryQueueStore(), capacity: 3)
        for i in 1...5 { queue.add(hr(Int64(i) * 1_000)) }
        #expect(queue.count == 3)
        #expect(queue.dropped == 2)
        #expect(queue.nextBatch().first?.receivedAtMs == 3_000)
    }

    @Test("UC-08 the queue survives an app restart through the file store")
    func fileStore() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("queue/pending.jsonl")
        let first = UploadQueue(store: FileQueueStore(url: url))
        first.add(hr(1_000))
        first.add(Reading.from(deviceId: "AA", kind: .bloodPressure, receivedAtMs: 2_000, payload: [UInt8](hex: "00780050005d00")!))

        let reopened = UploadQueue(store: FileQueueStore(url: url))
        #expect(reopened.count == 2)
        #expect(reopened.nextBatch()[1].kind == .bloodPressure)
    }

    @Test("UC-08 a damaged line in the file is skipped, not fatal")
    func damagedLine() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("pending.jsonl")
        UploadQueue(store: FileQueueStore(url: url)).add(hr(1_000))
        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("{ this is not json\n".utf8))
        try handle.close()
        #expect(UploadQueue(store: FileQueueStore(url: url)).count == 1)
    }

    @Test("UC-08 heart rate is kept once per interval per device")
    func sampler() {
        let sampler = HeartRateSampler(intervalMs: 5_000)
        let kept = [Int64(0), 1_000, 4_999, 5_000, 6_000, 10_000].filter { sampler.shouldKeep(deviceId: "A", receivedAtMs: $0) }
        #expect(kept == [0, 5_000, 10_000])
        #expect(sampler.shouldKeep(deviceId: "B", receivedAtMs: 6_000), "another device has its own window")
        #expect(sampler.shouldKeep(deviceId: "A", receivedAtMs: 2_000), "a clock that jumps backwards does not block readings")
    }

    @Test("UC-09 the Firestore document has the shape the rules expect")
    func documentShape() throws {
        let doc = try hr(1_795_113_924_796).document(platform: "ios")
        #expect(Set(doc.keys) == ["schema", "deviceId", "kind", "receivedAtMs", "payloadHex", "platform", "heartRate"])
        let heart = try #require(doc["heartRate"] as? [String: Any])
        #expect(heart["bpm"] as? Int == 72)
        #expect(heart["sensorContact"] as? String == "detected")
        #expect(heart["energyExpendedKj"] is NSNull)

        let bp = try Reading.from(
            deviceId: "A", kind: .bloodPressure, receivedAtMs: 1,
            payload: [UInt8](hex: "02790050005d00ea070b130e0509")!
        ).document(platform: "ios")
        let pressure = try #require(bp["bloodPressure"] as? [String: Any])
        #expect(pressure["deviceTime"] as? String == "2026-11-19T14:05:09")
        #expect(pressure["systolic"] as? Double == 121)
    }
}
