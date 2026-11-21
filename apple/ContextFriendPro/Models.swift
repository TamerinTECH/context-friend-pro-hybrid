import Foundation
import CryptoKit

struct ModelEntry: Codable, Identifiable {
    var id: String; var task: String; var file: String; var revision: String; var url: String; var bytes: Int64; var sha256: String
    var license: String; var runtime: String; var quantization: String; var languages: [String]; var qualification: String; var repo: String?; var archive: Bool?
}
struct ModelManifest: Codable { var version: Int; var models: [ModelEntry] }
final class Models {
    let root: URL
    let manifest: ModelManifest
    init() throws {
        root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("models")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true); try excludeBackup(root)
        guard let url = Bundle.main.url(forResource: "MODEL_MANIFEST", withExtension: "json") else { throw ProError.unavailable }
        manifest = try decoder.decode(ModelManifest.self, from: Data(contentsOf: url))
    }
    func entry(_ id: String) throws -> ModelEntry { guard let m = manifest.models.first(where: { $0.id == id }) else { throw ProError.invalid }; return m }
    func file(_ id: String) throws -> URL { root.appendingPathComponent(try entry(id).file) }
    func installed(_ id: String) -> Bool { guard let m = try? entry(id), let file = try? file(id), let attrs = try? FileManager.default.attributesOfItem(atPath: file.path), let size = attrs[.size] as? NSNumber else { return false }; return size.int64Value == m.bytes && (try? String(contentsOf: root.appendingPathComponent(id + ".verified"), encoding: .utf8)) == m.sha256 }
    func verified(_ id: String) throws -> URL { let f = try file(id); try require(installed(id) && fileHash(f) == entry(id).sha256); return f }
    func remove(_ id: String) throws { for url in [try file(id), root.appendingPathComponent(id + ".verified"), try file(id).appendingPathExtension("partial")] { if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) } } }
    func install(_ id: String, from source: URL, progress: @escaping (Double) -> Void, cancelled: () -> Bool = { false }) throws {
        let access = source.startAccessingSecurityScopedResource(); defer { if access { source.stopAccessingSecurityScopedResource() } }
        let m = try entry(id); let temp = try file(id).appendingPathExtension("partial")
        FileManager.default.createFile(atPath: temp.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        try excludeBackup(temp)
        let input = try FileHandle(forReadingFrom: source), output = try FileHandle(forWritingTo: temp)
        defer { try? input.close(); try? output.close() }
        var hash = SHA256(); var total: Int64 = 0
        while let part = try input.read(upToCount: 1024 * 1024), !part.isEmpty {
            if cancelled() { throw ProError.cancelled }; total += Int64(part.count); try require(total <= m.bytes)
            hash.update(data: part); try output.write(contentsOf: part); progress(Double(total) / Double(m.bytes))
        }
        try output.synchronize(); try require(total == m.bytes && hash.finalize().map { String(format: "%02x", $0) }.joined() == m.sha256)
        guard rename(temp.path, try file(id).path) == 0 else { throw ProError.unavailable }
        try m.sha256.write(to: root.appendingPathComponent(id + ".verified"), atomically: true, encoding: .utf8)
    }
    func download(_ id: String, progress: @escaping (Double) -> Void) async throws {
        let m = try entry(id); try require(m.archive != true)
        let download = ModelDownload(resumeFile:root.appendingPathComponent(id+".resume"),report:{progress($0*0.95)})
        let temp = try await download.start(URL(string:m.url)!)
        defer { try? FileManager.default.removeItem(at: temp) }
        try install(id, from: temp, progress: {progress(0.95+$0*0.05)}, cancelled: { Task.isCancelled })
    }
}
func fileHash(_ file: URL) throws -> String { let input = try FileHandle(forReadingFrom: file); defer { try? input.close() }; var hash = SHA256(); while let data = try input.read(upToCount: 1024 * 1024), !data.isEmpty { hash.update(data: data) }; return hash.finalize().map { String(format: "%02x", $0) }.joined() }
