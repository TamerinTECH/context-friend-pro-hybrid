import Foundation
import Network
import CoreBluetooth
import Combine
import Darwin

// All transports carry the same already encrypted, recipient-bound envelope.
enum LAN {
    static func privateIPv4(_ text: String) -> Bool {
        let p = text.split(separator: ".").compactMap { UInt8($0) }; guard p.count == 4, text.filter({$0 == "."}).count == 3 else { return false }
        return p[0] == 10 || (p[0] == 172 && (16...31).contains(p[1])) || (p[0] == 192 && p[1] == 168)
    }
    static func addresses() -> [String] {
        var head: UnsafeMutablePointer<ifaddrs>?; guard getifaddrs(&head) == 0 else { return [] }; defer { freeifaddrs(head) }
        var result: [String] = []; var current = head
        while let row = current { defer { current = row.pointee.ifa_next }; guard let addr = row.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST)); if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let text = String(cString: host); if privateIPv4(text) { result.append(text) }
            }
        }; return Array(Set(result)).sorted()
    }
    static func read(_ c: NWConnection, _ count: Int) async throws -> Data {
        try require(count > 0 && count <= Transfers.maximum)
        return try await withCheckedThrowingContinuation { continuation in
            c.receive(minimumIncompleteLength: count, maximumLength: count) { data, _, _, error in
                if let data, data.count == count, error == nil { continuation.resume(returning: data) } else { continuation.resume(throwing: ProError.unavailable) }
            }
        }
    }
    static func write(_ c: NWConnection, _ data: Data) async throws { try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        c.send(content: data, completion: .contentProcessed { e in if let e { continuation.resume(throwing: e) } else { continuation.resume() } })
    } }
    static func ready(_ c: NWConnection) async throws { try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        var finished = false
        c.stateUpdateHandler = { state in guard !finished else { return }; switch state {
        case .ready: finished = true; continuation.resume()
        case .failed(let error): finished = true; continuation.resume(throwing: error)
        case .cancelled: finished = true; continuation.resume(throwing: ProError.cancelled)
        default: break
        } }; c.start(queue: DispatchQueue(label: "lan-connection"))
    } }
    static func send(_ bytes: Data, invitation: Invitation, progress: @escaping (Double) -> Void) async throws -> String {
        guard let host = invitation.host, let port = invitation.port, privateIPv4(host), let endpoint = NWEndpoint.Port(rawValue: UInt16(port)) else { throw ProError.invalid }
        let c = NWConnection(host: NWEndpoint.Host(host), port: endpoint, using: .tcp)
        let expiry = Task { try await Task.sleep(nanoseconds: 600_000_000_000); c.cancel() }; defer { expiry.cancel(); c.cancel() }
        return try await withTaskCancellationHandler(operation: {
            try await ready(c); try await write(c, Data("CFL1".utf8) + u32(bytes.count))
            for n in stride(from: 0, to: bytes.count, by: 16384) { try Task.checkCancellation(); let end = min(n+16384,bytes.count); try await write(c, bytes.subdata(in:n..<end)); progress(Double(end)/Double(bytes.count)) }
            var cursor = Cursor(data: try await read(c,4)); let count = try cursor.int(); try require((1...1024).contains(count)); return String(decoding: try await read(c,count), as: UTF8.self)
        }, onCancel: { c.cancel() })
    }
}
final class LANReceiver {
    private var listener: NWListener?; private var peers: [NWConnection] = []; private var expiry: Task<Void, Never>?
    private let queue = DispatchQueue(label: "lan-listener"); private var received = 0
    func start(host: String, ready: @escaping (UInt16) -> Void, receive: @escaping (Data) async throws -> String, failure: @escaping () -> Void) throws {
        try require(LAN.privateIPv4(host)); let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(host), port: .any)
        let l = try NWListener(using: parameters); listener = l
        l.stateUpdateHandler = { state in if case .ready = state, let port = l.port { DispatchQueue.main.async { ready(port.rawValue) } }; if case .failed = state { failure() } }
        l.newConnectionHandler = { [weak self] c in guard let self, self.received < 8 else { c.cancel(); return }; self.received += 1; self.peers.append(c)
            Task { let timeout = Task { try? await Task.sleep(nanoseconds: 600_000_000_000); if !Task.isCancelled { c.cancel() } }; defer { timeout.cancel(); c.cancel() }
                do { try await LAN.ready(c); var header = Cursor(data: try await LAN.read(c,8)); try require(header.take(4) == Data("CFL1".utf8)); let size = try header.int(); try require((321...Transfers.maximum).contains(size))
                    let data = try await LAN.read(c,size); let receipt = Data(try await receive(data).utf8); try require(receipt.count <= 1024); try await LAN.write(c,u32(receipt.count)+receipt)
                } catch { if !Task.isCancelled { failure() } }
            }
        }
        l.start(queue: queue); expiry = Task { try? await Task.sleep(nanoseconds:600_000_000_000); if !Task.isCancelled { self.stop() } }
    }
    func stop() { expiry?.cancel(); expiry = nil; queue.async { self.listener?.cancel(); self.listener = nil; self.peers.forEach {$0.cancel()}; self.peers = [] } }
    deinit { listener?.cancel(); peers.forEach {$0.cancel()}; expiry?.cancel() }
}

