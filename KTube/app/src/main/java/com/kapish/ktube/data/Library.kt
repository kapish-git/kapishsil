package com.kapish.ktube.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** Local (on-device) history, liked items, subscriptions and settings. No account needed. */
object Library {
    private lateinit var prefs: SharedPreferences

    private val _history = MutableStateFlow<List<Item>>(emptyList())
    private val _liked = MutableStateFlow<List<Item>>(emptyList())
    private val _subs = MutableStateFlow<List<Item>>(emptyList())
    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<Item>> = _history
    val liked: StateFlow<List<Item>> = _liked
    val subscriptions: StateFlow<List<Item>> = _subs
    val searches: StateFlow<List<String>> = _searches

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
        _history.value = load("history")
        _liked.value = load("liked")
        _subs.value = load("subs")
        _searches.value = runCatching {
            val a = JSONArray(prefs.getString("searches", "[]")); List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun load(key: String): List<Item> = runCatching {
        val a = JSONArray(prefs.getString(key, "[]"))
        List(a.length()) { Item.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Item>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    fun addHistory(item: Item) {
        val l = (listOf(item) + _history.value.filter { it.url != item.url }).take(300)
        _history.value = l; save("history", l)
    }

    fun clearHistory() { _history.value = emptyList(); save("history", emptyList()) }

    fun isLiked(url: String) = _liked.value.any { it.url == url }
    fun toggleLike(item: Item): Boolean {
        val liked = isLiked(item.url)
        val l = if (liked) _liked.value.filter { it.url != item.url } else listOf(item) + _liked.value
        _liked.value = l; save("liked", l)
        return !liked
    }

    fun isSubscribed(url: String) = _subs.value.any { it.url == url }
    fun toggleSubscribe(channel: Item): Boolean {
        val s = isSubscribed(channel.url)
        val l = if (s) _subs.value.filter { it.url != channel.url } else listOf(channel) + _subs.value
        _subs.value = l; save("subs", l)
        return !s
    }

    fun addSearch(q: String) {
        val l = (listOf(q) + _searches.value.filter { !it.equals(q, true) }).take(30)
        _searches.value = l
        prefs.edit().putString("searches", JSONArray(l).toString()).apply()
    }

    // Settings
    var preferredHeight: Int
        get() = prefs.getInt("height", 720)
        set(v) = prefs.edit().putInt("height", v).apply()
    var autoplay: Boolean
        get() = prefs.getBoolean("autoplay", true)
        set(v) = prefs.edit().putBoolean("autoplay", v).apply()
}
