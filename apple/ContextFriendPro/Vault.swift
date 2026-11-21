import Foundation
import CryptoKit
import Security
import Darwin

enum VaultError: Error {
    case keychain(String, OSStatus)
    case filesystem(String, Int32)
}

final class Vault {
    let root: URL
    private let lock = NSRecursiveLock()
    private let keyService = "tech.tamerin.contextfriendpro.vault.v1"
    init(root: URL? = nil) throws {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("vault", isDirectory: true)
        try FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
        try excludeBackup(self.root)
    }
    func synchronized<T>(_ body: () throws -> T) rethrows -> T { lock.lock(); defer { lock.unlock() }; return try body() }
    private func master() throws -> SymmetricKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: keyService, kSecAttrAccount as String: "wrapping", kSecReturnData as String: true]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data { return SymmetricKey(data: data) }
        guard status == errSecItemNotFound else { throw VaultError.keychain("read wrapping key", status) }
        let data = try randomBytes(32)
        let add: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: keyService, kSecAttrAccount as String: "wrapping",
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAttrSynchronizable as String: false, kSecValueData as String: data]
        let added = SecItemAdd(add as CFDictionary, nil)
        guard added == errSecSuccess else { throw VaultError.keychain("store wrapping key", added) }
        return SymmetricKey(data: data)
    }
    private func seal(_ data: Data, key: SymmetricKey, aad: String) throws -> Data { Data([1]) + (try AES.GCM.seal(data, using: key, authenticating: Data(aad.utf8)).combined!) }
    private func open(_ data: Data, key: SymmetricKey, aad: String) throws -> Data {
        try require(data.count >= 29 && data.first == 1)
        return try AES.GCM.open(AES.GCM.SealedBox(combined: data.dropFirst()), using: key, authenticating: Data(aad.utf8))
    }
    private func directory(_ id: String) throws -> URL { try require(UUID(uuidString: id)?.uuidString.lowercased() == id); return root.appendingPathComponent(id, isDirectory: true) }
    private func atomic(_ data: Data, to file: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temp = file.appendingPathExtension("tmp")
        // These files contain AEAD ciphertext only. Keychain unwrap requires unlock.
        try data.write(to: temp, options: .completeFileProtectionUntilFirstUserAuthentication)
        let fd = Darwin.open(temp.path, O_RDONLY); guard fd >= 0 else { throw VaultError.filesystem("open ciphertext", errno) }; defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else { throw VaultError.filesystem("sync ciphertext", errno) }
        guard rename(temp.path, file.path) == 0 else { throw VaultError.filesystem("commit ciphertext", errno) }
        let dfd = Darwin.open(file.deletingLastPathComponent().path, O_RDONLY)
        if dfd >= 0 { defer { Darwin.close(dfd) }; guard fsync(dfd) == 0 else { throw VaultError.filesystem("sync ciphertext directory", errno) } }
        try excludeBackup(file)
    }
    func create(_ meeting: Meeting) throws -> Data { try synchronized {
        let dir = try directory(meeting.id); try require(!FileManager.default.fileExists(atPath: dir.path))
        let key = try randomBytes(32); try atomic(seal(key, key: master(), aad: meeting.id), to: dir.appendingPathComponent("key"))
        try save(meeting, key: key); return key
    } }
    func key(_ id: String) throws -> Data { try synchronized { try open(Data(contentsOf: directory(id).appendingPathComponent("key")), key: master(), aad: id) } }
    func save(_ meeting: Meeting, key: Data? = nil) throws { try synchronized {
        let raw = try key ?? self.key(meeting.id)
        try atomic(seal(encoder.encode(meeting), key: SymmetricKey(data: raw), aad: meeting.id + "/session"), to: directory(meeting.id).appendingPathComponent("session"))
    } }
    func load(_ id: String) throws -> Meeting { try synchronized {
        try decoder.decode(Meeting.self, from: open(Data(contentsOf: directory(id).appendingPathComponent("session")), key: SymmetricKey(data: key(id)), aad: id + "/session"))
    } }
    @discardableResult func update(_ id: String, _ body: (inout Meeting) throws -> Void) throws -> Meeting { try synchronized { var s = try load(id); try body(&s); try save(s); return s } }
    func list() throws -> [Meeting] { try synchronized {
        try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).filter { UUID(uuidString: $0.lastPathComponent) != nil }.map { try load($0.lastPathComponent) }.sorted { $0.createdAt > $1.createdAt }
    } }
    func write(_ chunk: AudioChunk, meeting: String, key: Data) throws { try synchronized {
        try require(chunk.index >= 0 && (1...80000).contains(chunk.samples))
        try atomic(seal(encoder.encode(chunk), key: SymmetricKey(data: key), aad: "\(meeting)/audio/\(chunk.index)"), to: directory(meeting).appendingPathComponent("chunks/\(chunk.index).audio"))
    } }
    func chunkIDs(_ id: String) throws -> [Int] {
        let dir = try directory(id).appendingPathComponent("chunks")
        guard FileManager.default.fileExists(atPath: dir.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil).filter { $0.pathExtension == "audio" }.compactMap { Int($0.deletingPathExtension().lastPathComponent) }.sorted()
    }
    func chunk(_ id: String, index: Int, key: Data) throws -> AudioChunk {
        try require(index >= 0)
        return try decoder.decode(AudioChunk.self, from: open(Data(contentsOf: directory(id).appendingPathComponent("chunks/\(index).audio")), key: SymmetricKey(data: key), aad: "\(id)/audio/\(index)"))
    }
    func deleteAudio(_ id: String) throws { try synchronized {
        let dir = try directory(id).appendingPathComponent("chunks"); if FileManager.default.fileExists(atPath: dir.path) { try FileManager.default.removeItem(at: dir) }
        try update(id) { $0.audioRetained = false }
    } }
    func delete(_ id: String) throws { try synchronized { let dir = try directory(id); try FileManager.default.removeItem(at: dir.appendingPathComponent("key")); try FileManager.default.removeItem(at: dir) } }
    func put<T: Encodable>(_ name: String, _ value: T) throws { try synchronized {
        try require(name.range(of: "^[a-z-]+$", options: .regularExpression) != nil)
        try atomic(seal(encoder.encode(value), key: master(), aad: name), to: root.appendingPathComponent(name + ".enc"))
    } }
    func get<T: Decodable>(_ name: String, as type: T.Type) throws -> T? { try synchronized {
        try require(name.range(of: "^[a-z-]+$", options: .regularExpression) != nil)
        let file = root.appendingPathComponent(name + ".enc"); guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try decoder.decode(type, from: open(Data(contentsOf: file), key: master(), aad: name))
    } }
    func preferences() throws -> Preferences { try get("preferences", as: Preferences.self) ?? Preferences() }
    func profiles() throws -> [VoiceProfile] { try get("profiles", as: [VoiceProfile].self) ?? [] }
    func saveProfiles(_ profiles: [VoiceProfile]) throws { try profiles.forEach { try $0.validate() }; try put("profiles", profiles) }
    func recover() throws {
        for var s in try list() {
            if s.state == "importing" { try delete(s.id); continue }
            if ["recording", "paused", "transcribing", "diarizing", "summarizing", "queued"].contains(s.state) {
                let raw = try key(s.id)
                for n in try chunkIDs(s.id) { let c = try chunk(s.id, index: n, key: raw); s.durationMs = max(s.durationMs, c.startMs + Int64(c.samples / 16)) }
                if ["recording", "paused"].contains(s.state) { s.gaps.append(Gap(startMs: s.durationMs, reason: "process_interrupted_uncommitted_tail_unknown")) }
                s.state = "interrupted"; try save(s)
            }
        }
    }
}
func randomBytes(_ count: Int) throws -> Data { var data = Data(count: count); let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }; guard status == errSecSuccess else { throw ProError.unavailable }; return data }
func excludeBackup(_ url: URL) throws { var u = url; var values = URLResourceValues(); values.isExcludedFromBackup = true; try u.setResourceValues(values) }
func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
