import CoreBluetooth
import Foundation
import Observation
import PulseLogCore

struct FoundDevice: Identifiable, Equatable {
    let id: UUID
    let name: String?
    let rssi: Int
}

/// Drives the shared reconnect state machine with CoreBluetooth.
///
/// The central manager delivers callbacks on the main queue, so everything here runs on the main actor.
/// iOS differs from Android in one useful way: `connect` has no timeout. A pending connection waits until
/// the device is back in range, even with the app suspended, which is why the state machine's
/// "connecting" phase can last for hours on iOS (docs/adr/0002).
@MainActor
@Observable
final class BLEManager: NSObject {
    private(set) var reconnect = ReconnectState()
    private(set) var nextRetryAt: Date?
    private(set) var bluetoothState: CBManagerState = .unknown
    private(set) var scanning = false
    private(set) var found: [FoundDevice] = []
    private(set) var heartRate: HeartRateMeasurement?
    private(set) var bloodPressure: BloodPressureMeasurement?
    private(set) var lastError: String?

    /// Called with every valid payload. The app model decides what to keep and upload.
    var onPayload: ((_ deviceId: String, _ kind: ReadingKind, _ payload: [UInt8]) -> Void)?

    private let config = ReconnectConfig()
    @ObservationIgnored private var central: CBCentralManager!
    @ObservationIgnored private var peripheral: CBPeripheral?
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var scanStopTask: Task<Void, Never>?
    @ObservationIgnored private var pendingSubscriptions: Set<CBUUID> = []
    @ObservationIgnored private var restoredPeripherals: [CBPeripheral] = []
    @ObservationIgnored private var restoreTarget: CBPeripheral?

    private let services = [CBUUID(string: GattUUID.heartRateService), CBUUID(string: GattUUID.bloodPressureService)]
    private let characteristics = [CBUUID(string: GattUUID.heartRateMeasurement), CBUUID(string: GattUUID.bloodPressureMeasurement)]

    override init() {
        super.init()
        // The restore identifier lets iOS relaunch the app in the background when a reading arrives (UC-12).
        central = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionRestoreIdentifierKey: "pulselog.central",
        ])
    }

    // MARK: Commands

    func connect(_ id: UUID) {
        stopScan()
        dispatch(.connectRequested(deviceId: id.uuidString))
    }

    func disconnect() {
        dispatch(.disconnectRequested)
    }

    func startScan(seconds: Double = 15) {
        guard central.state == .poweredOn, !scanning else { return }
        found = []
        scanning = true
        lastError = nil
        central.scanForPeripherals(withServices: services)
        scanStopTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            self?.stopScan()
        }
    }

    func stopScan() {
        scanStopTask?.cancel()
        guard scanning else { return }
        central.stopScan()
        scanning = false
    }

    // MARK: State machine

    private func dispatch(_ event: ReconnectEvent) {
        let result = Reconnect.reduce(reconnect, event, config: config) { Double.random(in: 0..<1) }
        reconnect = result.state
        result.effects.forEach(run)
    }

    private func run(_ effect: ReconnectEffect) {
        switch effect {
        case .connect(let deviceId):
            guard let uuid = UUID(uuidString: deviceId),
                  let target = restoredPeripherals.first(where: { $0.identifier == uuid })
                    ?? central.retrievePeripherals(withIdentifiers: [uuid]).first
            else {
                lastError = "The device is no longer known to this iPhone. Scan again."
                dispatch(.disconnectRequested)
                return
            }
            peripheral = target
            target.delegate = self
            central.connect(target)
        case .discoverServices:
            peripheral?.discoverServices(services)
        case .scheduleRetry(let delayMs):
            retryTask?.cancel()
            nextRetryAt = Date().addingTimeInterval(Double(delayMs) / 1000)
            retryTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(delayMs))
                guard !Task.isCancelled else { return }
                self?.nextRetryAt = nil
                self?.dispatch(.retryTimerFired)
            }
        case .cancelRetry:
            retryTask?.cancel()
            retryTask = nil
            nextRetryAt = nil
        case .disconnect:
            if let peripheral { central.cancelPeripheralConnection(peripheral) }
            peripheral = nil
        }
    }

    private func cause(for error: Error?) -> DisconnectCause {
        guard let code = (error as? CBError)?.code else { return .linkLoss }
        switch code {
        case .connectionTimeout, .peripheralDisconnected: return .linkLoss
        case .connectionFailed: return .timeout
        default: return .gattError
        }
    }

    private func handle(_ value: Data, from characteristic: CBUUID) {
        guard let deviceId = peripheral?.identifier.uuidString else { return }
        let bytes = [UInt8](value)
        do {
            switch characteristic {
            case CBUUID(string: GattUUID.heartRateMeasurement):
                heartRate = try GattParsers.heartRate(bytes)
                onPayload?(deviceId, .heartRate, bytes)
            case CBUUID(string: GattUUID.bloodPressureMeasurement):
                bloodPressure = try GattParsers.bloodPressure(bytes)
                onPayload?(deviceId, .bloodPressure, bytes)
            default:
                break
            }
        } catch {
            // A malformed packet is dropped and shown; it never reaches the upload queue.
            lastError = "Ignored a malformed reading (\(error))"
        }
    }
}

// CoreBluetooth calls these on the main queue (queue: nil above), so main-actor isolation holds.
extension BLEManager: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        bluetoothState = central.state
        switch central.state {
        case .poweredOn:
            dispatch(.bluetoothOn)
            // iOS relaunched us for a device we were connected to: pick it up again.
            if let restored = restoreTarget, reconnect.phase == .idle {
                restoreTarget = nil
                dispatch(.connectRequested(deviceId: restored.identifier.uuidString))
            }
        case .poweredOff, .resetting:
            stopScan()
            dispatch(.bluetoothOff)
        case .unauthorized:
            stopScan()
            if reconnect.isActive { dispatch(.permissionDenied) }
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        // Called before centralManagerDidUpdateState. Commands must wait for .poweredOn, so just remember the device.
        restoredPeripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        restoreTarget = restoredPeripherals.first
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let device = FoundDevice(
            id: peripheral.identifier,
            name: peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            rssi: RSSI.intValue
        )
        found = (found.filter { $0.id != device.id } + [device]).sorted { $0.rssi > $1.rssi }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        dispatch(.connected)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        dispatch(.disconnected(.timeout))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral.identifier == self.peripheral?.identifier else { return }
        dispatch(.disconnected(cause(for: error)))
    }
}

extension BLEManager: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services, !services.isEmpty else {
            central.cancelPeripheralConnection(peripheral) // reported through didDisconnectPeripheral
            return
        }
        pendingSubscriptions = []
        services.forEach { peripheral.discoverCharacteristics(characteristics, for: $0) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        for characteristic in service.characteristics ?? [] where characteristics.contains(characteristic.uuid) {
            pendingSubscriptions.insert(characteristic.uuid)
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        pendingSubscriptions.remove(characteristic.uuid)
        if pendingSubscriptions.isEmpty { dispatch(.ready) }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        handle(value, from: characteristic.uuid)
    }
}
