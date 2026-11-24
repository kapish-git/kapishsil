package com.kapish.ktube

import android.app.Application
import com.kapish.ktube.data.Library
import com.kapish.ktube.net.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        val locale = Locale.getDefault()
        NewPipe.init(
            OkHttpDownloader.instance,
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
        Library.init(this)
        com.kapish.ktube.player.PlayerManager.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
