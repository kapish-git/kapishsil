package com.kapish.ktube.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.kapish.ktube.data.Library
import com.kapish.ktube.data.Paged
import com.kapish.ktube.data.Repo
import com.kapish.ktube.data.subscriptionFeed
import com.kapish.ktube.databinding.FragmentHomeBinding

/** Home feed: subscriptions + YouTube's public feeds (live, music, gaming, podcasts…). */
class HomeFragment : Fragment() {
    private var _b: FragmentHomeBinding? = null
    private val b get() = _b!!
    private lateinit var list: PagedList

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentHomeBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val adapter = ItemAdapter(
            onClick = { _, pos -> Actions.open(requireActivity(), list.items, pos, music = false) },
            onMore = { item, v -> Actions.more(requireActivity(), item, v, music = false) },
        )
        list = PagedList(viewLifecycleOwner, b.list, adapter, b.swipe, b.status)

        val feeds = buildList {
            if (Library.history.value.isNotEmpty()) add("foryou" to "For you")
            if (Library.subscriptions.value.isNotEmpty()) add("subs" to "Subscriptions")
            Repo.kioskIds().forEach { add(it to Repo.kioskLabel(it)) }
        }
        feeds.forEachIndexed { idx, (id, label) ->
            val chip = Chip(requireContext()).apply {
                text = label; isCheckable = true; isCheckedIconVisible = false
                this.id = View.generateViewId()
                setOnClickListener { select(id) }
            }
            b.chips.addView(chip)
            if (idx == 0) chip.isChecked = true
        }
        feeds.firstOrNull()?.let { select(it.first) }
    }

    private fun select(id: String) {
        if (id == "foryou") {
            list.load { Repo.forYou(Library.history.value) }
        } else if (id == "subs") {
            list.load { Paged("Subscriptions", Repo.subscriptionFeed(Library.subscriptions.value), null) }
        } else {
            list.load { Repo.kiosk(id) }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
