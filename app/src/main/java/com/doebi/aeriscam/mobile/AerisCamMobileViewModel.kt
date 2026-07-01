package com.doebi.aeriscam.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

data class PairingQrInfo(
    val type: String,
    val version: Int,
    val host: String,
    val port: Int,
    val bridgeUrl: String,
    val pairingId: String,
    val pairingToken: String,
    val challengeSalt: String,
    val challengeVerifier: String,
    val challengeVerifierAlgorithm: String,
    val expiresAt: String
)

data class PairingConfirmResult(
    val status: String,
    val message: String,
    val sessionToken: String,
    val sessionExpiresInSeconds: Int
)

data class MobileCamera(
    val id: String,
    val name: String,
    val thumbnailUrl: String,
    val qualities: List<String>
)

const val MAX_INVALID_CHALLENGE_ATTEMPTS = 5

class AerisCamMobileViewModel : ViewModel() {

    var rawQrText by mutableStateOf("")
    var challengeCode by mutableStateOf("")
    var invalidChallengeAttempts by mutableStateOf(0)
    var lastInvalidChallengeCode by mutableStateOf("")
    var pairingRequestStarted by mutableStateOf(false)
    var sessionToken by mutableStateOf("")
    var cameras by mutableStateOf<List<MobileCamera>>(emptyList())
    var selectedCamera by mutableStateOf<MobileCamera?>(null)
    var isViewerFullScreen by mutableStateOf(false)
    var statusText by mutableStateOf("Not paired. Scan the AerisCam Desktop QR code.")
    var errorText by mutableStateOf("")

    fun resetForScannedQr(scannedText: String) {
        rawQrText = scannedText
        challengeCode = ""
        invalidChallengeAttempts = 0
        lastInvalidChallengeCode = ""
        pairingRequestStarted = false
        clearSessionAndViewerState()
        errorText = ""
    }

    fun clearSessionAndViewerState() {
        sessionToken = ""
        cameras = emptyList()
        selectedCamera = null
        isViewerFullScreen = false
    }

    fun clearPairingChallengeAndSession() {
        rawQrText = ""
        challengeCode = ""
        invalidChallengeAttempts = 0
        lastInvalidChallengeCode = ""
        pairingRequestStarted = false
        clearSessionAndViewerState()
        errorText = ""
    }
}
