import Foundation

/// Where the queue lives between app launches.
public protocol QueueStore {
    func load() -> [Reading]
    func save(_ readings: [Reading]) throws
}

/// Readings waiting to be uploaded, oldest first. Same rules as the Kotlin `UploadQueue`:
/// duplicates by id are ignored, the queue is capped (oldest dropped and counted), and a batch
/// leaves only when the uploader acknowledges it.
public final class UploadQueue {
    private let store: QueueStore
    private let capacity: Int
    private var order: [String] = []
    private var items: [String: Reading] = [:]

    public private(set) var dropped = 0

    public init(store: QueueStore, capacity: Int = 5_000) {
        precondition(capacity > 0)
        self.store = store
        self.capacity = capacity
        for reading in store.load() where items[reading.id] == nil {
            order.append(reading.id)
            items[reading.id] = reading
        }
    }

    public var count: Int { order.count }

    /// Returns false when the reading was already queued.
    @discardableResult
    public func add(_ reading: Reading) -> Bool {
        guard items[reading.id] == nil else { return false }
        order.append(reading.id)
        items[reading.id] = reading
        while order.count > capacity {
            items[order.removeFirst()] = nil
            dropped += 1
        }
        persist()
        return true
    }

    public func nextBatch(limit: Int = 100) -> [Reading] {
        order.prefix(limit).compactMap { items[$0] }
    }

    public func acknowledge(_ ids: [String]) {
        let gone = Set(ids).filter { items[$0] != nil }
        guard !gone.isEmpty else { return }
        gone.forEach { items[$0] = nil }
        order.removeAll { gone.contains($0) }
        persist()
    }

    private func persist() {
        // A failed write keeps the in-memory queue; the next change tries again.
        try? store.save(order.compactMap { items[$0] })
    }
}

/// One JSON object per line, written atomically so a crash never leaves half a file.
public struct FileQueueStore: QueueStore {
    public let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func load() -> [Reading] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        let decoder = JSONDecoder()
        return text.split(separator: "\n").compactMap { line in
            try? decoder.decode(Reading.self, from: Data(line.utf8)) // a damaged line is skipped, not fatal
        }
    }

    public func save(_ readings: [Reading]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        var text = ""
        for reading in readings {
            text += String(decoding: try encoder.encode(reading), as: UTF8.self) + "\n"
        }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: url, options: [.atomic])
    }
}

public final class MemoryQueueStore: QueueStore {
    public private(set) var saved: [Reading]
    public private(set) var saves = 0

    public init(_ initial: [Reading] = []) {
        saved = initial
    }

    public func load() -> [Reading] { saved }

    public func save(_ readings: [Reading]) throws {
        saved = readings
        saves += 1
    }
}
