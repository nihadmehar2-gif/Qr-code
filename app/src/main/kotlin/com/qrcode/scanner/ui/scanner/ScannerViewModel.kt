package com.qrcode.scanner.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrcode.scanner.data.QrContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScannerViewModel : ViewModel() {

    private val _lastResult = MutableStateFlow<QrContent?>(null)
    val lastResult: StateFlow<QrContent?> = _lastResult.asStateFlow()

    @Volatile
    var lastRaw: String? = null

    fun publishResult(content: QrContent) {
        _lastResult.value = content
    }

    fun clear() {
        _lastResult.value = null
        lastRaw = null
    }
}
