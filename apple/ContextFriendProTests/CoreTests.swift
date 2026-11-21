import XCTest
import CFNative
@testable import ContextFriendPro

final class CoreTests:XCTestCase {
    private func fixture(_ name:String) throws -> URL {
        try XCTUnwrap(Bundle(for:CoreTests.self).url(forResource:name,withExtension:nil,subdirectory:"Fixtures"))
    }
    private func speech() throws -> [Float] {
        let bytes = try Data(contentsOf:fixture("jfk.pcm"))
        return stride(from:0,to:bytes.count,by:2).map { i in
            Float(Int16(bitPattern:UInt16(bytes[i]) | UInt16(bytes[i+1]) << 8))/32768
        }
    }
    private func nativeJSON(_ pointer:UnsafeMutablePointer<CChar>?) throws -> Data {
        let pointer = try XCTUnwrap(pointer); defer {cf_free(pointer)}
        return Data(String(cString:pointer).utf8)
    }
    func testRealWhisperAndSilence() throws {
        let model = try fixture("ggml-tiny.bin").path; let pcm = try speech()
        cf_reset_cancel()
        let data = try nativeJSON(pcm.withUnsafeBufferPointer { cf_transcribe(model,$0.baseAddress,Int32($0.count),"en") })
        let result = try JSONDecoder().decode(Recognition.self,from:data)
        let text = result.segments.map(\.text).joined(separator:" ").lowercased()
        XCTAssertTrue(text.contains("country"),text); XCTAssertTrue(text.contains("ask"),text); XCTAssertTrue(text.contains("you"),text)
        let zero = [Float](repeating:0,count:16000)
        let silence = try nativeJSON(zero.withUnsafeBufferPointer { cf_transcribe(model,$0.baseAddress,Int32($0.count),"en") })
        XCTAssertTrue(try JSONDecoder().decode(Recognition.self,from:silence).segments.isEmpty)
    }
    func testRealSpeakerModels() throws {
        let segmentation = try fixture("segmentation.int8.onnx").path
        let embedding = try fixture("nemo_en_titanet_small.onnx").path
        let pcm = try speech(); cf_reset_cancel()
        let data = try nativeJSON(pcm.withUnsafeBufferPointer { cf_speakers(segmentation,embedding,$0.baseAddress,Int32($0.count)) })
        let result = try JSONDecoder().decode(SpeakerResult.self,from:data)
        XCTAssertFalse(result.turns.isEmpty); XCTAssertFalse(result.vectors.isEmpty)
        XCTAssertTrue(result.turns.allSatisfy {$0.startMs>=0 && $0.endMs>$0.startMs && $0.endMs<=Int64(pcm.count/16)+1000})
        for vector in result.vectors.values {
            XCTAssertEqual(vector.count,192); XCTAssertTrue(vector.allSatisfy(\.isFinite)); XCTAssertTrue(vector.contains {$0 != 0})
        }
    }
    func testIndependentPythonWireVector() throws {
        let resource = try XCTUnwrap(Bundle.main.url(forResource:"protocol-vectors",withExtension:"json"))
        let vector = try JSONSerialization.jsonObject(with:Data(contentsOf:resource)) as! [String:Any]
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(newID()); let vault = try Vault(root:root); defer {try? FileManager.default.removeItem(at:root)}
        let identity = try decoder.decode(Identity.self,from:JSONSerialization.data(withJSONObject:vector["recipient"]!)); try vault.put("identity",identity)
        let transfers = Transfers(vault:vault,models:try Models()); let inv = try decoder.decode(Invitation.self,from:JSONSerialization.data(withJSONObject:vector["invitation"]!))
        // Get the current boot marker from the same implementation, then substitute the public fixture ID.
        _ = try transfers.invite(); let pending = try XCTUnwrap(vault.get("invitations",as:[PendingInvitation].self)?.first)
        try vault.put("invitations",[PendingInvitation(invitation:inv,deadline:pending.deadline,boot:pending.boot)])
        let bytes = try un64(vector["envelope"] as! String); let opened = try transfers.open(bytes)
        XCTAssertEqual(opened.payload.meeting?.summary,"שלום · Prüfung · offline")
        XCTAssertEqual(try transfers.accept(opened,profileConsent:false),vector["receipt"] as? String)
    }
    func testApprovalInvalidationAndSources() throws { var s = Meeting(title:"Fixture"); s.editedSummary = "שלום"; s.approvedRevision = s.revision; XCTAssertTrue(s.approved); s.edit(); XCTAssertFalse(s.approved); XCTAssertTrue(s.history.last!.approved)
        let summary = Summary(title:"T",paragraphs:[Statement(text:"x",sources:["missing"])],decisions:[],actions:[],uncertainties:[]); XCTAssertThrowsError(try summary.validate([]))
    }
    func testQRReassemblyAndExpiry() throws {let data = Data((0..<2107).map{UInt8($0%251)}); let frames = try QrFrames.encode(data); let c = QrFrames.Collector(); XCTAssertNil(try c.add(frames[2],now:1)); XCTAssertNil(try c.add(frames[0],now:1)); XCTAssertNil(try c.add(frames[2],now:1)); XCTAssertNil(try c.add(frames[3],now:1)); XCTAssertEqual(try c.add(frames[1],now:1),data)
        let expired = QrFrames.Collector(); _ = try expired.add(frames[0],now:1); XCTAssertThrowsError(try expired.add(frames[1],now:602)); XCTAssertThrowsError(try checkJSONDepth(Data((String(repeating:"[",count:17)+String(repeating:"]",count:17)).utf8)))
    }
    func testVaultAndRecovery() throws { let root = FileManager.default.temporaryDirectory.appendingPathComponent(newID()); let vault = try Vault(root:root); defer {try? FileManager.default.removeItem(at:root)}
        var s = Meeting(title:"PRIVATE_TEST_עברית"); s.state = "recording"; let key = try vault.create(s); try vault.write(AudioChunk(index:0,startMs:0,pcm:Data(count:32000).base64EncodedString(),samples:16000),meeting:s.id,key:key)
        try vault.recover(); XCTAssertEqual(try vault.load(s.id).durationMs,1000); XCTAssertEqual(try vault.load(s.id).state,"interrupted")
        let encrypted = try Data(contentsOf:root.appendingPathComponent(s.id+"/session")); XCTAssertFalse(String(decoding:encrypted,as:UTF8.self).contains(s.title)); try vault.delete(s.id); XCTAssertTrue(try vault.list().isEmpty)
    }
}
