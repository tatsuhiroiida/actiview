package iida.work.actiview

import android.os.Bundle
import android.os.RemoteException
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.altbeacon.beacon.*
import org.altbeacon.beacon.MonitorNotifier.INSIDE


class MainActivity : AppCompatActivity(), IActivityLifeCycle, BeaconConsumer {

    private val db = FirebaseFirestore.getInstance()
    private val TAG: String
        get() = MainActivity::class.java.simpleName

    private val TARGET_UUID: String = "2edb0100-022a-468c-a7cc-d3e066206d59"

    private val mLifeCycle: ActivityLifeCycle = ActivityLifeCycle(this)

    private lateinit var mBeaconManager: BeaconManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(mLifeCycle)
        setContentView(R.layout.activity_main)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycle.removeObserver(mLifeCycle)
    }

    override fun onCreated() {
        mBeaconManager = BeaconManager.getInstanceForApplication(this)
        mBeaconManager.beaconParsers.add(BeaconParser().setBeaconLayout(BeaconUtil.IBEACON_FORMAT))
    }

    override fun onConnected() {
        mBeaconManager.bind(this@MainActivity)
    }

    override fun onDisconnect() {
        mBeaconManager.unbind(this@MainActivity)
    }

    private val iBeacons = mutableListOf<IBeacon>()

    override fun onBeaconServiceConnect() {
        val mRegion = Region(packageName, Identifier.parse(TARGET_UUID), null, null)

        mBeaconManager.addMonitorNotifier(object : MonitorNotifier {
            override fun didEnterRegion(region: Region) {

                createSendData(region.id1.toString(), ActiveState.ENTER, 0.0)
                        .also { addToDB(it) }

                mBeaconManager.startRangingBeaconsInRegion(mRegion)
            }

            override fun didExitRegion(region: Region) {

                createSendData(TARGET_UUID, ActiveState.EXIT, 0.0)
                        .also { addToDB(it) }

                mBeaconManager.stopRangingBeaconsInRegion(mRegion)
            }

            override fun didDetermineStateForRegion(i: Int, region: Region) {
                when (i) {
                    INSIDE -> {
                        createSendData(TARGET_UUID, ActiveState.ENTER, 0.0)
                                .also { addToDB(it) }

                        mBeaconManager.startRangingBeaconsInRegion(mRegion)
                    }
                }
            }
        })
        mBeaconManager.addRangeNotifier { beacons, region ->

            beacons
                    .map {
                        IBeacon(it.id1.toString(), it.id2.toInt(), it.id3.toInt(), it.rssi, it.distance, it.txPower, System.currentTimeMillis())
                    }
                    .also {
                        Log.d(TAG, it.toString())
                    }
                    .forEach {
                        iBeacons.add(it)
                    }
                    .takeIf {
                        isBufferFilled()
                    }
                    ?.let {
                        createSendData(iBeacons[0].uuid, ActiveState.DWELL, sd(iBeacons))
                    }
                    ?.also {
                        iBeacons.clear()
                        addToDB(it)
                    }
        }

        try {
            mBeaconManager.startMonitoringBeaconsInRegion(mRegion)
        } catch (e: RemoteException) {
            Log.e(TAG, "Exception", e)
        }
    }

    private fun createSendData(uuid: String, state: ActiveState, sd: Double) =
            HashMap<String, Any>()
                    .let {
                        it["uuid"] = uuid
                        it["time"] = FieldValue.serverTimestamp()
                        it["state"] = state.toString()
                        it["sd"] = sd
                        it
                    }

    private fun addToDB(it: HashMap<String, Any>) {
        db.collection("ibeacon")
                .add(it)
                .addOnSuccessListener { documentReference ->
                    Log.d(TAG, "DocumentSnapshot added with ID: " + documentReference.id)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Error adding document", e)
                }
    }

    private fun isBufferFilled(): Boolean = iBeacons.size > 0 && iBeacons[iBeacons.size - 1].time - iBeacons[0].time > 10000
    private fun sd(numArray: MutableList<IBeacon>): Double {
        val doubleArray = numArray.map { it.rssi }
        val mean = doubleArray.average()
        val sd = doubleArray.fold(0.0) { accumulator, next -> accumulator + Math.pow(next - mean, 2.0) }
        return Math.sqrt(sd / doubleArray.size)
    }
}

