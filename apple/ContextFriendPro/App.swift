import SwiftUI
import Combine
import AVFoundation
import LocalAuthentication
import UniformTypeIdentifiers

final class AppStore: ObservableObject {
    let vault: Vault; let models: Models; let recorder: Recorder; let processing: Processing; let transfers: Transfers
    @Published var meetings: [Meeting] = []; @Published var profiles: [VoiceProfile] = []; @Published var preferences: Preferences
    @Published var failed = false; @Published var unlocked = true
    private var subscriptions: Set<AnyCancellable> = []; private let translations: [String:[String:String]]
    init() throws {
        vault = try Vault(); models = try Models(); preferences = try vault.preferences(); recorder = Recorder(vault:vault); processing = Processing(vault:vault,models:models); transfers = Transfers(vault:vault,models:models)
        guard let url = Bundle.main.url(forResource:"translations",withExtension:"json") else { throw ProError.unavailable }
        translations = try decoder.decode([String:[String:String]].self,from:Data(contentsOf:url)); try vault.recover()
        recorder.changed = { [weak self] in self?.refresh() }; processing.changed = { [weak self] in self?.refresh() }
        recorder.objectWillChange.receive(on:DispatchQueue.main).sink { [weak self] in self?.objectWillChange.send() }.store(in:&subscriptions)
        processing.objectWillChange.receive(on:DispatchQueue.main).sink { [weak self] in self?.objectWillChange.send() }.store(in:&subscriptions)
        refresh(); unlocked = !preferences.appLock
    }
    func t(_ key:String) -> String { translations[preferences.language]?[key] ?? translations["en"]?[key] ?? key }
    func refresh() { perform { meetings = try vault.list(); profiles = try vault.profiles() } }
    func perform(_ body:() throws -> Void) { do { try body() } catch { failed = true } }
    func savePreferences() { perform { try vault.put("preferences",preferences) } }
    func unlock() { let context = LAContext(); context.evaluatePolicy(.deviceOwnerAuthentication,localizedReason:t("unlock")) { ok,_ in DispatchQueue.main.async { self.unlocked = ok } } }
    func update(_ id:String,_ body:(inout Meeting) throws -> Void) { perform { try vault.update(id,body); refresh() } }
    var busy: Bool { recorder.active != nil || processing.active != nil }
}
@main struct ContextFriendProApp: App {
    @State private var store: AppStore?; @State private var failed = false
    var body: some Scene { WindowGroup {
        Group { if let store { RootView().environmentObject(store) } else { VStack { Text("ContextFriend Pro").font(.title); if failed { Button("Retry / Erneut / נסה שוב") { load() } } else { ProgressView() } }.task { load() } } }
    } }
    private func load() { do { store = try AppStore(); failed = false } catch { failed = true } }
}
struct RootView: View {
    @EnvironmentObject var app:AppStore; @Environment(\.scenePhase) var phase
    var body: some View {
        ZStack {
            if app.unlocked {
                TabView {
                    NavigationStack { MeetingsView() }.tabItem { Label(app.t("sessions"),systemImage:"waveform") }
                    NavigationStack { ModelView() }.tabItem { Label(app.t("models"),systemImage:"square.and.arrow.down") }
                    NavigationStack { ProfilesView() }.tabItem { Label(app.t("profiles"),systemImage:"person.crop.circle") }
                    NavigationStack { TransferView() }.tabItem { Label(app.t("transfer"),systemImage:"qrcode") }
                    NavigationStack { SettingsView() }.tabItem { Label(app.t("settings"),systemImage:"gearshape") }
                }
            } else { Button(app.t("unlock")) { app.unlock() }.buttonStyle(.borderedProminent) }
            if phase != .active { Color(.systemBackground).ignoresSafeArea(); Text("ContextFriend Pro").font(.title) }
        }
        .environment(\.layoutDirection,app.preferences.language == "he" ? .rightToLeft : .leftToRight)
        .alert(app.t("error"),isPresented:$app.failed) { Button(app.t("close"),role:.cancel) {} }
        .onChange(of:phase) { state in if state == .background { app.processing.cancel(); if app.preferences.appLock { app.unlocked = false } } }
    }
}
struct MeetingsView: View {
    @EnvironmentObject var app:AppStore; @State private var acknowledgement = false
    var body: some View {
        List {
            Section { Text(app.t("local")); Text(app.t("disclaimer")).font(.caption)
                if ProcessInfo.processInfo.physicalMemory < 6*1024*1024*1024 { Text(app.t("reader")) }
            }
            if app.recorder.active != nil {
                Section { Text(app.t(app.recorder.paused ? "paused":"recording")); Text(timestamp(app.recorder.elapsed)).monospacedDigit(); ProgressView(value:Double(app.recorder.level))
                    Button(app.t(app.recorder.paused ? "resume":"pause")) { app.perform { if app.recorder.paused { try app.recorder.resume() } else { app.recorder.pause() } } }
                    Button(app.t("stop"),role:.destructive) { app.recorder.stop() }
                }
            } else { Section { Text(app.t("record_notice")); Toggle(app.t("acknowledge"),isOn:$acknowledgement)
                Button(app.t("start")) { AVAudioSession.sharedInstance().requestRecordPermission { allowed in DispatchQueue.main.async { if allowed { app.perform { try app.recorder.start(language:app.preferences.language); app.refresh() } } else { app.failed = true } } } }.disabled(!acknowledgement || app.busy)
            } }
            if app.recorder.failure { Text(app.t("state_interrupted")) }
            if app.meetings.isEmpty { Text(app.t("no_sessions")) }
            ForEach(app.meetings) { meeting in NavigationLink { MeetingView(id:meeting.id) } label: { VStack(alignment:.leading) { Text(meeting.title).font(.headline); Text(Date(timeIntervalSince1970:Double(meeting.createdAt)/1000),style:.date); Text(app.t(meeting.approved ? "approved":"state_"+meeting.state)).font(.caption) } } }
        }.navigationTitle(app.t("app_name"))
    }
}
struct MeetingView: View {
    @EnvironmentObject var app:AppStore; @Environment(\.dismiss) var dismiss
    let id:String
    @State private var section = "summary"; @State private var edit = false; @State private var draft = ""; @State private var baseRevision = ""
    @State private var deletion = ""; @State private var export = false; @State private var share: String?; @State private var source: String?
    @StateObject private var player = AudioPlayback()
    var body: some View {
        if let s = app.meetings.first(where:{$0.id == id}) {
            ScrollViewReader { proxy in
                List {
                    Section { Text(app.t(s.approved ? "approved":"draft")); Text(app.t("state_"+s.state)); Text(app.t("disclaimer")); if !s.gaps.isEmpty { Text(app.t("gaps")).foregroundStyle(.orange); ForEach(Array(s.gaps.enumerated()),id:\.offset) { _,g in Text(timestamp(g.startMs)+" – "+(g.endMs.map(timestamp) ?? "?")) } }
                        if app.processing.active == id { ProgressView(); Button(app.t("cancel_processing")) { app.processing.cancel() } }
                        else if !s.received && s.audioRetained { Button(app.t("process")) { app.processing.run(id) }.disabled(app.busy) }
                        if !s.audioRetained { Text(app.t("audio_deleted")) }
                        Picker("",selection:$section) { ForEach(["summary","transcript","profiles"],id:\.self) { Text(app.t($0)).tag($0) } }.pickerStyle(.segmented)
                    }
                    if section == "summary" {
                        Section { if edit { TextEditor(text:$draft).frame(minHeight:240); Button(app.t("save")) { app.update(id) { try require($0.revision == baseRevision); $0.edit(); $0.editedSummary = draft }; edit = false }; Button(app.t("cancel")) { edit = false } }
                            else { Text(s.summaryText.isEmpty ? app.t("empty_summary"):s.summaryText).textSelection(.enabled)
                                if !s.summaryText.isEmpty { Button(app.t("edit")) { draft = s.summaryText; baseRevision = s.revision; edit = true }.disabled(app.busy)
                                    Button(app.t("approve")) { app.perform {try app.vault.update(id) { try require($0.revision == s.revision); $0.approvedRevision = s.revision }; if app.preferences.deleteAudioAfterApproval {try app.vault.deleteAudio(id)}; app.refresh()} }.disabled(app.busy || s.approved)
                                    NavigationLink(app.t("send")) { TransferView(initialMeeting:id) }; Button(app.t("external_export")) { export = true }
                                }
                            }
                        }
                        if let summary = s.summary { ForEach(Array((summary.paragraphs+summary.decisions+summary.uncertainties).enumerated()),id:\.offset) { _,statement in Section { Text(statement.text); ForEach(statement.sources,id:\.self) { ref in Button(app.t("source")+" · "+timestamp(s.transcript.first(where:{$0.id == ref})?.startMs ?? 0)) { source = ref; section = "transcript" } } } }
                            ForEach(Array(summary.actions.enumerated()),id:\.offset) { _,action in Section { Text([action.text,action.owner,action.due].compactMap{$0}.joined(separator:" · ")); ForEach(action.sources,id:\.self) { ref in Button(app.t("source")) { source = ref; section = "transcript" } } } }
                        }
                    } else if section == "transcript" {
                        ForEach(s.transcript) { line in TranscriptRow(meeting:id,line:line,speaker:line.speaker.flatMap{s.speakers[$0] ?? $0} ?? app.t("unknown"),play:{app.perform { try player.play(vault:app.vault,id:id,start:line.startMs,end:line.endMs) }},audio:s.audioRetained).id(line.id) }
                    } else { Section { Text(app.t("profile_notice")) }; ForEach(s.speakerVectors.keys.sorted(),id:\.self) { speaker in SpeakerRow(meeting:s,speaker:speaker) } }
                    Section { if player.playing { Button(app.t("stop")) { player.stop() } }; if s.audioRetained { Button(app.t("delete_audio"),role:.destructive) { deletion = "audio" }.disabled(app.busy) }; Button(app.t("delete"),role:.destructive) { deletion = "meeting" }.disabled(app.busy) }
                }.onChange(of:section) { _ in if let source { DispatchQueue.main.asyncAfter(deadline:.now()+0.15) { proxy.scrollTo(source,anchor:.center) } } }
            }
            .navigationTitle(s.title)
            .alert(app.t("confirm_delete"),isPresented:Binding(get:{!deletion.isEmpty},set:{if !$0 {deletion = ""}})) { Button(app.t("delete"),role:.destructive) { app.perform { if deletion == "audio" { try app.vault.deleteAudio(id) } else { try app.vault.delete(id); dismiss() }; deletion = ""; app.refresh() } }; Button(app.t("cancel"),role:.cancel) { deletion = "" } }
            .alert(app.t("external_notice"),isPresented:$export) { Button(app.t("external_export")) { share = (s.approved ? "":app.t("draft")+"\n")+s.summaryText }; Button(app.t("cancel"),role:.cancel) {} }
            .sheet(isPresented:Binding(get:{share != nil},set:{if !$0 {share = nil}})) { ShareSheet(text:share ?? "") }
            .onDisappear { player.stop() }
        } else { Text(app.t("no_sessions")) }
    }
}
struct TranscriptRow:View {
    @EnvironmentObject var app:AppStore; let meeting:String; let line:Segment; let speaker:String; let play:()->Void; let audio:Bool
    @State private var editing = false; @State private var draft = ""; @State private var assigned:String?
    var body: some View { VStack(alignment:.leading) { Text(timestamp(line.startMs)+" · "+speaker+(line.uncertain ? " ?":"")).font(.caption)
        if editing { TextField(app.t("transcript"),text:$draft,axis:.vertical); Picker(app.t("profiles"),selection:$assigned) { Text(app.t("unknown")).tag(String?.none); ForEach((app.meetings.first(where:{$0.id == meeting})?.speakerVectors.keys.map{$0} ?? []).sorted(),id:\.self) {Text($0).tag(Optional($0))} }; Button(app.t("save")) { app.update(meeting) { s in guard let i = s.transcript.firstIndex(where:{$0.id == line.id}) else {throw ProError.invalid}; s.edit(); s.transcript[i].correction = draft; s.transcript[i].speaker = assigned }; editing = false } }
        else { Text(line.text).textSelection(.enabled); Button(app.t("edit")) { draft = line.text; assigned = line.speaker; editing = true }.disabled(app.busy) }; if audio { Button(app.t("play"),action:play).disabled(app.recorder.active != nil) }
    } }
}
struct SpeakerRow:View {
    @EnvironmentObject var app:AppStore; let meeting:Meeting; let speaker:String
    @State private var name = ""; @State private var consent = false
    var body: some View { Section { Text(speaker); TextField(app.t("name"),text:$name)
        Button(app.t("rename")) { app.update(meeting.id) { $0.edit(); $0.speakers[speaker] = name } }.disabled(name.isEmpty || app.busy)
        if let id = meeting.suggestedProfiles[speaker], let p = app.profiles.first(where:{$0.id == id}) { Button(app.t("confirm_match")+": "+p.name) { app.update(meeting.id) {$0.edit(); $0.speakers[speaker] = p.name; $0.suggestedProfiles.removeValue(forKey:speaker)} }.disabled(app.busy) }
        Toggle(app.t("profile_consent"),isOn:$consent)
        Button(app.t("enroll")) { app.perform { try require(meeting.transcript.filter{$0.speaker == speaker}.reduce(Int64(0)){$0+$1.endMs-$1.startMs} >= 10000); let p = VoiceProfile(name:name,embedding:meeting.speakerVectors[speaker]!,modelHash:try app.models.entry("titanet-small").sha256); try app.vault.saveProfiles(app.profiles+[p]); app.refresh() } }.disabled(!consent || name.isEmpty || app.busy)
    }.onAppear { name = meeting.speakers[speaker] ?? "" } }
}
struct ProfilesView:View {
    @EnvironmentObject var app:AppStore; @State private var deleting:String?
    var body: some View { List { Text(app.t("profile_notice")); Toggle(app.t("matching"),isOn:$app.preferences.matching).onChange(of:app.preferences.matching) {_ in app.savePreferences()}; if app.profiles.isEmpty { Text(app.t("no_profiles")) }
        ForEach(app.profiles) { p in Section { ProfileRow(profile:p); Button(app.t("delete_profile"),role:.destructive) { deleting = p.id } } }
    }.navigationTitle(app.t("profiles")).alert(app.t("confirm_delete"),isPresented:Binding(get:{deleting != nil},set:{if !$0 {deleting = nil}})) { Button(app.t("delete"),role:.destructive) { app.perform { let id = deleting!; try app.vault.saveProfiles(app.profiles.filter{$0.id != id}); for s in app.meetings { try app.vault.update(s.id) { $0.suggestedProfiles = $0.suggestedProfiles.filter{$0.value != id} } }; deleting = nil; app.refresh() } }; Button(app.t("cancel"),role:.cancel) {deleting = nil} } }
}
struct ProfileRow:View {
    @EnvironmentObject var app:AppStore; let profile:VoiceProfile; @State private var name = ""; @State private var enabled = false
    var body:some View { TextField(app.t("name"),text:$name); Toggle(app.t("matching"),isOn:$enabled); Button(app.t("save")) { app.perform { try app.vault.saveProfiles(app.profiles.map { p in if p.id == profile.id {var p = p; p.name = name; p.enabled = enabled; return p}; return p }); app.refresh() } }.onAppear {name = profile.name; enabled = profile.enabled} }
}
struct SettingsView:View {
    @EnvironmentObject var app:AppStore
    var body:some View { Form { Picker(app.t("language"),selection:$app.preferences.language) { Text("English").tag("en"); Text("Deutsch").tag("de"); Text("עברית").tag("he") }.onChange(of:app.preferences.language) {_ in app.savePreferences()}
        Toggle(app.t("lock"),isOn:$app.preferences.appLock).onChange(of:app.preferences.appLock) { value in let context = LAContext(); if value && !context.canEvaluatePolicy(.deviceOwnerAuthentication,error:nil) {app.preferences.appLock = false; app.failed = true}; app.savePreferences() }
        Toggle(app.t("retention"),isOn:$app.preferences.deleteAudioAfterApproval).onChange(of:app.preferences.deleteAudioAfterApproval) {_ in app.savePreferences()}
        Text(app.t("loss_notice")); Text(app.t("disclaimer"))
        ForEach((try? app.transfers.peers()) ?? []) { peer in HStack { Text(peer.label); Button(app.t("delete"),role:.destructive) {app.perform {try app.transfers.untrust(peer.signKey); app.objectWillChange.send()}} } }
    }.navigationTitle(app.t("settings")) }
}
struct ModelView:View {
    @EnvironmentObject var app:AppStore; @State private var importing:String?; @State private var showImport = false; @State private var working:String?; @State private var progress:Double = 0; @State private var task:Task<Void,Never>?
    var body:some View { List { Text(app.t("setup_notice")); if ProcessInfo.processInfo.physicalMemory < 6*1024*1024*1024 { Text(app.t("reader")) }
        Picker(app.t("models"),selection:$app.preferences.asrModel) { Text("Whisper Tiny").tag("whisper-tiny"); Text("Whisper Small Q5").tag("whisper-small") }.onChange(of:app.preferences.asrModel) {_ in app.savePreferences()}
        ForEach(app.models.manifest.models) { m in Section { Text(m.id).font(.headline); Text(m.license); Text(ByteCountFormatter.string(fromByteCount:m.bytes,countStyle:.file)); Text(app.t(app.models.installed(m.id) ? "installed":"missing")); Text(m.sha256).font(.caption2).textSelection(.enabled)
            if working == m.id { ProgressView(value:progress); Button(app.t("cancel")) { task?.cancel() } } else {
                Button(app.t("install")) { importing = m.id; showImport = true }.disabled(working != nil || app.busy)
                if m.archive != true { Button(app.t("download")) { working = m.id; progress = 0; task = Task { do { try await app.models.download(m.id) { p in DispatchQueue.main.async {progress = p} } } catch { if !Task.isCancelled {app.failed = true} }; working = nil; app.objectWillChange.send() } }.disabled(working != nil || app.busy) }
                if app.models.installed(m.id) { Button(app.t("delete"),role:.destructive) {app.perform {try app.models.remove(m.id); app.objectWillChange.send()}}.disabled(app.busy || working != nil) }
            }
        } }
    }.navigationTitle(app.t("models")).fileImporter(isPresented:$showImport,allowedContentTypes:[.data]) { result in
        guard let id = importing else {return}; importing = nil; working = id; progress = 0
        task = Task { do { let url = try result.get(); let worker = Task.detached { try app.models.install(id,from:url,progress:{p in DispatchQueue.main.async {progress = p}},cancelled:{Task.isCancelled}) }; try await withTaskCancellationHandler(operation:{try await worker.value},onCancel:{worker.cancel()}) } catch {if !Task.isCancelled {app.failed = true}}; working = nil; app.objectWillChange.send() }
    } }
}
struct ShareSheet:UIViewControllerRepresentable { let text:String; func makeUIViewController(context:Context)->UIActivityViewController {UIActivityViewController(activityItems:[text],applicationActivities:nil)}; func updateUIViewController(_ uiViewController:UIActivityViewController,context:Context) {} }
func timestamp(_ ms:Int64)->String {String(format:"%02lld:%02lld",ms/60000,ms/1000%60)}

