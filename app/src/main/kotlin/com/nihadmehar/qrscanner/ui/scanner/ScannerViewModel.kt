package com.nihadmehar.qrscanner.ui.scanner

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Holds the most recent scan result event for the ScannerActivity's UI.
 *
 * IMPORTANT — why we use a [Channel] instead of a StateFlow here:
 *
 * The previous implementation used `StateFlow<QrContent?>` collected via
 * `repeatOnLifecycle(STARTED)`. StateFlow has two properties that combine into a
 * show-stopping bug for the scanner use-case:
 *
 *  1. StateFlow always has a value (the initial null) and re-emits the latest value
 *     to every new collector when collection starts.
 *  2. StateFlow uses **distinctUntilChanged** semantics — it only emits when the value
 *     actually changes.
 *
 * Combined with the duplicate-scan guard in ScannerActivity's processImageProxy()
 * (`if (parsed.raw != viewModel.lastRaw)`) this means:
 *
 *  - User scans QR code X → publishResult(X) → StateFlow emits X → sheet appears ✅
 *  - User dismisses sheet → StateFlow still holds X → no re-emission (same value) ✅
 *  - User scans the SAME code X again → processImageProxy() sees
 *    `parsed.raw == viewModel.lastRaw` → publishResult is NOT called → StateFlow
 *    doesn't emit → **sheet never appears** ❌ (but history is saved, because
 *    historyRepository.insert() runs every time processImageProxy is invoked)
 *
 * That matches the user's reported bug exactly: "result doesn't appear but
 * item is saved in history".
 *
 * The fix: use a **Channel** with capacity CONFLATED (so the most recent event
 * is preserved if the collector briefly pauses, but no duplicate emissions).
 * Channel.receiveAsFlow() is a *hot* flow that emits each event exactly once to
 * each collector — it does NOT replay the last value when collection restarts.
 *
 * Combined with moving the duplicate-scan guard OUT of processImageProxy and
 * INTO publishResult itself, we get correct behavior:
 *
 *  - First scan of X → publishResult(X) → channel sends X → sheet appears ✅
 *  - User dismisses sheet → channel is empty → no re-emission ✅
 *  - User scans X again → publishResult(X) is called again (no lastRaw guard
 *    anymore) → channel sends X again → sheet appears ✅
 */
class ScannerViewModel : ViewModel() {

    private val _resultEvent = Channel<QrContentEvent>(capacity = Channel.CONFLATED)
    val resultEvent: Flow<QrContentEvent> = _resultEvent.receiveAsFlow()

    /**
     * Publish a scan result so the UI can show it. Always emits — the caller may
     * pass the same content twice and the user will see the sheet both times.
     * The de-duplication that actually matters (preventing the same code from
     * being scanned 30 times per second while the sheet is open) is handled in
     * ScannerActivity by the [isPausedForResult] flag, not here.
     */
    fun publishResult(content: com.nihadmehar.qrscanner.data.QrContent) {
        _resultEvent.trySend(QrContentEvent(content))
    }

    /**
     * Single event carrying the parsed scan result. Wrapping in a data class
     * (instead of using Channel<QrContent> directly) keeps the door open for
     * attaching metadata like scan-timestamp or source (camera vs gallery)
     * without breaking the channel's identity semantics.
     */
    data class QrContentEvent(val content: com.nihadmehar.qrscanner.data.QrContent)

    override fun onCleared() {
        super.onCleared()
        _resultEvent.close()
    }
}
