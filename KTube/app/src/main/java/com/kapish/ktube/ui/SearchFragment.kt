package com.kapish.ktube.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.kapish.ktube.R
import com.kapish.ktube.data.Library
import com.kapish.ktube.data.Repo
import com.kapish.ktube.databinding.FragmentSearchBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search screen. With music = true it searches YouTube Music (songs, albums, artists…) and plays
 * results as audio, like the YT Music app; otherwise it searches regular YouTube.
 */
class SearchFragment : Fragment() {
    private var _b: FragmentSearchBinding? = null
    private val b get() = _b!!
    private lateinit var list: PagedList
    private val music get() = arguments?.getBoolean(ARG_MUSIC) == true
    private var filter = ""
    private var query = ""
    private var suggestJob: Job? = null
    private val suggestions = SuggestionAdapter { q -> b.query.setText(q); b.query.setSelection(q.length); submit() }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentSearchBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = ItemAdapter(
            compact = music,
            onClick = { _, pos -> Actions.open(requireActivity(), list.items, pos, music) },
            onMore = { item, v -> Actions.more(requireActivity(), item, v, music) },
        )
        list = PagedList(viewLifecycleOwner, b.list, adapter, b.swipe, b.status)
        b.queryLayout.hint = getString(if (music) R.string.search_music else R.string.search_youtube)

        val filters = if (music) listOf(
            // YT Music "songs"/"albums" shelves currently fail to parse in the extractor,
            // so songs come from the music-videos shelf (played as audio).
            Repo.MUSIC_VIDEOS to "Songs", Repo.MUSIC_PLAYLISTS to "Playlists", Repo.ARTISTS to "Artists",
        ) else listOf(
            Repo.ALL to "All", Repo.VIDEOS to "Videos", Repo.CHANNELS to "Channels", Repo.PLAYLISTS to "Playlists",
        )
        filter = filters.first().first
        filters.forEachIndexed { idx, (id, label) ->
            b.chips.addView(Chip(requireContext()).apply {
                text = label; isCheckable = true; isCheckedIconVisible = false
                this.id = View.generateViewId(); isChecked = idx == 0
                setOnClickListener { filter = id; if (query.isNotBlank()) runSearch() }
            })
        }

        b.suggestions.layoutManager = LinearLayoutManager(requireContext())
        b.suggestions.adapter = suggestions
        b.query.setOnEditorActionListener { _, action, ev ->
            if (action == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { submit(); true } else false
        }
        b.query.setOnFocusChangeListener { _, has -> if (has) showSuggestions(b.query.text.toString()) }
        b.query.doAfterTextChanged { if (b.query.hasFocus()) showSuggestions(it?.toString().orEmpty()) }

        if (music) {
            list.load { Repo.musicHome() }
        } else {
            b.query.requestFocus()
            showKeyboard()
        }
    }

    private fun showSuggestions(text: String) {
        suggestJob?.cancel()
        val history = Library.searches.value.filter { text.isBlank() || it.contains(text, true) }.take(5)
        suggestions.submit(history, emptyList())
        b.suggestions.visibility = View.VISIBLE
        if (text.isBlank()) return
        suggestJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(250)
            val remote = Repo.suggestions(text)
            suggestions.submit(history, remote.filter { r -> history.none { it.equals(r, true) } }.take(10))
        }
    }

    private fun submit() {
        val q = b.query.text?.toString()?.trim().orEmpty()
        if (q.isBlank()) return
        if (q.startsWith("http")) { Actions.openUrl(requireActivity(), q); return }
        query = q
        Library.addSearch(q)
        runSearch()
    }

    private fun runSearch() {
        suggestJob?.cancel()
        b.suggestions.visibility = View.GONE
        b.query.clearFocus()
        hideKeyboard()
        val q = query; val f = filter
        list.load { Repo.search(q, f) }
    }

    private fun showKeyboard() = b.query.post {
        requireContext().getSystemService(InputMethodManager::class.java)
            .showSoftInput(b.query, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() = requireContext().getSystemService(InputMethodManager::class.java)
        .hideSoftInputFromWindow(b.query.windowToken, 0)

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        private const val ARG_MUSIC = "music"
        fun newInstance(music: Boolean) = SearchFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_MUSIC, music) }
        }
    }
}

class SuggestionAdapter(private val onPick: (String) -> Unit) : RecyclerView.Adapter<SuggestionAdapter.VH>() {
    private var rows: List<Pair<String, Boolean>> = emptyList() // text, isHistory
    class VH(val t: TextView) : RecyclerView.ViewHolder(t)

    fun submit(history: List<String>, remote: List<String>) {
        rows = history.map { it to true } + remote.map { it to false }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val t = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false) as TextView
        return VH(t)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (text, hist) = rows[position]
        holder.t.text = text
        holder.t.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (hist) R.drawable.ic_history else R.drawable.ic_search, 0, 0, 0
        )
        holder.t.setOnClickListener { onPick(text) }
    }
}
