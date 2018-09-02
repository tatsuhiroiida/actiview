package iida.work.actiview

import android.graphics.Color
import android.os.Bundle
import android.os.RemoteException
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Toast
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.android.synthetic.main.activity_main.*
import org.altbeacon.beacon.*
import org.altbeacon.beacon.MonitorNotifier.INSIDE


class MainActivity : AppCompatActivity(), IActivityLifeCycle, BeaconConsumer {

    private val db = FirebaseFirestore.getInstance()
    private val TAG: String
        get() = MainActivity::class.java.simpleName

    private val TARGET_UUID: String = "2edb0100-022a-468c-a7cc-d3e066206d59"

    private val name = "かずとし"
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
        startListenSnapshot()
    }

    private fun startListenSnapshot() {
        db.collection("ibeacon")
                .orderBy("time", Query.Direction.ASCENDING)
                .addSnapshotListener { snapshot, e ->

                    if (e != null) {
                        Log.d(TAG, "Listen failed: $e")
                        return@addSnapshotListener
                    }
                    for (doc in snapshot.documentChanges) {
                        when (doc.type) {
                            DocumentChange.Type.ADDED -> {
                                Log.d(TAG, "ADDED: " + doc.document.data)
                                updateUI(doc.document.data)
                            }
                            else -> {
                                Log.d(TAG, "${doc.type}: " + doc.document.data)
                            }
                        }
                    }
                }
    }

    private fun updateUI(data: Map<String, Any>) {
        if (!data.containsKey("state")) return
        when (ActiveState.valueOf(data["state"].toString())) {
            ActiveState.ENTER -> {
                Toast.makeText(this, "${name}が家に帰りました。", Toast.LENGTH_SHORT).show()
                imageView.setBackgroundColor(Color.GREEN)
                textViewState.text = "いま帰宅しました"
            }
            ActiveState.DWELL -> {
                draw(data["sd"].toString().toDouble())
            }
            ActiveState.EXIT -> {
                Toast.makeText(this, "${name}が外出しました。", Toast.LENGTH_SHORT).show()
                imageView.setBackgroundColor(Color.GRAY)
                textViewState.text = "外出中"
            }
        }
    }

    private fun draw(sd: Double) {
        val activity = Math.ceil(sd).toInt()
        when (activity) {
            in Int.MIN_VALUE..-1 -> Pair(Color.GRAY, "外出中")
            in 0..4 -> Pair(Color.BLUE, "お休み中")
            in 5..7 -> Pair(Color.GREEN, "ゆっくり活動中")
            in 8..10 -> Pair(Color.MAGENTA, "元気に活動中!")
            else -> Pair(Color.RED, "超元気!!!!")
        }
                .let {
                    imageView.setBackgroundColor(it.first)
                    textViewState.text = it.second
                }
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
                        .also {
                            iBeacons.clear()
                            addToDB(it)
                        }

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

