package iida.work.actiview

data class IBeacon(val uuid: String, val major: Int, val minor: Int, val rssi: Int, val distance: Double, val txPower: Int, val time: Long) {

}
