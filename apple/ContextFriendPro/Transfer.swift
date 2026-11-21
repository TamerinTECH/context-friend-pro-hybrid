import Foundation
import Clibsodium
import Darwin

func b64(_ data: Data) -> String { data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
func un64(_ text: String) throws -> Data { try require(text.count <= 90_000_000); let padded = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/") + String(repeating: "=", count: (4 - text.count % 4) % 4); guard let data = Data(base64Encoded: padded) else { throw ProError.invalid }; return data }
func uuidData(_ id: String) throws -> Data { guard var uuid = UUID(uuidString: id)?.uuid else { throw ProError.invalid }; return withUnsafeBytes(of: &uuid) { Data($0) } }
func uuidString(_ data: Data) throws -> String { try require(data.count == 16); let b = [UInt8](data); return UUID(uuid: (b[0],b[1],b[2],b[3],b[4],b[5],b[6],b[7],b[8],b[9],b[10],b[11],b[12],b[13],b[14],b[15])).uuidString.lowercased() }
func u32(_ value: Int) -> Data { var n = UInt32(value).bigEndian; return withUnsafeBytes(of: &n) { Data($0) } }
struct Cursor { let data: Data; var offset = 0
    mutating func take(_ n: Int) throws -> Data { try require(n >= 0 && offset + n <= data.count); defer { offset += n }; return data.subdata(in: offset..<offset+n) }
    mutating func int() throws -> Int { try take(4).reduce(0) { ($0 << 8) | Int($1) } }
}
struct Identity: Codable { var boxPublic: String; var boxSecret: String; var signPublic: String; var signSecret: String }
struct Invitation: Codable, Equatable {
    var version = 1; var id = newID(); var boxKey: String; var signKey: String; var ttl = 600; var host: String?; var port: Int?
    func validate() throws { try require(version == 1 && UUID(uuidString: id) != nil && (1...600).contains(ttl)); try require(un64(boxKey).count == 32 && un64(signKey).count == 32); if let port { try require((1024...65535).contains(port)) }; if let host { try require(host.count <= 100) } }
    func encoded() throws -> String { "CFI1:" + b64(try encoder.encode(self)) }
    static func decode(_ text: String) throws -> Invitation { try require(text.hasPrefix("CFI1:") && text.count <= 2048); let i = try decoder.decode(Self.self, from: un64(String(text.dropFirst(5)))); try i.validate(); return i }
    var fingerprint: String { (try? sha256(un64(boxKey) + un64(signKey)).prefix(24).enumerated().map { ($0.offset > 0 && $0.offset % 4 == 0 ? " " : "") + String($0.element) }.joined()) ?? "" }
}
struct PendingInvitation: Codable { var invitation: Invitation; var deadline: Double; var boot: Int64; var bundleHash: String?; var completed = false; var receipt: String? }
struct TrustedPeer: Codable, Identifiable { var signKey: String; var boxKey: String; var label: String; var id: String { signKey } }
struct MeetingPayload: Codable {
    var sessionId: String; var revision: String; var createdAt: Int64; var language: String; var title: String; var approved: Bool; var summary: String
    var transcript: [Segment]?; var speakers: [String: String]?; var audio: [AudioChunk]?; var stages: [String: Stage]
    var structuredSummary: Summary?
}
struct BundlePayload: Codable {
    var version = 1; var bundleId = newID(); var purpose: String; var meeting: MeetingPayload?; var profiles: [VoiceProfile]?
    func validate() throws {
        try require(version == 1 && UUID(uuidString: bundleId) != nil)
        if purpose == "meeting" {
            guard let m = meeting, profiles == nil else { throw ProError.invalid }
            try require(UUID(uuidString: m.sessionId) != nil && UUID(uuidString: m.revision) != nil && m.title.count <= 500 && m.summary.count <= 300000 && ["en","de","he","auto"].contains(m.language))
            try require((m.transcript?.count ?? 0) <= 20000 && (m.speakers?.count ?? 0) <= 1000 && (m.audio?.count ?? 0) <= 7200)
            for s in m.transcript ?? [] { try require(UUID(uuidString: s.id) != nil && s.startMs >= 0 && s.endMs >= s.startMs && s.original.count <= 20000 && (s.correction?.count ?? 0) <= 20000) }
            try require(Set((m.transcript ?? []).map(\.id)).count == (m.transcript?.count ?? 0) && (m.transcript ?? []).allSatisfy {$0.endMs <= 86400000})
            try require(Set((m.audio ?? []).map(\.index)).count == (m.audio?.count ?? 0))
            try m.structuredSummary?.validate(Set((m.transcript ?? []).map(\.id)))
            for c in m.audio ?? [] { try require(c.index >= 0 && (0...86400000).contains(c.startMs) && c.sampleRate == 16000 && (1...80000).contains(c.samples) && Data(base64Encoded: c.pcm)?.count == c.samples * 2) }
        } else { guard purpose == "profiles", meeting == nil, let profiles, !profiles.isEmpty, profiles.count <= 100 else { throw ProError.invalid }; try profiles.forEach { try $0.validate() } }
    }
}
struct OpenBundle { var payload: BundlePayload; var invitation: PendingInvitation; var hash: String; var sender: String; var trusted: Bool }
final class Transfers {
    static let maximum = 64 * 1024 * 1024
    private let vault: Vault; private let models: Models; private let lock = NSRecursiveLock()
    init(vault: Vault, models: Models) { self.vault = vault; self.models = models; sodium_init() }
    private func identity() throws -> Identity {
        if let id = try vault.get("identity", as: Identity.self) { return id }
        var bp = [UInt8](repeating: 0, count: 32), bs = bp, sp = bp, ss = [UInt8](repeating: 0, count: 64)
        try require(crypto_box_keypair(&bp, &bs) == 0 && crypto_sign_keypair(&sp, &ss) == 0)
        let id = Identity(boxPublic: b64(Data(bp)), boxSecret: b64(Data(bs)), signPublic: b64(Data(sp)), signSecret: b64(Data(ss)))
        try vault.put("identity", id); return id
    }
    private func boot() -> Int64 { var value = timeval(); var size = MemoryLayout<timeval>.size; guard sysctlbyname("kern.boottime", &value, &size, nil, 0) == 0 else { return -1 }; return Int64(value.tv_sec) }
    private func pending() throws -> [PendingInvitation] { try vault.get("invitations", as: [PendingInvitation].self) ?? [] }
    func peers() throws -> [TrustedPeer] { try vault.get("trusted-peers", as: [TrustedPeer].self) ?? [] }
    func trust(_ inv: Invitation, label: String) throws { try inv.validate(); try require(!label.isEmpty && label.count <= 100); try vault.put("trusted-peers", peers().filter { $0.signKey != inv.signKey } + [TrustedPeer(signKey: inv.signKey, boxKey: inv.boxKey, label: label)]) }
    func untrust(_ key: String) throws { try vault.put("trusted-peers", peers().filter { $0.signKey != key }) }
    func invite(host: String? = nil, port: Int? = nil) throws -> Invitation {
        try require(boot()>0)
        lock.lock(); defer { lock.unlock() }; let id = try identity(); let inv = Invitation(boxKey: id.boxPublic, signKey: id.signPublic, host: host, port: port)
        let active = try pending().filter { $0.boot == boot() && $0.deadline > ProcessInfo.processInfo.systemUptime }
        try vault.put("invitations", Array((active + [PendingInvitation(invitation: inv, deadline: ProcessInfo.processInfo.systemUptime + 600, boot: boot())]).suffix(20))); return inv
    }
    func revoke(_ id: String) throws { try vault.put("invitations", pending().filter { $0.invitation.id != id }) }
    func meeting(_ s: Meeting, transcript: Bool, audio: Bool, draft: Bool) throws -> BundlePayload {
        try require(s.approved || draft); try require(!s.summaryText.isEmpty)
        var chunks: [AudioChunk]?
        if audio { try require(s.audioRetained && s.durationMs <= 15*60*1000); let key = try vault.key(s.id); chunks = try vault.chunkIDs(s.id).map { try vault.chunk(s.id, index: $0, key: key) } }
        let payload = BundlePayload(purpose: "meeting", meeting: MeetingPayload(sessionId: s.id, revision: s.revision, createdAt: s.createdAt, language: s.language, title: s.title,
            approved: s.approved, summary: s.summaryText, transcript: transcript ? s.transcript : nil, speakers: transcript ? s.speakers : nil, audio: chunks, stages: s.stages.mapValues { Stage(state: $0.state, completedWindows: $0.completedWindows, modelHash: $0.modelHash) },structuredSummary:transcript && s.editedSummary == nil ? s.summary:nil))
        try payload.validate(); return payload
    }
    func seal(_ payload: BundlePayload, to recipient: Invitation) throws -> Data {
        try payload.validate(); try recipient.validate(); let plain = try encoder.encode(payload); try require(plain.count <= Self.maximum - 512)
        let id = try identity(); var key = [UInt8](try randomBytes(32)); defer { key.withUnsafeMutableBytes { sodium_memzero($0.baseAddress, $0.count) } }
        let nonce = [UInt8](try randomBytes(24)); var wrapped = [UInt8](repeating: 0, count: 80)
        try require(crypto_box_seal(&wrapped, key, 32, [UInt8](un64(recipient.boxKey))) == 0)
        var header = Data("CFB1".utf8); header.append(payload.purpose == "meeting" ? 1 : 2)
        header += try uuidData(recipient.id); header += try uuidData(payload.bundleId); header += try un64(recipient.boxKey); header += try un64(recipient.signKey)
        header += try un64(id.signPublic); header += Data(nonce); header += Data(wrapped); header += u32(plain.count + 16)
        var cipher = [UInt8](repeating: 0, count: plain.count + 16); var length: UInt64 = 0
        try require(crypto_aead_xchacha20poly1305_ietf_encrypt(&cipher, &length, [UInt8](plain), UInt64(plain.count), [UInt8](header), UInt64(header.count), nil, nonce, key) == 0)
        let bytes = header + Data(cipher); let message = Data("CFB1-SIGN:".utf8) + bytes; var signature = [UInt8](repeating: 0, count: 64)
        try require(crypto_sign_detached(&signature, nil, [UInt8](message), UInt64(message.count), [UInt8](un64(id.signSecret))) == 0)
        return bytes + Data(signature)
    }
    func open(_ bytes: Data) throws -> OpenBundle {
        try require(boot()>0)
        lock.lock(); defer { lock.unlock() }; try require((321...Self.maximum).contains(bytes.count))
        var r = Cursor(data: bytes); try require(String(data: r.take(4), encoding: .utf8) == "CFB1")
        let purpose = try r.take(1).first!; try require(purpose == 1 || purpose == 2)
        let invID = try uuidString(r.take(16)), bundleID = try uuidString(r.take(16)); let box = try r.take(32), sign = try r.take(32), sender = try r.take(32)
        let nonce = try r.take(24), wrapped = try r.take(80), length = try r.int(); let id = try identity()
        try require(length >= 16 && length == bytes.count - 305 && b64(box) == id.boxPublic && b64(sign) == id.signPublic)
        let hash = sha256(bytes)
        guard var invitation = try pending().first(where: { $0.invitation.id == invID }) else { throw ProError.invalid }
        try require(invitation.boot == boot() && invitation.deadline > ProcessInfo.processInfo.systemUptime)
        try require(invitation.bundleHash == nil || invitation.bundleHash == hash)
        let signed = Data("CFB1-SIGN:".utf8) + bytes.dropLast(64)
        try require(crypto_sign_verify_detached([UInt8](bytes.suffix(64)), [UInt8](signed), UInt64(signed.count), [UInt8](sender)) == 0)
        var key = [UInt8](repeating: 0, count: 32); defer { key.withUnsafeMutableBytes { sodium_memzero($0.baseAddress, $0.count) } }
        try require(crypto_box_seal_open(&key, [UInt8](wrapped), 80, [UInt8](box), [UInt8](un64(id.boxSecret))) == 0)
        var plain = [UInt8](repeating: 0, count: length - 16); defer { plain.withUnsafeMutableBytes { sodium_memzero($0.baseAddress, $0.count) } }; var resultLength: UInt64 = 0
        try require(crypto_aead_xchacha20poly1305_ietf_decrypt(&plain, &resultLength, nil, [UInt8](bytes[241..<241+length]), UInt64(length), [UInt8](bytes.prefix(241)), 241, [UInt8](nonce), key) == 0)
        try checkJSONDepth(Data(plain)); let payload = try decoder.decode(BundlePayload.self, from: Data(plain)); try payload.validate()
        try require(payload.bundleId == bundleID && payload.purpose == (purpose == 1 ? "meeting" : "profiles"))
        invitation.bundleHash = hash; try vault.put("invitations", pending().map { $0.invitation.id == invID ? invitation : $0 })
        return OpenBundle(payload: payload, invitation: invitation, hash: hash, sender: b64(sender), trusted: try peers().contains { $0.signKey == b64(sender) })
    }
    func accept(_ opened: OpenBundle, profileConsent: Bool) throws -> String {
        try require(boot()>0)
        lock.lock(); defer { lock.unlock() }
        guard var current = try pending().first(where: { $0.invitation.id == opened.invitation.invitation.id }) else { throw ProError.invalid }
        try require(current.bundleHash == opened.hash && current.boot == boot() && current.deadline > ProcessInfo.processInfo.systemUptime)
        if current.completed { guard let receipt = current.receipt else { throw ProError.invalid }; return receipt }
        let payload = opened.payload
        if payload.purpose == "profiles" {
            try require(profileConsent); let expected = try models.entry("titanet-small").sha256; let imported = payload.profiles!
            try require(imported.allSatisfy { $0.modelHash == expected }); let existing = try vault.profiles()
            try vault.saveProfiles(existing + imported.filter { p in !existing.contains { $0.id == p.id } }.map { p in var p = p; p.imported = true; p.enabled = false; return p })
        } else {
            let m = payload.meeting!
            for old in try vault.list() where old.sourceBundle == payload.bundleId && old.state == "importing" { try vault.delete(old.id) }
            if try !vault.list().contains(where: { $0.sourceBundle == payload.bundleId }) {
                var s = Meeting(title: m.title); s.createdAt = m.createdAt; s.language = m.language; s.state = "importing"; s.transcript = m.transcript ?? []; s.editedSummary = m.summary
                s.summary = m.structuredSummary; s.revision = m.revision; s.approvedRevision = m.approved ? m.revision : nil; s.speakers = m.speakers ?? [:]; s.audioRetained = !(m.audio ?? []).isEmpty
                s.received = true; s.senderStatus = opened.trusted ? "trusted" : "unverified"; s.sourceBundle = payload.bundleId; s.stages = m.stages
                let key = try vault.create(s)
                do { for c in m.audio ?? [] { try vault.write(c, meeting: s.id, key: key); s.durationMs = max(s.durationMs, c.startMs + Int64(c.samples / 16)) }; s.state = "needs_review"; try vault.save(s, key: key) }
                catch { try? vault.delete(s.id); throw error }
            }
        }
        let text = "CFR1:\(current.invitation.id):\(payload.bundleId):\(opened.hash):imported"; let data = Data(text.utf8); var signature = [UInt8](repeating: 0, count: 64)
        try require(crypto_sign_detached(&signature, nil, [UInt8](data), UInt64(data.count), [UInt8](un64(identity().signSecret))) == 0)
        current.completed = true; current.receipt = text + ":" + b64(Data(signature)); try vault.put("invitations", pending().map { $0.invitation.id == current.invitation.id ? current : $0 }); return current.receipt!
    }
    func verifyReceipt(_ receipt: String, invitation: Invitation, bytes: Data) throws -> Bool {
        try invitation.validate()
        try require(receipt.count <= 1024 && bytes.count >= 37); let fields = receipt.components(separatedBy: ":"); guard fields.count == 6 else { return false }
        let expected = "CFR1:\(invitation.id):\(try uuidString(bytes.subdata(in: 21..<37))):\(sha256(bytes)):imported"
        guard fields.prefix(5).joined(separator: ":") == expected else { return false }; let signature = try un64(fields[5]); try require(signature.count == 64)
        return try crypto_sign_verify_detached([UInt8](signature), [UInt8](expected.utf8), UInt64(expected.utf8.count), [UInt8](un64(invitation.signKey))) == 0
    }
}
func checkJSONDepth(_ data: Data) throws { var depth = 0, quoted = false, escaped = false
    for c in data { if quoted { if escaped { escaped = false } else if c == 92 { escaped = true } else if c == 34 { quoted = false } }
        else { if c == 34 { quoted = true } else if c == 91 || c == 123 { depth += 1; try require(depth <= 16) } else if c == 93 || c == 125 { depth -= 1; try require(depth >= 0) } } }
    try require(depth == 0 && !quoted)
}
enum QrFrames {
    static let size = 600
    static func encode(_ data: Data) throws -> [String] { try require(data.count <= size * 64 && !data.isEmpty); let hash = sha256(data), count = (data.count + size - 1) / size
        return (0..<count).map { "CFQ1:\(hash):\($0):\(count):\(data.count):\(b64(data.subdata(in: $0*size..<min(data.count, ($0+1)*size))))" }
    }
    final class Collector {
        private var hash: String?; private var total = 0; private(set) var count = 0; private var deadline: Double = 0; private var chunks: [Int: Data] = [:]
        var received: Int { chunks.count }
        func add(_ text: String, now: Double = ProcessInfo.processInfo.systemUptime) throws -> Data? {
            try require(text.count <= 1100); let p = text.components(separatedBy: ":")
            guard p.count == 6, p[0] == "CFQ1", let index = Int(p[2]), let n = Int(p[3]), let length = Int(p[4]), p[1].range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw ProError.invalid }
            let bytes = try un64(p[5]); try require((1...64).contains(n) && (1...38400).contains(length) && n == (length+599)/600 && (0..<n).contains(index))
            try require(bytes.count == (index == n-1 ? length-600*(n-1) : 600))
            if hash == nil { hash = p[1]; total = length; count = n; deadline = now + 600 }
            try require(hash == p[1] && count == n && total == length && now < deadline)
            if let old = chunks[index] { try require(old == bytes) }; chunks[index] = bytes
            guard chunks.count == count else { return nil }; let data = (0..<count).reduce(Data()) { $0 + chunks[$1]! }; try require(sha256(data) == hash); return data
        }
    }
}
