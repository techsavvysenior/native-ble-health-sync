import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation
import Observation
import PulseLogCore

/// Writes readings to users/{uid}/readings/{readingId}. Shape and limits are enforced by firebase/firestore.rules.
@MainActor
final class FirestoreUploader {
    let projectId: String

    private init(projectId: String) {
        self.projectId = projectId
    }

    /// Returns nil when no Firebase project is configured; the app then runs local-only (UC-11).
    static func makeIfConfigured(bundle: Bundle = .main) -> FirestoreUploader? {
        func setting(_ key: String) -> String {
            (bundle.object(forInfoDictionaryKey: key) as? String ?? "").trimmingCharacters(in: .whitespaces)
        }
        let projectId = setting("PulseLogFirebaseProjectID")
        let appId = setting("PulseLogFirebaseAppID")
        let apiKey = setting("PulseLogFirebaseAPIKey")
        let senderId = setting("PulseLogFirebaseSenderID")
        guard !projectId.isEmpty, !appId.isEmpty, !apiKey.isEmpty, !senderId.isEmpty else { return nil }

        if FirebaseApp.app() == nil {
            let options = FirebaseOptions(googleAppID: appId, gcmSenderID: senderId)
            options.projectID = projectId
            options.apiKey = apiKey
            FirebaseApp.configure(options: options)
        }
        return FirestoreUploader(projectId: projectId)
    }

    func upload(_ batch: [Reading]) async throws {
        let uid = try await userId()
        let db = Firestore.firestore()
        let readings = db.collection("users").document(uid).collection("readings")
        let write = db.batch()
        for reading in batch {
            // Same id, same data: a retried batch rewrites identical documents, which the rules allow
            // and which never creates duplicates (docs/adr/0003).
            write.setData(try reading.document(platform: "ios"), forDocument: readings.document(reading.id))
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            write.commit { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }

    private func userId() async throws -> String {
        if let uid = Auth.auth().currentUser?.uid { return uid }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<String, Error>) in
            Auth.auth().signInAnonymously { result, error in
                if let uid = result?.user.uid {
                    continuation.resume(returning: uid)
                } else {
                    continuation.resume(throwing: error ?? URLError(.userAuthenticationRequired))
                }
            }
        }
    }
}

/// Moves readings from the on-phone queue to Firestore. A failed batch stays queued and is retried
/// with backoff, and again when the next reading arrives.
@MainActor
@Observable
final class SyncLoop {
    private(set) var pending = 0
    private(set) var dropped = 0
    private(set) var uploading = false
    private(set) var lastError: String?

    private let queue: UploadQueue
    let uploader: FirestoreUploader?
    @ObservationIgnored private var task: Task<Void, Never>?

    init(queue: UploadQueue, uploader: FirestoreUploader?) {
        self.queue = queue
        self.uploader = uploader
        publish()
    }

    func add(_ reading: Reading) {
        queue.add(reading)
        publish()
        kick()
    }

    func kick() {
        guard let uploader, task == nil else { return }
        task = Task { [weak self] in
            await self?.drain(uploader)
            self?.task = nil
        }
    }

    private func drain(_ uploader: FirestoreUploader) async {
        var failures = 0
        uploading = true
        defer { uploading = false }
        while true {
            let batch = queue.nextBatch(limit: 100)
            if batch.isEmpty { break }
            do {
                try await uploader.upload(batch)
                queue.acknowledge(batch.map(\.id))
                failures = 0
                lastError = nil
            } catch {
                failures += 1
                lastError = error.localizedDescription
                if failures >= 5 { break } // the next reading or app launch starts it again
                try? await Task.sleep(for: .seconds(2 << (failures - 1)))
            }
            publish()
        }
        publish()
    }

    private func publish() {
        pending = queue.count
        dropped = queue.dropped
    }
}
