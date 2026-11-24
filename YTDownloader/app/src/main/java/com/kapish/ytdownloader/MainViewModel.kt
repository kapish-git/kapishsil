package com.kapish.ytdownloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UiState {
    data object Idle : UiState
    data class Loading(val message: String) : UiState
    data class Loaded(val info: MediaInfo) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    fun fetch(url: String, singleVideoOnly: Boolean) {
        if (_state.value is UiState.Loading) return
        viewModelScope.launch {
            _state.value = UiState.Loading("Preparing downloader…")
            try {
                withContext(Dispatchers.IO) { Ytdl.ensureInit(getApplication()) }
                _state.value = UiState.Loading("Reading video info…")
                val info = withContext(Dispatchers.IO) { Ytdl.fetch(getApplication(), url, singleVideoOnly) }
                _state.value = UiState.Loaded(info)
            } catch (e: Throwable) {
                _state.value = UiState.Error(DownloadService.friendlyError(e))
            }
        }
    }

    fun updateYtdlp(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val status = Ytdl.update(getApplication())
                    val v = Ytdl.version(getApplication()) ?: ""
                    if (status == com.yausername.youtubedl_android.YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)
                        "yt-dlp is already up to date ($v)" else "yt-dlp updated to $v"
                }.getOrElse { "Update failed: ${it.message}" }
            }
            onDone(msg)
        }
    }
}
