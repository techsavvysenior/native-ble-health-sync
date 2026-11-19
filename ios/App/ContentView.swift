import CoreBluetooth
import PulseLogCore
import SwiftUI

struct ContentView: View {
    let ble: BLEManager
    let sync: SyncLoop

    var body: some View {
        NavigationStack {
            List {
                if ble.bluetoothState == .unauthorized {
                    Section {
                        Text("Pulse Log needs Bluetooth access. Turn it on in Settings > Pulse Log.")
                    }
                } else if ble.bluetoothState == .poweredOff {
                    Section {
                        Text("Bluetooth is off. Pulse Log reconnects when it's back on.")
                    }
                }

                Section("Connection") {
                    Text(status).font(.headline)
                    if let device = ble.reconnect.deviceId {
                        Text(device).font(.caption).foregroundStyle(.secondary)
                    }
                    if let retry = ble.nextRetryAt {
                        Text("Next attempt \(retry, style: .relative)").font(.caption)
                    }
                    if let error = ble.lastError {
                        Text(error).font(.caption).foregroundStyle(.red)
                    }
                    if ble.reconnect.isActive {
                        Button("Disconnect", role: .destructive) { ble.disconnect() }
                    }
                }

                Section("Readings") {
                    Text(ble.heartRate.map { "\($0.bpm) bpm" } ?? "No heart rate yet").font(.largeTitle.bold())
                    if let rr = ble.heartRate?.rrMillis.last {
                        Text("Last RR interval \(Int(rr.rounded())) ms")
                    }
                    if let bp = ble.bloodPressure {
                        Text("Blood pressure \(Int(bp.systolic))/\(Int(bp.diastolic)) \(bp.unit.rawValue)")
                        if let pulse = bp.pulseRate { Text("Pulse \(Int(pulse))") }
                        if bp.irregularPulse { Text("Device flagged an irregular pulse").foregroundStyle(.red) }
                    }
                }

                Section("Cloud sync") {
                    Text(sync.uploader.map { "Syncing to Firebase project \($0.projectId)" } ?? "Local only. Add a Firebase project to sync.")
                    Text("Waiting to upload: \(sync.pending)" + (sync.uploading ? " (uploading)" : ""))
                    if sync.dropped > 0 { Text("Dropped when the queue was full: \(sync.dropped)") }
                    if let error = sync.lastError { Text("Last upload error: \(error)").foregroundStyle(.red) }
                }

                Section("Devices") {
                    Button(ble.scanning ? "Scanning" : "Find devices") { ble.startScan() }
                        .disabled(ble.scanning || ble.bluetoothState != .poweredOn)
                    ForEach(ble.found) { device in
                        Button {
                            ble.connect(device.id)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(device.name ?? "Unnamed device")
                                Text("\(device.rssi) dBm").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Pulse Log")
        }
    }

    private var status: String {
        let r = ble.reconnect
        switch r.phase {
        case .idle: return "Not connected"
        case .connecting: return r.attempt == 0 ? "Connecting" : "Connecting, attempt \(r.attempt + 1)"
        case .discovering: return "Setting up"
        case .streaming: return "Connected"
        case .waitingToRetry: return "Connection lost. Retry \(r.attempt) scheduled"
        case .bluetoothOff: return "Waiting for Bluetooth"
        case .stopped: return r.stopReason == .unauthorized ? "Stopped: Bluetooth access was removed" : "Stopped after too many failed attempts"
        }
    }
}