final class Fragments {
    private var hash: String?; private var buffer = Data(); private(set) var offset = 0
    func begin(_ data: Data) throws { try require(data.count == 40); var c = Cursor(data:data); try require(c.take(4) == Data("CFB1".utf8)); let h = try c.take(32).map {String(format:"%02x",$0)}.joined(); let n = try c.int(); try require((321...2*1024*1024).contains(n))
        if let hash { try require(hash == h && buffer.count == n); return }; hash = h; buffer = Data(count:n); offset = 0
    }
    func append(_ data: Data) throws -> Data? { try require(hash != nil && data.count >= 5); var c = Cursor(data:data); let at = try c.int(); let bytes = try c.take(data.count-4); try require(at >= 0 && at+bytes.count <= buffer.count)
        if at < offset { try require(at+bytes.count <= offset && buffer.subdata(in:at..<at+bytes.count) == bytes); return nil }
        try require(at == offset); buffer.replaceSubrange(at..<at+bytes.count,with:bytes); offset += bytes.count
        guard offset == buffer.count else { return nil }; try require(sha256(buffer) == hash); return buffer
    }
    func clear() { buffer.resetBytes(in:0..<buffer.count); buffer = Data(); offset = 0; hash = nil }
}

final class Bluetooth: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate, CBPeripheralManagerDelegate {
    static let service = CBUUID(string:"cf010001-e21b-48ac-b9dc-1920e92fbc01"), control = CBUUID(string:"cf010002-e21b-48ac-b9dc-1920e92fbc01"), data = CBUUID(string:"cf010003-e21b-48ac-b9dc-1920e92fbc01"), receipt = CBUUID(string:"cf010004-e21b-48ac-b9dc-1920e92fbc01")
    @Published var devices: [CBPeripheral] = []; @Published var progress: Double = 0; @Published var failure = false; @Published var active = false
    var receive: ((Data) async throws -> String)?; var completed: ((String) -> Void)?
    private var central: CBCentralManager?; private var server: CBPeripheralManager?; private var selected: CBPeripheral?
    private var characteristics: [CBUUID:CBCharacteristic] = [:]; private let fragments = Fragments(); private var importStarted = false
    private var result = Data(); private var payload = Data(); private var offset = 0; private var writeEnd = 0; private var peer: UUID?
    private var timer: Timer?; private var polling: Timer?; private var advertising = false
    func scan() { stop(); active = true; failure = false; devices = []; central = CBCentralManager(delegate:self,queue:.main); expire() }
    func listen() { stop(); active = true; failure = false; advertising = true; server = CBPeripheralManager(delegate:self,queue:.main,options:[CBPeripheralManagerOptionShowPowerAlertKey:true]); expire() }
    private func expire() { timer = Timer.scheduledTimer(withTimeInterval:600,repeats:false) { [weak self] _ in self?.stop() } }
    func send(_ bytes: Data, to device: CBPeripheral) throws { try require(bytes.count <= 2*1024*1024 && central?.state == .poweredOn); payload = bytes; selected = device; device.delegate = self; central?.stopScan(); central?.connect(device); progress = 0 }
    func stop() { active = false; timer?.invalidate(); polling?.invalidate(); timer = nil; polling = nil; central?.stopScan(); if let selected { central?.cancelPeripheralConnection(selected) }; server?.stopAdvertising(); server?.removeAllServices(); selected = nil; central = nil; server = nil; characteristics = [:]; payload = Data(); result = Data(); fragments.clear(); peer = nil; importStarted = false; advertising = false }
    private func fail() { failure = true; stop() }
    func centralManagerDidUpdateState(_ central: CBCentralManager) { if central.state == .poweredOn { central.scanForPeripherals(withServices:[Self.service]) } else if central.state != .unknown && central.state != .resetting { fail() } }
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String:Any], rssi RSSI:NSNumber) { if !devices.contains(where:{$0.identifier == peripheral.identifier}) && devices.count < 32 { devices.append(peripheral) } }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) { peripheral.discoverServices([Self.service]) }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,error:Error?) { fail() }
    func centralManager(_ central: CBCentralManager,didDisconnectPeripheral peripheral:CBPeripheral,error:Error?) { if !payload.isEmpty { failure = true } }
    func peripheral(_ peripheral: CBPeripheral,didDiscoverServices error:Error?) { guard error == nil, let service = peripheral.services?.first(where:{$0.uuid == Self.service}) else { fail(); return }; peripheral.discoverCharacteristics([Self.control,Self.data,Self.receipt],for:service) }
    func peripheral(_ peripheral:CBPeripheral,didDiscoverCharacteristicsFor service:CBService,error:Error?) {
        guard error == nil else { fail(); return }; for c in service.characteristics ?? [] { characteristics[c.uuid] = c }
        guard let control = characteristics[Self.control], characteristics[Self.data] != nil, characteristics[Self.receipt] != nil, peripheral.maximumWriteValueLength(for:.withResponse) >= 40 else { fail(); return }
        let digest = sha256(payload); var bytes = Data(); for i in stride(from:0,to:64,by:2) { let a = digest.index(digest.startIndex,offsetBy:i); let b = digest.index(a,offsetBy:2); bytes.append(UInt8(digest[a..<b],radix:16)!) }
        peripheral.writeValue(Data("CFB1".utf8)+bytes+u32(payload.count),for:control,type:.withResponse)
    }
    func peripheral(_ peripheral:CBPeripheral,didWriteValueFor characteristic:CBCharacteristic,error:Error?) { guard error == nil else { fail(); return }
        if characteristic.uuid == Self.control { peripheral.readValue(for:characteristic) } else if characteristic.uuid == Self.data { offset = writeEnd; progress = Double(offset)/Double(payload.count); writeNext(peripheral) }
    }
    private func writeNext(_ p:CBPeripheral) {
        if offset == payload.count { polling?.invalidate(); polling = Timer.scheduledTimer(withTimeInterval:0.75,repeats:true) { [weak self] _ in guard let self, let receipt = self.characteristics[Self.receipt] else { return }; p.readValue(for:receipt) }; return }
        writeEnd = min(payload.count,offset+p.maximumWriteValueLength(for:.withResponse)-4)
        p.writeValue(u32(offset)+payload.subdata(in:offset..<writeEnd),for:characteristics[Self.data]!,type:.withResponse)
    }
    func peripheral(_ peripheral:CBPeripheral,didUpdateValueFor characteristic:CBCharacteristic,error:Error?) { guard error == nil, let value = characteristic.value else { fail(); return }
        if characteristic.uuid == Self.control { do { var c = Cursor(data:value); offset = try c.int(); try require(offset >= 0 && offset <= payload.count); writeNext(peripheral) } catch { fail() } }
        else if characteristic.uuid == Self.receipt && !value.isEmpty { guard value.count <= 1024 else { fail(); return }; polling?.invalidate(); payload = Data(); completed?(String(decoding:value,as:UTF8.self)); stop() }
    }
    func peripheralManagerDidUpdateState(_ peripheral:CBPeripheralManager) {
        guard peripheral.state == .poweredOn else { if peripheral.state != .unknown && peripheral.state != .resetting { fail() }; return }
        let service = CBMutableService(type:Self.service,primary:true)
        service.characteristics = [CBMutableCharacteristic(type:Self.control,properties:[.write,.read],value:nil,permissions:[.writeable,.readable]),CBMutableCharacteristic(type:Self.data,properties:[.write],value:nil,permissions:[.writeable]),CBMutableCharacteristic(type:Self.receipt,properties:[.read],value:nil,permissions:[.readable])]; peripheral.add(service)
    }
    func peripheralManager(_ peripheral:CBPeripheralManager,didAdd service:CBService,error:Error?) { if error != nil { fail() } else { peripheral.startAdvertising([CBAdvertisementDataServiceUUIDsKey:[Self.service]]) } }
    func peripheralManagerDidStartAdvertising(_ peripheral:CBPeripheralManager,error:Error?) { if error != nil { fail() } }
    func peripheralManager(_ peripheral:CBPeripheralManager,didReceiveWrite requests:[CBATTRequest]) {
        guard let first = requests.first else { return }
        do { for r in requests { try require(r.offset == 0 && (peer == nil || peer == r.central.identifier)); peer = r.central.identifier; guard let value = r.value else { throw ProError.invalid }
            if r.characteristic.uuid == Self.control { try fragments.begin(value) }
            else if r.characteristic.uuid == Self.data { if let bytes = try fragments.append(value), !importStarted { importStarted = true; Task { do { guard let receive else { throw ProError.unavailable }; result = Data(try await receive(bytes).utf8) } catch { result = Data("rejected".utf8) } } } }
            else { throw ProError.invalid }
        }; peripheral.respond(to:first,withResult:.success) } catch { peripheral.respond(to:first,withResult:.unlikelyError) }
    }
    func peripheralManager(_ peripheral:CBPeripheralManager,didReceiveRead request:CBATTRequest) {
        let data = request.characteristic.uuid == Self.control ? u32(fragments.offset) : result
        guard peer == request.central.identifier, request.offset <= data.count else { peripheral.respond(to:request,withResult:.invalidOffset); return }
        request.value = data.subdata(in:request.offset..<data.count); peripheral.respond(to:request,withResult:.success)
    }
    deinit { timer?.invalidate(); polling?.invalidate(); server?.stopAdvertising(); central?.stopScan() }
}
