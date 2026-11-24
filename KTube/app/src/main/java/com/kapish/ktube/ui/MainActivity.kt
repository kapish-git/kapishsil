package com.kapish.ktube.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kapish.ktube.BuildConfig
import com.kapish.ktube.R
import com.kapish.ktube.data.Library
import com.kapish.ktube.databinding.ActivityMainBinding
import com.kapish.ktube.player.PlayerManager

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val tabs = mapOf(
        R.id.nav_home to "home",
        R.id.nav_music to "music",
        R.id.nav_library to "library",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        MiniPlayer(this, b.mini)
        PlayerManager.ensureService()

        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_search -> { openSearch(); true }
                R.id.action_settings -> { showSettings(); true }
                else -> false
            }
        }
        b.bottomNav.setOnItemSelectedListener { selectTab(it.itemId); true }
        b.bottomNav.setOnItemReselectedListener { closeSearch() }
        if (savedInstanceState == null) {
            selectTab(R.id.nav_home)
            handleIntent(intent)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
                    b.bottomNav.selectedItemId != R.id.nav_home -> b.bottomNav.selectedItemId = R.id.nav_home
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value }
            else -> null
        } ?: return
        Actions.openUrl(this, url)
    }

    private fun selectTab(id: Int) {
        closeSearch()
        val tag = tabs[id] ?: return
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tabs.values.forEach { t -> fm.findFragmentByTag(t)?.let { if (t == tag) tx.show(it) else tx.hide(it) } }
        if (fm.findFragmentByTag(tag) == null) {
            val f: Fragment = when (tag) {
                "home" -> HomeFragment()
                "music" -> SearchFragment.newInstance(music = true)
                else -> LibraryFragment()
            }
            tx.add(R.id.container, f, tag)
        }
        tx.commit()
        b.toolbar.title = getString(if (tag == "music") R.string.app_name_music else R.string.app_name)
        b.toolbar.menu.findItem(R.id.action_search)?.isVisible = tag != "music"
    }

    private fun openSearch() {
        supportFragmentManager.beginTransaction()
            .add(R.id.overlay, SearchFragment.newInstance(music = false), "search")
            .addToBackStack("search")
            .commit()
    }

    private fun closeSearch() {
        if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
    }

    private fun showSettings() {
        val heights = intArrayOf(1080, 720, 480, 360, 240, 144)
        val labels = heights.map { "${it}p" }.toTypedArray()
        val current = heights.indexOf(Library.preferredHeight).coerceAtLeast(1)
        MaterialAlertDialogBuilder(this)
            .setTitle("Default video quality")
            .setSingleChoiceItems(labels, current) { d, which ->
                Library.preferredHeight = heights[which]; d.dismiss()
            }
            .setNeutralButton(if (Library.autoplay) "Turn autoplay off" else "Turn autoplay on") { _, _ ->
                Library.autoplay = !Library.autoplay
                Actions.toast(this, "Autoplay ${if (Library.autoplay) "on" else "off"}")
            }
            .setPositiveButton("About") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_name)
                    .setMessage(
                        "Version ${BuildConfig.VERSION_NAME}\n\n" +
                            "Ad-free YouTube & YouTube Music player with background playback.\n" +
                            "Powered by NewPipeExtractor and ExoPlayer. No Google account; " +
                            "history, likes and subscriptions are stored only on this phone."
                    )
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            .show()
    }
}
