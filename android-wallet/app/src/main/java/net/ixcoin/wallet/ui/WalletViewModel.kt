package net.ixcoin.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ixcoin.wallet.wallet.IxcoinWalletService
import org.bitcoinj.core.Coin

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val service = IxcoinWalletService(app.applicationContext)

    val state: StateFlow<IxcoinWalletService.WalletUiState> =
        service.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            IxcoinWalletService.WalletUiState()
        )

    private val _sendResult = MutableStateFlow<SendResult?>(null)
    val sendResult: StateFlow<SendResult?> = _sendResult

    sealed interface SendResult {
        data class Sent(val txId: String) : SendResult
        data class Failed(val reason: String) : SendResult
    }

    init {
        service.start()
    }

    fun newReceiveAddress() = viewModelScope.launch(Dispatchers.IO) {
        service.freshReceiveAddress()
    }

    fun mnemonic(): List<String>? = service.mnemonic()

    fun isValidAddress(a: String) = service.isValidAddress(a)

    fun send(address: String, amount: Coin, emptyWallet: Boolean = false) =
        viewModelScope.launch {
            _sendResult.value = withContext(Dispatchers.IO) {
                try {
                    SendResult.Sent(service.send(address, amount, emptyWallet = emptyWallet))
                } catch (e: Exception) {
                    SendResult.Failed(e.message ?: e::class.java.simpleName)
                }
            }
        }

    fun clearSendResult() { _sendResult.value = null }

    override fun onCleared() {
        service.stop()
        super.onCleared()
    }
}
