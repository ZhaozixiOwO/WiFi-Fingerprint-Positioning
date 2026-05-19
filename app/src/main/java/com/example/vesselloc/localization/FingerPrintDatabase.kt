import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FingerprintDatabase {
    val fingerprints = mutableListOf<Fingerprint>()

    private var appContext: Context? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        loadFromFile()
    }

    fun add(fp: Fingerprint) {
        fingerprints.add(fp)
        saveToFile()
    }

    fun removeAt(index: Int) {
        if (index in fingerprints.indices) {
            fingerprints.removeAt(index)
            saveToFile()
        }
    }

    fun clear() {
        fingerprints.clear()
        saveToFile()
    }

    fun size(): Int = fingerprints.size

    private fun saveFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, "fingerprints.json")
    }

    private fun saveToFile() {
        val file = saveFile() ?: return
        val arr = JSONArray()
        for (fp in fingerprints) {
            val obj = JSONObject()
            obj.put("x", fp.x.toDouble())
            obj.put("y", fp.y.toDouble())
            val rssi = JSONObject()
            for ((bssid, level) in fp.rssiMap) {
                rssi.put(bssid, level)
            }
            obj.put("rssi", rssi)
            arr.put(obj)
        }
        file.writeText(arr.toString())
    }

    private fun loadFromFile() {
        val file = saveFile() ?: return
        if (!file.exists()) return
        val arr = JSONArray(file.readText())
        fingerprints.clear()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val x = obj.getDouble("x").toFloat()
            val y = obj.getDouble("y").toFloat()
            val rssiObj = obj.getJSONObject("rssi")
            val rssiMap = mutableMapOf<String, Int>()
            for (key in rssiObj.keys()) {
                rssiMap[key] = rssiObj.getInt(key)
            }
            fingerprints.add(Fingerprint(x, y, rssiMap))
        }
    }
}
