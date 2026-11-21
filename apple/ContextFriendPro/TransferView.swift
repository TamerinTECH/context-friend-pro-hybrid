import SwiftUI
import CoreBluetooth

struct TransferView:View {
    @EnvironmentObject var app:AppStore; @Environment(\.scenePhase) var phase
    var initialMeeting:String? = nil
    @State private var receiving = false; @State private var profileMode = false; @State private var selected:String?; @State private var selectedProfiles:Set<String> = []
    @State private var transcript = false; @State private var audio = false; @State private var draft = false; @State private var profileConsent = false
    @State private var invitation:Invitation?; @State private var verified = false; @State private var payload:BundlePayload?; @State private var sealed:Data?
    @State private var frames:[String] = []; @State private var frame = 0; @State private var collector = QrFrames.Collector()
    @State private var opened:OpenBundle?; @State private var receipt:String?; @State private var confirmed = false; @State private var scanner = false; @State private var pasted = ""
    @State private var continuation:CheckedContinuation<String,Error>?; @State private var lan:LANReceiver?; @State private var task:Task<Void,Never>?; @State private var progress:Double = 0; @State private var sending = false
    @StateObject private var bluetooth = Bluetooth()
    var body:some View {
        List {
            if bluetooth.active {Button(app.t("cancel")) {bluetooth.stop()}}
            Picker("",selection:$receiving) { Text(app.t("send")).tag(false); Text(app.t("receive")).tag(true) }.pickerStyle(.segmented).onChange(of:receiving) {_ in closeSession(); payload = nil; sealed = nil; frames = []; invitation = nil; verified = false}
            if receiving {
                Section {
                    Button(app.t("qr")) { app.perform { closeSession(); invitation = try app.transfers.invite(); collector = QrFrames.Collector(); receipt = nil } }
                    Button(app.t("ble")) { app.perform { closeSession(); invitation = try app.transfers.invite(); bluetooth.receive = receive; bluetooth.listen() } }
                    Button(app.t("lan")) { app.perform { closeSession(); guard let host = LAN.addresses().first else {throw ProError.unavailable}; let server = LANReceiver(); lan = server
                        try server.start(host:host,ready:{port in app.perform {invitation = try app.transfers.invite(host:host,port:Int(port))}},receive:receive,failure:{DispatchQueue.main.async {app.failed = true}})
                    } }
                    if let invitation { QRImage(text:(try? invitation.encoded()) ?? ""); Text(invitation.fingerprint).font(.system(.body,design:.monospaced)); if let host = invitation.host, let port = invitation.port {Text("\(host):\(port)")}; Button(app.t("cancel")) {closeSession(); self.invitation = nil} }
                    if let receipt { Text(app.t("confirmed")); QRImage(text:receipt) }
                }
            } else {
                Section {
                    Toggle(app.t("profiles"),isOn:$profileMode).onChange(of:profileMode) {_ in invalidate()}
                    if profileMode { Text(app.t("profile_share_notice")); ForEach(app.profiles) { p in Toggle(p.name,isOn:Binding(get:{selectedProfiles.contains(p.id)},set:{if $0 {selectedProfiles.insert(p.id)} else {selectedProfiles.remove(p.id)};invalidate()})) }; Toggle(app.t("profile_share_confirm"),isOn:$profileConsent).onChange(of:profileConsent) {_ in invalidate()} }
                    else {
                        Picker(app.t("sessions"),selection:$selected) { Text(app.t("choose")).tag(String?.none); ForEach(app.meetings) {Text($0.title).tag(Optional($0.id))} }.onChange(of:selected) {_ in invalidate()}
                        Toggle(app.t("include_transcript"),isOn:$transcript).onChange(of:transcript) {_ in invalidate()}; Toggle(app.t("include_audio"),isOn:$audio).onChange(of:audio) {_ in invalidate()}; Toggle(app.t("allow_draft"),isOn:$draft).onChange(of:draft) {_ in invalidate()}
                    }
                    Button(app.t("preview")) { app.perform {
                        if profileMode { try require(profileConsent); payload = BundlePayload(purpose:"profiles",profiles:app.profiles.filter{selectedProfiles.contains($0.id)}); try payload!.validate() }
                        else {guard let selected else {throw ProError.invalid}; payload = try app.transfers.meeting(app.vault.load(selected),transcript:transcript,audio:audio,draft:draft) }; sealed = nil; frames = []
                    } }.disabled(app.busy)
                }
                if let payload { preview(payload) }
                if let invitation { Section {Text(app.t("verify_recipient")); Text(invitation.fingerprint); Toggle(app.t("confirm_recipient"),isOn:$verified)
                    if verified { Button(app.t("trusted")) {app.perform {try app.transfers.trust(invitation,label:invitation.fingerprint)}} }
                } }
                Button(app.t("prepare")) { app.perform {guard let payload,let invitation,verified else {throw ProError.invalid}; sealed = try app.transfers.seal(payload,to:invitation); confirmed = false} }.disabled(!verified || payload == nil || invitation == nil)
                if let sealed {
                    Section { Text(ByteCountFormatter.string(fromByteCount:Int64(sealed.count),countStyle:.file))
                        Button(app.t("qr")) {app.perform {frames = try QrFrames.encode(sealed); frame = 0}}
                        Button(app.t("ble")) { bluetooth.completed = checkReceipt; bluetooth.scan() }.disabled(sending)
                        ForEach(bluetooth.devices,id:\.identifier) { device in Button(device.identifier.uuidString.prefix(8).description) {app.perform {try bluetooth.send(sealed,to:device)}} }
                        ProgressView(value:bluetooth.progress)
                        if let invitation, invitation.host != nil { Button(app.t("lan")) { sending = true; progress = 0; task = Task { do { let r = try await LAN.send(sealed,invitation:invitation) {p in DispatchQueue.main.async {progress = p}}; checkReceipt(r) } catch {if !Task.isCancelled {app.failed = true}}; sending = false } }.disabled(sending) }
                        if sending {ProgressView(value:progress); Button(app.t("cancel")) {task?.cancel(); bluetooth.stop(); sending = false}}
                        Text(app.t(confirmed ? "confirmed":"unconfirmed"))
                    }
                }
                if !frames.isEmpty { Section {QRImage(text:frames[frame]); Text("\(frame+1) / \(frames.count)"); HStack {Button(app.t("previous")) {frame = max(0,frame-1)}; Spacer(); Button(app.t("next")) {frame = min(frames.count-1,frame+1)}}} }
            }
            Section {Button(app.t("scan")) {scanner = true}; TextField(app.t("paste"),text:$pasted,axis:.vertical).textInputAutocapitalization(.never).autocorrectionDisabled(); Button(app.t("accept")) {scan(pasted)}; if collector.count > 0 {Text("\(collector.received) / \(collector.count)")} }
            if let opened { Section {Text(app.t(opened.trusted ? "trusted":"unverified")); preview(opened.payload)
                if opened.payload.purpose == "profiles" { Text(app.t("profile_share_notice")); Toggle(app.t("profile_share_confirm"),isOn:$profileConsent) }
                Button(app.t("accept")) {app.perform {let r = try app.transfers.accept(opened,profileConsent:profileConsent); receipt = r; continuation?.resume(returning:r); continuation = nil; self.opened = nil; app.refresh()}}.disabled(opened.payload.purpose == "profiles" && !profileConsent)
                Button(app.t("cancel"),role:.cancel) {continuation?.resume(throwing:ProError.cancelled); continuation = nil; self.opened = nil}
            } }
        }.navigationTitle(app.t("transfer"))
        .onAppear {selected = initialMeeting}
        .onDisappear {closeSession()}
        .onChange(of:phase) {if $0 == .background {closeSession()}}
        .onChange(of:bluetooth.failure) {if $0 {app.failed = true}}
        .sheet(isPresented:$scanner) {VStack {QRScanner(scanned:scan); Button(app.t("close")) {scanner = false}}}
    }
    @ViewBuilder private func preview(_ p:BundlePayload)->some View {
        Section(app.t("preview")) { if let m = p.meeting {Text(app.t(m.approved ? "approved":"draft")); Text(m.title).font(.headline); Text(m.summary); ForEach(m.transcript ?? []) {Text(timestamp($0.startMs)+" · "+$0.text)}; if let audio = m.audio {Text(app.t("include_audio")+": \(audio.reduce(0){$0+$1.samples}/16000) s")} }; ForEach(p.profiles ?? []) {Text($0.name+" · "+$0.modelHash.prefix(12))} }
    }
    private func invalidate() {payload = nil; sealed = nil; frames = []; confirmed = false}
    private func scan(_ text:String) {app.perform {
        if text.hasPrefix("CFI1:") {invitation = try Invitation.decode(text); verified = false; scanner = false}
        else if text.hasPrefix("CFQ1:") {if let bytes = try collector.add(text) {opened = try app.transfers.open(bytes); profileConsent = false; scanner = false}}
        else if text.hasPrefix("CFR1:") {checkReceipt(text); scanner = false}
        else {throw ProError.invalid}
    } }
    private func checkReceipt(_ text:String) {app.perform {guard let invitation,let sealed else {throw ProError.invalid}; confirmed = try app.transfers.verifyReceipt(text,invitation:invitation,bytes:sealed)}}
    @MainActor private func receive(_ bytes:Data) async throws -> String { let open = try app.transfers.open(bytes); try require(continuation == nil); opened = open; profileConsent = false
        return try await withCheckedThrowingContinuation {continuation = $0}
    }
    private func closeSession() {lan?.stop(); lan = nil; bluetooth.stop(); task?.cancel(); continuation?.resume(throwing:ProError.cancelled); continuation = nil; if receiving, let invitation {try? app.transfers.revoke(invitation.id)} }
}
