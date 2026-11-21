import Foundation
import AVFoundation
import Combine
import UIKit

final class Recorder: ObservableObject {
    @Published private(set) var active: String?
    @Published private(set) var paused = false
    @Published private(set) var level: Float = 0
    @Published private(set) var elapsed: Int64 = 0
    @Published private(set) var failure = false
    private let vault: Vault
    private let engine = AVAudioEngine()
    private let writer = DispatchQueue(label: "encrypted-audio-writer")
    private let capacity = DispatchSemaphore(value: 2)
    private var key: Data?
    private var meeting: Meeting?
    private var origin: Double = 0
    private var observer: [NSObjectProtocol] = []
    private var pending = [Int16]()
    private var pendingStart: Int64 = 0
    private var index = 0
    private let stateLock = NSLock(); private var stopFlag = false
    private var stopping:Bool { get {stateLock.lock(); defer {stateLock.unlock()}; return stopFlag} set {stateLock.lock(); stopFlag = newValue; stateLock.unlock()} }
    var changed: (() -> Void)?
    init(vault: Vault) {
        self.vault = vault
        for name in [AVAudioSession.interruptionNotification, AVAudioSession.routeChangeNotification, AVAudioSession.mediaServicesWereResetNotification] {
            observer.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in self?.pause(reason: "audio_interrupted") })
        }
    }
    deinit { observer.forEach(NotificationCenter.default.removeObserver) }
    func start(language: String) throws {
        try require(active == nil)
        let audio = AVAudioSession.sharedInstance(); try audio.setCategory(.record, mode: .measurement, options: [.allowBluetooth]); try audio.setActive(true)
        var s = Meeting(title: DateFormatter.localizedString(from: Date(), dateStyle: .medium, timeStyle: .short)); s.language = language; s.state = "recording"
        key = try vault.create(s); meeting = s; origin = ProcessInfo.processInfo.systemUptime; index = 0; pending = []; stopping = false
        active = s.id; paused = false; failure = false; elapsed = 0
        do { try installTap(); try engine.start() } catch { failure = true; stop(); throw error }
    }
    private func installTap() throws {
        let input = engine.inputNode; let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16000, channels: 1, interleaved: false), let converter = AVAudioConverter(from: format, to: target) else { throw ProError.unavailable }
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, time in
            guard let self, !self.stopping else { return }
            // Copy into a bounded queue; no disk access or inference in the audio callback.
            guard self.capacity.wait(timeout: .now()) == .success else { DispatchQueue.main.async { self.failure = true; self.stop() }; return }
            guard let copied = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: buffer.frameLength) else { self.capacity.signal(); return }
            copied.frameLength = buffer.frameLength
            let src = UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList), dst = UnsafeMutableAudioBufferListPointer(copied.mutableAudioBufferList)
            for i in 0..<src.count { memcpy(dst[i].mData!, src[i].mData!, Int(src[i].mDataByteSize)) }
            let at = max(Int64(0), Int64((AVAudioTime.seconds(forHostTime: time.hostTime) - self.origin) * 1000))
            self.writer.async {
                defer { self.capacity.signal() }
                do {
                    let n = AVAudioFrameCount(ceil(Double(copied.frameLength) * 16000 / format.sampleRate) + 64)
                    guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: n) else { throw ProError.unavailable }
                    var supplied = false; var error: NSError?
                    let status = converter.convert(to: output, error: &error) { _, flag in if supplied { flag.pointee = .noDataNow; return nil }; supplied = true; flag.pointee = .haveData; return copied }
                    guard status != .error, error == nil, let samples = output.floatChannelData?[0] else { throw ProError.unavailable }
                    var peak: Float = 0
                    for i in 0..<Int(output.frameLength) {
                        if self.pending.isEmpty { self.pendingStart = at + Int64(i / 16) }
                        peak = max(peak, abs(samples[i])); self.pending.append(Int16(max(-32768, min(32767, samples[i] * 32768))))
                        if self.pending.count == 16000 { try self.commit() }
                    }
                    DispatchQueue.main.async { self.level = peak; self.elapsed = Int64((ProcessInfo.processInfo.systemUptime - self.origin) * 1000) }
                } catch { DispatchQueue.main.async { self.failure = true; self.stop() } }
            }
        }
    }
    private func commit() throws {
        guard !pending.isEmpty, let key, var s = meeting else { return }
        let capacity = try vault.root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
        guard capacity > 128 * 1024 * 1024 else { throw ProError.resourcePressure }
        var bytes = Data(); bytes.reserveCapacity(pending.count * 2)
        for var value in pending.map(\.littleEndian) { withUnsafeBytes(of: &value) { bytes.append(contentsOf: $0) } }
        let chunk = AudioChunk(index: index, startMs: pendingStart, pcm: bytes.base64EncodedString(), samples: pending.count)
        try vault.write(chunk, meeting: s.id, key: key); index += 1; s.durationMs = max(s.durationMs, pendingStart + Int64(pending.count / 16))
        try vault.save(s, key: key); meeting = s; pending = []; bytes.resetBytes(in: bytes.startIndex..<bytes.endIndex)
    }
    func pause(reason: String = "user_pause") {
        guard active != nil && !paused && !stopping else { return }
        engine.stop(); engine.inputNode.removeTap(onBus: 0); paused = true
        let at = Int64((ProcessInfo.processInfo.systemUptime - origin) * 1000)
        writer.async { do { try self.commit(); self.meeting?.state = "paused"; self.meeting?.gaps.append(Gap(startMs: at, reason: reason)); if let s = self.meeting { try self.vault.save(s, key: self.key) } } catch { DispatchQueue.main.async { self.failure = true; self.stop() } } }
    }
    func resume() throws {
        try require(active != nil && paused)
        writer.sync { if let last = meeting?.gaps.indices.last { meeting?.gaps[last].endMs = Int64((ProcessInfo.processInfo.systemUptime - origin) * 1000) }; meeting?.state = "recording" }
        try AVAudioSession.sharedInstance().setActive(true); try installTap(); try engine.start(); paused = false
    }
    func stop() {
        guard active != nil && !stopping else { return }; stopping = true
        let captureFailed = failure
        engine.stop(); if !paused { engine.inputNode.removeTap(onBus: 0) }
        writer.async {
            do { try self.commit(); if var s = self.meeting {
                s.state = captureFailed ? "interrupted" : "recorded"
                if captureFailed { s.gaps.append(Gap(startMs: s.durationMs, reason: "capture_interrupted")) }
                try self.vault.save(s, key: self.key)
            } } catch { DispatchQueue.main.async { self.failure = true } }
            if var raw = self.key { raw.resetBytes(in: 0..<raw.count) }; self.key = nil; self.meeting = nil; self.pending = []
            DispatchQueue.main.async { self.active = nil; self.paused = false; self.changed?(); try? AVAudioSession.sharedInstance().setActive(false) }
        }
    }
}
