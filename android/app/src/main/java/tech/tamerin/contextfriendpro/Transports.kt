package tech.tamerin.contextfriendpro

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class LanReceiver(private val address: InetAddress,private val receive: suspend (ByteArray)->String): AutoCloseable {
    private val server=ServerSocket(0,1,address)
    val port: Int get()=server.localPort
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var peer: Socket?=null
    init {scope.launch {delay(600000);close()}}
    init {scope.launch {withTimeout(600000) {repeat(8) {
        try {
            val socket=server.accept();peer=socket;socket.soTimeout=30000
            socket.use {
                val input=DataInputStream(it.getInputStream());require(input.readInt()==0x43464c31)
                val n=input.readInt();require(n in 321..Transfers.MAX_BUNDLE);val bytes=ByteArray(n);input.readFully(bytes)
                val receipt=receive(bytes).toByteArray();require(receipt.size<=1024)
                DataOutputStream(it.getOutputStream()).apply {writeInt(receipt.size);write(receipt);flush()}
            }
        } catch(_: Exception) {if(server.isClosed)return@withTimeout}
    } };close()} }
    override fun close() {runCatching {peer?.close()};runCatching {server.close()};scope.cancel()}
}
object Lan {
    fun addresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList().filter {it.isUp && !it.isLoopback}.flatMap {it.inetAddresses.toList()}
        .filter {it is Inet4Address && it.isSiteLocalAddress}
    suspend fun send(inv: Invitation,bytes: ByteArray,progress: (Float)->Unit): String = withContext(Dispatchers.IO) {
        val host=inv.host ?: error("no_address");require(host.matches(Regex("[0-9.]+")))
        val ip=InetAddress.getByName(host);require(ip.isSiteLocalAddress && !ip.isLoopbackAddress)
        val socket=Socket()
        try {
            socket.connect(InetSocketAddress(ip,inv.port ?: error("no_port")),10000);socket.soTimeout=1000
            val out=DataOutputStream(socket.getOutputStream());out.writeInt(0x43464c31);out.writeInt(bytes.size)
            var offset=0
            while(offset<bytes.size) {currentCoroutineContext().ensureActive();val n=minOf(16384,bytes.size-offset);out.write(bytes,offset,n);offset+=n;progress(offset.toFloat()/bytes.size)}
            out.flush();val input=socket.getInputStream()
            suspend fun readExact(n: Int): ByteArray {val data=ByteArray(n);var at=0;while(at<n) {currentCoroutineContext().ensureActive();try {val count=input.read(data,at,n-at);require(count>0);at+=count}catch(_:SocketTimeoutException){}};return data}
            withTimeout(600000) {val n=ByteBuffer.wrap(readExact(4)).int;require(n in 1..1024);readExact(n).decodeToString()}
        } finally {socket.close()}
    }
}

