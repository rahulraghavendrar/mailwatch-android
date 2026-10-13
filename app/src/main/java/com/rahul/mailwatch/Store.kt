package com.rahul.mailwatch

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers, kept in private app storage. UI observes the StateFlows. */
object Store {
    private lateinit var prefs: SharedPreferences
    private val lock = Any()
    @Volatile private var ready = false

    private val _settings = MutableStateFlow(Settings())
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    private val _watches = MutableStateFlow<List<Watch>>(emptyList())
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    private val _chat = MutableStateFlow<List<ChatMsg>>(emptyList())
    private val _lastCheck = MutableStateFlow(0L)

    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()
    val watches: StateFlow<List<Watch>> = _watches.asStateFlow()
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()
    val chat: StateFlow<List<ChatMsg>> = _chat.asStateFlow()
    val lastCheck: StateFlow<Long> = _lastCheck.asStateFlow()

    private val seen = LinkedHashSet<String>()

    fun init(context: Context) {
        synchronized(lock) { load(context) }
    }

    private fun load(context: Context) {
        if (ready) return
        prefs = context.applicationContext.getSharedPreferences("mailwatch", Context.MODE_PRIVATE)
        _settings.value = runCatching { Settings.from(JSONObject(prefs.getString("settings", "{}")!!)) }.getOrDefault(Settings())
        _accounts.value = list("accounts") { Account.from(it) }
        _watches.value = list("watches") { Watch.from(it) }
        _alerts.value = list("alerts") { Alert.from(it) }
        _chat.value = list("chat") { ChatMsg.from(it) }
        _lastCheck.value = prefs.getLong("lastCheck", 0)
        runCatching { JSONArray(prefs.getString("seen", "[]")) }.getOrNull()?.strings()?.let { seen.addAll(it) }
        ready = true
    }

    private fun <T> list(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun save(key: String, items: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }

    fun nextId(): Long = synchronized(lock) {
        val id = prefs.getLong("nextId", 1)
        prefs.edit().putLong("nextId", id + 1).apply()
        id
    }

    // ---------------- settings
    fun updateSettings(f: (Settings) -> Settings) = synchronized(lock) {
        _settings.update(f)
        prefs.edit().putString("settings", _settings.value.toJson().toString()).apply()
    }

    // ---------------- accounts
    fun upsertAccount(a: Account) = synchronized(lock) {
        _accounts.update { list ->
            val i = list.indexOfFirst { it.provider == a.provider && it.address.equals(a.address, true) }
            if (i >= 0) list.toMutableList().also { it[i] = a.copy(id = list[i].id) } else list + a
        }
        save("accounts", _accounts.value.map { it.toJson() })
    }

    fun updateAccount(id: String, f: (Account) -> Account) = synchronized(lock) {
        _accounts.update { list -> list.map { if (it.id == id) f(it) else it } }
        save("accounts", _accounts.value.map { it.toJson() })
    }

    fun removeAccount(id: String) = synchronized(lock) {
        _accounts.update { list -> list.filterNot { it.id == id } }
        save("accounts", _accounts.value.map { it.toJson() })
    }

    fun account(id: String) = _accounts.value.firstOrNull { it.id == id }

    // ---------------- watches
    fun addWatch(w: Watch): Watch = synchronized(lock) {
        _watches.update { it + w }
        save("watches", _watches.value.map { it.toJson() })
        w
    }

    fun updateWatch(id: Long, f: (Watch) -> Watch) = synchronized(lock) {
        _watches.update { list -> list.map { if (it.id == id) f(it) else it } }
        save("watches", _watches.value.map { it.toJson() })
    }

    fun removeWatch(id: Long) = synchronized(lock) {
        _watches.update { list -> list.filterNot { it.id == id } }
        save("watches", _watches.value.map { it.toJson() })
    }

    fun activeWatches() = _watches.value.filter { it.status == "active" }

    // ---------------- alerts
    /** Returns null if we already alerted for this mail + watch. */
    fun addAlert(a: Alert): Alert? {
        synchronized(lock) {
            if (_alerts.value.any { it.watchId == a.watchId && it.msgKey == a.msgKey }) return null
            _alerts.update { (listOf(a) + it).take(200) }
            save("alerts", _alerts.value.map { it.toJson() })
            return a
        }
    }

    fun updateAlert(id: Long, f: (Alert) -> Alert) = synchronized(lock) {
        _alerts.update { list -> list.map { if (it.id == id) f(it) else it } }
        save("alerts", _alerts.value.map { it.toJson() })
    }

    fun alert(id: Long) = _alerts.value.firstOrNull { it.id == id }

    fun clearAlerts() = synchronized(lock) {
        _alerts.update { list -> list.filter { !it.acked && it.method != "test" } }
        save("alerts", _alerts.value.map { it.toJson() })
    }

    // ---------------- chat
    fun addChat(fromUser: Boolean, text: String): ChatMsg = synchronized(lock) {
        val m = ChatMsg(nextId(), fromUser, text)
        _chat.update { (it + m).takeLast(300) }
        save("chat", _chat.value.map { it.toJson() })
        m
    }

    fun clearChat() = synchronized(lock) {
        _chat.value = emptyList()
        save("chat", emptyList())
    }

    // ---------------- seen mail ids
    /** True the first time a message key is seen. */
    fun markSeen(key: String): Boolean {
        synchronized(lock) {
            if (!seen.add(key)) return false
            while (seen.size > 4000) seen.remove(seen.first())
            prefs.edit().putString("seen", JSONArray(seen.toList()).toString()).apply()
            return true
        }
    }

    fun setLastCheck(t: Long) {
        _lastCheck.value = t
        prefs.edit().putLong("lastCheck", t).apply()
    }
}
