package tech.tamerin.contextfriendpro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

fun qrBitmap(text: String): Bitmap {
    val matrix=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,640,640,mapOf(EncodeHintType.MARGIN to 3,EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
    return Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888).apply {for(y in 0 until 640)for(x in 0 until 640)setPixel(x,y,if(matrix[x,y])Color.BLACK else Color.WHITE)}
}
@SuppressLint("MissingPermission")
@Composable fun QrCamera(modifier: Modifier,onResult: (String)->Unit) {
    val result by rememberUpdatedState(onResult)
    var camera by remember {mutableStateOf<CameraDevice?>(null)}
    var session by remember {mutableStateOf<CameraCaptureSession?>(null)}
    var reader by remember {mutableStateOf<ImageReader?>(null)}
    val thread=remember {HandlerThread("qr-camera").apply {start()}}
    DisposableEffect(Unit) {onDispose {session?.close();camera?.close();reader?.close();thread.quitSafely()} }
    AndroidView(modifier=modifier,factory={context -> TextureView(context).apply {
        surfaceTextureListener=object: TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture,width: Int,height: Int) {
                val manager=context.getSystemService(CameraManager::class.java)
                val id=manager.cameraIdList.firstOrNull {manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK} ?: return
                val handler=Handler(thread.looper)
                texture.setDefaultBufferSize(640,480)
                val output=ImageReader.newInstance(640,480,ImageFormat.YUV_420_888,2);reader=output
                var last="";var lastAt=0L
                output.setOnImageAvailableListener({r ->
                    val frame=r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val plane=frame.planes[0];val raw=ByteArray(plane.buffer.remaining());plane.buffer.get(raw)
                        val pixels=ByteArray(frame.width*frame.height)
                        for(y in 0 until frame.height)for(x in 0 until frame.width)pixels[y*frame.width+x]=raw[y*plane.rowStride+x*plane.pixelStride]
                        val source=PlanarYUVLuminanceSource(pixels,frame.width,frame.height,0,0,frame.width,frame.height,false)
                        val decoded=runCatching {MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text}.getOrNull()
                        if(decoded!=null && decoded.length<=2048 && (decoded!=last || SystemClock.elapsedRealtime()-lastAt>2000)) {
                            last=decoded;lastAt=SystemClock.elapsedRealtime();Handler(Looper.getMainLooper()).post {result(decoded)}
                        }
                    } finally {frame.close()}
                },handler)
                manager.openCamera(id,object: CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera=device;val preview=Surface(texture)
                        device.createCaptureSession(listOf(preview,output.surface),object: CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {session=s;val request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {addTarget(preview);addTarget(output.surface);set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)};s.setRepeatingRequest(request.build(),null,handler)}
                            override fun onConfigureFailed(s: CameraCaptureSession) {s.close()}
                        },handler)
                    }
                    override fun onDisconnected(device: CameraDevice){device.close()}
                    override fun onError(device: CameraDevice,error: Int){device.close()}
                },handler)
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture,w: Int,h: Int){}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {session?.close();camera?.close();return true}
            override fun onSurfaceTextureUpdated(s: SurfaceTexture){}
        }
    }})
}