/** In-memory partial transfers expire with the explicit foreground receive session. */
class FragmentReceiver {
    private var expectedHash: String?=null
    private var buffer=ByteArray(0)
    var offset=0;private set
    fun begin(data: ByteArray) {
        require(data.size==40);val r=ByteBuffer.wrap(data);require(r.int==0x43464231)
        val hash=ByteArray(32).also(r::get).joinToString("") {"%02x".format(it)};val size=r.int
        require(size in 321..2*1024*1024)
        if(expectedHash==hash) {require(buffer.size==size);return}
        require(expectedHash==null) {"different_transfer_active"};expectedHash=hash;buffer=ByteArray(size);offset=0
    }
    fun append(data: ByteArray): ByteArray? {
        require(expectedHash!=null && data.size>=5)
        val r=ByteBuffer.wrap(data);val at=r.int;val n=data.size-4;require(at>=0 && at+n<=buffer.size)
        if(at<offset) {require(at+n<=offset && buffer.copyOfRange(at,at+n).contentEquals(data.copyOfRange(4,data.size)));return null}
        require(at==offset);data.copyInto(buffer,offset,4);offset+=n
        if(offset!=buffer.size)return null
        require(digest(buffer)==expectedHash);return buffer.copyOf()
    }
    fun clear(){buffer.fill(0);buffer=ByteArray(0);offset=0;expectedHash=null}
}
@Suppress("MissingPermission", "DEPRECATION")
class BleReceiver(context: Context,private val receive: suspend (ByteArray)->String): AutoCloseable {
    private val manager=context.getSystemService(BluetoothManager::class.java)
    private val adapter=manager.adapter ?: error("bluetooth_unavailable")
    private val assembler=FragmentReceiver()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var receipt=ByteArray(0)
    private var complete=false
    private var peer: BluetoothDevice?=null
    private var server: BluetoothGattServer?=null
    private val advertise=object: AdvertiseCallback() {override fun onStartFailure(errorCode: Int){close()}}
    init {
        require(adapter.isEnabled && adapter.isMultipleAdvertisementSupported) {"ble_peripheral_unavailable"}
        server=manager.openGattServer(context,object: BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice,status: Int,newState: Int) {if(newState==BluetoothProfile.STATE_CONNECTED){if(peer!=null && peer!=device)server?.cancelConnection(device)else peer=device}}
            override fun onCharacteristicWriteRequest(device: BluetoothDevice,requestId: Int,characteristic: BluetoothGattCharacteristic,preparedWrite: Boolean,responseNeeded: Boolean,offset: Int,value: ByteArray) {
                val accepted=runCatching {
                    require(peer==device && !preparedWrite && offset==0)
                    when(characteristic.uuid) {
                        Ble.CONTROL -> assembler.begin(value)
                        Ble.DATA -> {val bytes=assembler.append(value);if(bytes!=null && !complete){complete=true;scope.launch {receipt=runCatching {receive(bytes).toByteArray()}.getOrElse {"rejected".toByteArray()}}}}
                        else -> error("unknown_characteristic")
                    }
                }.isSuccess
                if(responseNeeded)server?.sendResponse(device,requestId,if(accepted)BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,0,null)
            }
            override fun onCharacteristicReadRequest(device: BluetoothDevice,requestId: Int,offset: Int,characteristic: BluetoothGattCharacteristic) {
                val bytes=when(characteristic.uuid){Ble.CONTROL->ByteBuffer.allocate(4).putInt(assembler.offset).array();Ble.RECEIPT->receipt;else->ByteArray(0)}
                if(peer==device && offset in 0..bytes.size)server?.sendResponse(device,requestId,BluetoothGatt.GATT_SUCCESS,offset,bytes.copyOfRange(offset,bytes.size))
                else server?.sendResponse(device,requestId,BluetoothGatt.GATT_INVALID_OFFSET,offset,null)
            }
            override fun onServiceAdded(status: Int,service: BluetoothGattService) {
                if(status==BluetoothGatt.GATT_SUCCESS)adapter.bluetoothLeAdvertiser.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build(),
                    AdvertiseData.Builder().addServiceUuid(ParcelUuid(Ble.SERVICE)).setIncludeDeviceName(false).build(),advertise)
            }
        }) ?: error("ble_server_unavailable")
        val service=BluetoothGattService(Ble.SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(Ble.CONTROL,BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(Ble.DATA,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE))
        service.addCharacteristic(BluetoothGattCharacteristic(Ble.RECEIPT,BluetoothGattCharacteristic.PROPERTY_READ,BluetoothGattCharacteristic.PERMISSION_READ))
        check(server!!.addService(service))
        scope.launch {delay(600000);close()}
    }
    override fun close() {runCatching {adapter.bluetoothLeAdvertiser?.stopAdvertising(advertise)};server?.close();server=null;assembler.clear();scope.cancel()}
}
@Suppress("MissingPermission","DEPRECATION")
object Ble {
    val SERVICE: UUID=UUID.fromString("cf010001-e21b-48ac-b9dc-1920e92fbc01")
    val CONTROL: UUID=UUID.fromString("cf010002-e21b-48ac-b9dc-1920e92fbc01")
    val DATA: UUID=UUID.fromString("cf010003-e21b-48ac-b9dc-1920e92fbc01")
    val RECEIPT: UUID=UUID.fromString("cf010004-e21b-48ac-b9dc-1920e92fbc01")
    suspend fun discover(context: Context): List<BluetoothDevice> = withContext(Dispatchers.IO) {
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter ?: error("bluetooth_unavailable")
        require(adapter.isEnabled)
        val found=java.util.concurrent.ConcurrentHashMap<String,BluetoothDevice>()
        val callback=object: ScanCallback() {override fun onScanResult(type: Int,result: ScanResult) {found[result.device.address]=result.device}}
        adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),ScanSettings.Builder().build(),callback)
        try {delay(6000);found.values.toList()}finally {adapter.bluetoothLeScanner.stopScan(callback)}
    }
    suspend fun send(context: Context,device: BluetoothDevice,bytes: ByteArray,progress: (Float)->Unit): String=withContext(Dispatchers.IO) {
        require(bytes.size<=2*1024*1024);val events=Channel<Pair<String,ByteArray>>(Channel.UNLIMITED)
        var mtu=23
        val gatt=device.connectGatt(context,false,object: BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt,status: Int,state: Int) {events.trySend((if(status==0 && state==BluetoothProfile.STATE_CONNECTED)"connected"else "error") to ByteArray(0))}
            override fun onServicesDiscovered(g: BluetoothGatt,status: Int) {events.trySend((if(status==0)"services"else "error") to ByteArray(0))}
            override fun onMtuChanged(g: BluetoothGatt,value: Int,status: Int) {if(status==0)mtu=value;events.trySend("mtu" to ByteArray(0))}
            override fun onCharacteristicWrite(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int) {events.trySend((if(status==0)"write"else "error") to ByteArray(0))}
            override fun onCharacteristicRead(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int) {events.trySend((if(status==0)"read"else "error") to c.value)}
        },BluetoothDevice.TRANSPORT_LE)
        suspend fun event(type: String,timeout: Long=15000): ByteArray=withTimeout(timeout) {val value=events.receive();require(value.first==type) {"ble_transfer_failed"};value.second}
        try {
            event("connected");check(gatt.discoverServices());event("services")
            if(gatt.requestMtu(247))event("mtu")
            require(mtu>=43) {"ble_mtu_too_small_use_qr_or_lan"}
            val service=gatt.getService(SERVICE) ?: error("wrong_service")
            suspend fun write(uuid: UUID,value: ByteArray) {val c=service.getCharacteristic(uuid);c.writeType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;c.value=value;check(gatt.writeCharacteristic(c));event("write")}
            suspend fun read(uuid: UUID): ByteArray {check(gatt.readCharacteristic(service.getCharacteristic(uuid)));return event("read")}
            val hash=digest(bytes).chunked(2).map {it.toInt(16).toByte()}.toByteArray()
            write(CONTROL,ByteBuffer.allocate(40).putInt(0x43464231).put(hash).putInt(bytes.size).array())
            var offset=ByteBuffer.wrap(read(CONTROL)).int;require(offset in 0..bytes.size)
            while(offset<bytes.size) {ensureActive();val n=minOf(mtu-7,bytes.size-offset);write(DATA,ByteBuffer.allocate(n+4).putInt(offset).put(bytes,offset,n).array());offset+=n;progress(offset.toFloat()/bytes.size)}
            withTimeout(600000) {var receipt=ByteArray(0);while(receipt.isEmpty()) {delay(750);receipt=read(RECEIPT)};receipt.decodeToString()}
        } finally {gatt.disconnect();gatt.close();events.close()}
    }
}
