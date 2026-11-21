import Foundation

func newID() -> String { UUID().uuidString.lowercased() }
func milliseconds() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
enum ProError: Error { case invalid, unavailable, cancelled, integrity, incompatible, expired, resourcePressure }
func require(_ condition: @autoclosure () throws -> Bool) throws { if !(try condition()) { throw ProError.invalid } }
let encoder: JSONEncoder = { let e = JSONEncoder(); e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]; return e }()
let decoder = JSONDecoder()

struct Segment: Codable, Identifiable {
    var id = newID(); var startMs: Int64; var endMs: Int64; var original: String
    var correction: String?; var speaker: String?; var uncertain = false; var language = "auto"
    var text: String { correction ?? original }
}
struct Statement: Codable { var text: String; var sources: [String] }
struct ActionItem: Codable { var text: String; var owner: String?; var due: String?; var sources: [String] }
struct Summary: Codable {
    var title: String; var paragraphs: [Statement]; var decisions: [Statement]; var actions: [ActionItem]; var uncertainties: [Statement]
    func validate(_ ids: Set<String>) throws {
        try require(title.count <= 500 && paragraphs.count <= 100 && decisions.count <= 100 && actions.count <= 100 && uncertainties.count <= 100)
        for s in paragraphs + decisions + uncertainties { try require(s.text.count <= 10000 && !s.sources.isEmpty && s.sources.allSatisfy(ids.contains)) }
        for s in actions { try require(s.text.count <= 10000 && !s.sources.isEmpty && s.sources.allSatisfy(ids.contains)) }
    }
    var plain: String { ([title] + paragraphs.map(\.text) + decisions.map(\.text) + actions.map { [$0.text, $0.owner, $0.due].compactMap { $0 }.joined(separator: " · ") } + uncertainties.map(\.text)).joined(separator: "\n\n") }
}
struct Gap: Codable { var startMs: Int64; var endMs: Int64?; var reason: String }
struct Stage: Codable { var state = "pending"; var completedWindows = 0; var modelHash: String?; var error: String? }
struct Revision: Codable { var id: String; var transcript: [Segment]; var summary: Summary?; var editedSummary: String?; var approved: Bool }
struct Meeting: Codable, Identifiable {
    var id = newID(); var title: String; var createdAt = milliseconds(); var timezone = TimeZone.current.identifier; var language = "en"
    var state = "recorded"; var durationMs: Int64 = 0; var gaps: [Gap] = []; var transcript: [Segment] = []; var summary: Summary?
    var editedSummary: String?; var revision = newID(); var approvedRevision: String?; var history: [Revision] = []; var stages: [String: Stage] = [:]
    var speakers: [String: String] = [:]; var suggestedProfiles: [String: String] = [:]; var speakerVectors: [String: [Float]] = [:]
    var audioRetained = true; var received = false; var senderStatus: String?; var sourceBundle: String?
    var approved: Bool { approvedRevision == revision }
    var summaryText: String { editedSummary ?? summary?.plain ?? "" }
    mutating func edit() {
        history.append(Revision(id: revision, transcript: transcript, summary: summary, editedSummary: editedSummary, approved: approved))
        history = Array(history.suffix(20)); revision = newID(); approvedRevision = nil; state = "needs_review"
    }
}
struct AudioChunk: Codable { var index: Int; var startMs: Int64; var sampleRate = 16000; var pcm: String; var samples: Int }
struct VoiceProfile: Codable, Identifiable {
    var id = newID(); var name: String; var embedding: [Float]; var modelHash: String
    var preprocessing = "mono16k-f32-sherpa-v1"; var consentVersion = 1; var createdAt = milliseconds(); var imported = false; var enabled = true
    func validate() throws {
        try require(UUID(uuidString: id) != nil && !name.isEmpty && name.count <= 100 && embedding.count == 192)
        try require(embedding.allSatisfy(\.isFinite) && embedding.contains { $0 != 0 } && modelHash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil)
        try require(preprocessing == "mono16k-f32-sherpa-v1" && consentVersion == 1)
    }
}
struct Preferences: Codable {
    var language = "en"; var matching = false; var appLock = false; var deleteAudioAfterApproval = false; var asrModel = "whisper-tiny"; var summaryModel = "gemma4-e2b"
}
func cosine(_ a: [Float], _ b: [Float]) -> Float {
    guard a.count == b.count && !a.isEmpty else { return -1 }
    let aa = a.reduce(Float(0)) { $0 + $1 * $1 }; let bb = b.reduce(Float(0)) { $0 + $1 * $1 }
    guard aa > 0 && bb > 0 else { return -1 }
    return zip(a, b).reduce(Float(0)) { $0 + $1.0 * $1.1 } / sqrt(aa * bb)
}
