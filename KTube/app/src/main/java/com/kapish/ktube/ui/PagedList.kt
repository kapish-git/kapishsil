package com.kapish.ktube.ui

import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.kapish.ktube.data.Item
import com.kapish.ktube.data.Paged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Wires a RecyclerView to a [Paged] loader with pull-to-refresh and endless scrolling. */
class PagedList(
    private val owner: LifecycleOwner,
    private val recycler: RecyclerView,
    val adapter: ItemAdapter,
    private val swipe: SwipeRefreshLayout?,
    private val status: TextView,
) {
    private var loader: (suspend () -> Paged)? = null
    private var next: (suspend () -> Paged)? = null
    private var job: Job? = null
    private var loadingMore = false
    var items: List<Item> = emptyList()
        private set
    var onLoaded: ((Paged) -> Unit)? = null

    init {
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 5) loadMore()
            }
        })
        swipe?.setOnRefreshListener { reload() }
    }

    fun load(l: suspend () -> Paged) { loader = l; reload() }

    fun reload() {
        val l = loader ?: run { swipe?.isRefreshing = false; return }
        job?.cancel()
        loadingMore = false
        showStatus(null)
        swipe?.isRefreshing = true
        job = owner.lifecycleScope.launch {
            try {
                val p = l()
                items = p.items
                next = p.next
                adapter.submitList(items)
                recycler.scrollToPosition(0)
                if (items.isEmpty()) showStatus("Nothing here")
                onLoaded?.invoke(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                items = emptyList(); adapter.submitList(items)
                showStatus(errorText(e))
            } finally {
                swipe?.isRefreshing = false
            }
        }
    }

    fun setStatic(list: List<Item>, empty: String) {
        job?.cancel(); loader = null; next = null
        items = list
        adapter.submitList(list)
        showStatus(if (list.isEmpty()) empty else null)
    }

    private fun loadMore() {
        val n = next ?: return
        if (loadingMore) return
        loadingMore = true
        owner.lifecycleScope.launch {
            try {
                val p = n()
                next = p.next
                items = (items + p.items).distinctBy { it.url }
                adapter.submitList(items)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                next = null
            } finally {
                loadingMore = false
            }
        }
    }

    private fun showStatus(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    companion object {
        fun errorText(e: Throwable): String {
            val name = e.javaClass.simpleName
            return when {
                name.contains("ReCaptcha") -> "YouTube is rate-limiting this network. Try again in a while."
                e is java.net.UnknownHostException -> "No internet connection"
                name.contains("ContentNotAvailable") || name.contains("Private") -> "This content isn't available"
                name.contains("AgeRestricted") -> "Age-restricted content can't be played without sign-in"
                else -> "Couldn't load (${e.message ?: name}). Pull down to retry."
            }
        }
    }
}
