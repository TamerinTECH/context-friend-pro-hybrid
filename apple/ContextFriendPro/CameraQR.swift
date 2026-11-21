import SwiftUI
import AVFoundation
import CoreImage.CIFilterBuiltins

struct QRImage: View {
    let text: String
    var body: some View {
        if let image = bitmap { Image(uiImage:image).interpolation(.none).resizable().scaledToFit().padding(16).background(.white).accessibilityLabel("QR") }
    }
    private var bitmap: UIImage? { let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8); filter.correctionLevel = "M"; guard let out = filter.outputImage, let cg = CIContext().createCGImage(out.transformed(by:CGAffineTransform(scaleX:8,y:8)),from:out.extent.applying(CGAffineTransform(scaleX:8,y:8))) else { return nil }; return UIImage(cgImage:cg) }
}
struct QRScanner: UIViewControllerRepresentable {
    var scanned: (String) -> Void
    func makeUIViewController(context:Context) -> CameraController { let c = CameraController(); c.scanned = scanned; return c }
    func updateUIViewController(_ uiViewController:CameraController,context:Context) {}
    static func dismantleUIViewController(_ c:CameraController,coordinator:()) { c.stop() }
}
final class CameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var scanned: ((String)->Void)?; private let session = AVCaptureSession(); private let queue = DispatchQueue(label:"qr-camera"); private var layer:AVCaptureVideoPreviewLayer?; private var previous = ""; private var at = Date.distantPast; private var ended = false
    override func viewDidLoad() { super.viewDidLoad(); view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for:.video) { [weak self] allowed in guard allowed, let self else { return }; self.queue.async {
            guard !self.ended, let camera = AVCaptureDevice.default(for:.video), let input = try? AVCaptureDeviceInput(device:camera), self.session.canAddInput(input) else { return }
            self.session.addInput(input); let output = AVCaptureMetadataOutput(); guard self.session.canAddOutput(output) else { return }; self.session.addOutput(output); output.setMetadataObjectsDelegate(self,queue:.main); output.metadataObjectTypes = [.qr]
            DispatchQueue.main.async { let layer = AVCaptureVideoPreviewLayer(session:self.session); layer.videoGravity = .resizeAspectFill; layer.frame = self.view.bounds; self.view.layer.addSublayer(layer); self.layer = layer }; self.session.startRunning()
        } }
    }
    override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); layer?.frame = view.bounds }
    func metadataOutput(_ output:AVCaptureMetadataOutput,didOutput metadataObjects:[AVMetadataObject],from connection:AVCaptureConnection) {
        guard let text = (metadataObjects.first as? AVMetadataMachineReadableCodeObject)?.stringValue, text.count <= 2048, text != previous || Date().timeIntervalSince(at)>2 else { return }; previous = text; at = Date(); scanned?(text)
    }
    func stop() { queue.async { self.ended = true; self.session.stopRunning() } }
}
