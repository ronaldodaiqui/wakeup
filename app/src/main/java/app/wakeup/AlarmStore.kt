package app.wakeup

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** All alarms, saved as JSON in SharedPreferences. One instance for the whole app. */
@SuppressLint("StaticFieldLeak")
object AlarmStore {
    /** Id used for "Test" rings started from the editor. Never scheduled. */
    const val TEST_ID = 0

    private lateinit var prefs: SharedPreferences
    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        _alarms.value = load()
    }

    private fun load(): List<Alarm> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<Alarm>) {
        val sorted = list.sortedWith(compareBy({ it.hour }, { it.minute }))
        _alarms.value = sorted
        val arr = JSONArray()
        sorted.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun get(id: Int): Alarm? =
        if (id == TEST_ID) testAlarm() else _alarms.value.firstOrNull { it.id == id }

    /** Saves (adds or replaces) and returns the alarm with its id filled in. */
    fun put(alarm: Alarm): Alarm {
        val a = if (alarm.id == 0) alarm.copy(id = nextId()) else alarm
        save(_alarms.value.filter { it.id != a.id } + a)
        return a
    }

    fun delete(id: Int) {
        save(_alarms.value.filter { it.id != id })
        resetSnoozes(id)
    }

    private fun nextId(): Int {
        val n = maxOf(prefs.getInt("nextId", 1), (_alarms.value.maxOfOrNull { it.id } ?: 0) + 1)
        prefs.edit().putInt("nextId", n + 1).apply()
        return n
    }

    // --- Defaults: a new alarm starts with whatever settings you used last time ---

    fun template(): Alarm {
        val raw = prefs.getString("template", null) ?: return Alarm()
        return runCatching { Alarm.fromJson(JSONObject(raw)) }.getOrDefault(Alarm())
            .copy(id = 0, enabled = true, label = "", days = 0)
    }

    fun rememberTemplate(a: Alarm) {
        prefs.edit().putString("template", a.copy(id = 0, label = "").toJson().toString()).apply()
    }

    // --- Test ring ---

    fun setTestAlarm(a: Alarm) {
        prefs.edit().putString("test", a.copy(id = TEST_ID).toJson().toString()).commit()
    }

    private fun testAlarm(): Alarm? = prefs.getString("test", null)?.let {
        runCatching { Alarm.fromJson(JSONObject(it)).copy(id = TEST_ID) }.getOrNull()
    }

    // --- Snooze bookkeeping ---

    fun snoozesUsed(id: Int) = prefs.getInt("snoozed_$id", 0)
    fun addSnooze(id: Int) = prefs.edit().putInt("snoozed_$id", snoozesUsed(id) + 1).apply()
    fun resetSnoozes(id: Int) = prefs.edit().remove("snoozed_$id").apply()

    /** Pending snooze ring time per alarm, so the list can show it. */
    fun snoozeUntil(id: Int) = prefs.getLong("snoozeAt_$id", 0L)
    fun setSnoozeUntil(id: Int, at: Long) = prefs.edit().putLong("snoozeAt_$id", at).apply()

    // --- First-run ---

    var setupSeen: Boolean
        get() = prefs.getBoolean("setupSeen", false)
        set(v) = prefs.edit().putBoolean("setupSeen", v).apply()
}
