package com.kapish.ktube.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.kapish.ktube.data.Library
import com.kapish.ktube.databinding.FragmentLibraryBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** On-device library: watch history, liked videos/songs, subscribed channels. */
class LibraryFragment : Fragment() {
    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!
    private lateinit var list: PagedList
    private var section = 0

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = ItemAdapter(
            compact = true,
            onClick = { item, pos ->
                // Songs seen in music mode were played as audio; keep it simple: liked/history play as video.
                Actions.open(requireActivity(), list.items, pos, music = false)
            },
            onMore = { item, v -> Actions.more(requireActivity(), item, v, music = false) },
        )
        list = PagedList(viewLifecycleOwner, b.list, adapter, null, b.status)
        listOf("History", "Liked", "Subscriptions").forEachIndexed { idx, label ->
            b.chips.addView(Chip(requireContext()).apply {
                text = label; isCheckable = true; isCheckedIconVisible = false
                id = View.generateViewId(); isChecked = idx == 0
                setOnClickListener { section = idx; render() }
            })
        }
        b.clear.setOnClickListener { Library.clearHistory() }
        b.playAll.setOnClickListener { Actions.open(requireActivity(), list.items, 0, music = false) }
        b.playAudio.setOnClickListener {
            com.kapish.ktube.player.PlayerManager.play(list.items, 0, audioOnly = true)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(Library.history, Library.liked, Library.subscriptions) { _, _, _ -> }.collect { render() }
            }
        }
    }

    private fun render() {
        val (items, empty) = when (section) {
            0 -> Library.history.value to "Videos you watch will show up here"
            1 -> Library.liked.value to "Use ⋮ → Save to Liked on any video or song"
            else -> Library.subscriptions.value to "Subscribe to channels to see them here and in Home"
        }
        list.setStatic(items, empty)
        b.clear.visibility = if (section == 0 && items.isNotEmpty()) View.VISIBLE else View.GONE
        val playable = section != 2 && items.isNotEmpty()
        b.playAll.visibility = if (playable) View.VISIBLE else View.GONE
        b.playAudio.visibility = if (playable) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