final class AudioPlayback:ObservableObject {
    @Published var playing = false; private var engine:AVAudioEngine?; private var node:AVAudioPlayerNode?; private var job:Task<Void,Never>?
    func stop() {job?.cancel(); job = nil; node?.stop(); engine?.stop(); node = nil; engine = nil; playing = false}
    func play(vault:Vault,id:String,start:Int64,end:Int64) throws {
        stop(); try require(end > start); let audio = AVAudioSession.sharedInstance(); try audio.setCategory(.playback); try audio.setActive(true)
        let engine = AVAudioEngine(), node = AVAudioPlayerNode(); let format = AVAudioFormat(standardFormatWithSampleRate:16000,channels:1)!
        engine.attach(node); engine.connect(node,to:engine.mainMixerNode,format:format); try engine.start(); node.play(); self.engine = engine; self.node = node; playing = true
        job = Task { do { let reader = try AudioReader(vault:vault,id:id); for at in stride(from:start,to:end,by:5000) {try Task.checkCancellation(); let pcm = try reader.window(start:at,duration:min(5000,end-at)); let buffer = AVAudioPCMBuffer(pcmFormat:format,frameCapacity:AVAudioFrameCount(pcm.count))!; buffer.frameLength = buffer.frameCapacity; pcm.withUnsafeBufferPointer {buffer.floatChannelData![0].update(from:$0.baseAddress!,count:pcm.count)}
                try await withTaskCancellationHandler(operation:{ await withCheckedContinuation { (done:CheckedContinuation<Void,Never>) in node.scheduleBuffer(buffer,completionCallbackType:.dataPlayedBack) {_ in done.resume()} } },onCancel:{node.stop()})
            } } catch { }
            self.stop()
        }
    }
}
