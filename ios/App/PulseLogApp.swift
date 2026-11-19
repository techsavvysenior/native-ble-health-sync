import Foundation
import Observation
import PulseLogCore
import SwiftUI

@main
struct PulseLogApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            ContentView(ble: model.ble, sync: model.sync)
        }
    }
}

/// The app's objects, wired by hand.
@MainActor
@Observable
final class AppModel {
    let ble = BLEManager()
    let sync: SyncLoop
    private let sampler = HeartRateSampler(intervalMs: 5_000)

    init() {
        let folder = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        let queue = UploadQueue(store: FileQueueStore(url: folder.appendingPathComponent("queue/pending.jsonl")))
        sync = SyncLoop(queue: queue, uploader: FirestoreUploader.makeIfConfigured())

        ble.onPayload = { [weak self] deviceId, kind, payload in
            guard let self else { return }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            // Every blood pressure reading is kept. Heart rate is thinned to one value per interval.
            if kind == .bloodPressure || sampler.shouldKeep(deviceId: deviceId, receivedAtMs: now) {
                sync.add(Reading.from(deviceId: deviceId, kind: kind, receivedAtMs: now, payload: payload))
            }
        }
        sync.kick()
    }
}
