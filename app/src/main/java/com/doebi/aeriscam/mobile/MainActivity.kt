package com.doebi.aeriscam.mobile

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MainActivity : ComponentActivity() {

    private lateinit var scanner: GmsBarcodeScanner
    private lateinit var mobileViewModel: AerisCamMobileViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mobileViewModel = ViewModelProvider(this)[AerisCamMobileViewModel::class.java]

        val scannerOptions = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        scanner = GmsBarcodeScanning.getClient(this, scannerOptions)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val pairingInfo = parsePairingQrInfo(mobileViewModel.rawQrText)

                    androidx.compose.runtime.LaunchedEffect(
                        mobileViewModel.rawQrText,
                        mobileViewModel.sessionToken
                    ) {
                        if (mobileViewModel.sessionToken.isNotBlank()
                            && mobileViewModel.cameras.isEmpty()
                            && pairingInfo != null
                        ) {
                            try {
                                mobileViewModel.statusText = "Restoring camera list..."

                                mobileViewModel.cameras = fetchCameraList(
                                    bridgeUrl = pairingInfo.bridgeUrl,
                                    sessionToken = mobileViewModel.sessionToken
                                )

                                mobileViewModel.statusText =
                                    "Pairing successful. Loaded ${mobileViewModel.cameras.size} camera(s)."

                            } catch (exception: Exception) {
                                mobileViewModel.statusText = "Could not restore camera list."
                                mobileViewModel.errorText = exception.message
                                    ?: exception::class.java.simpleName
                            }
                        }
                    }

                    AerisCamMobilePairingScreen(
                        rawQrText = mobileViewModel.rawQrText,
                        challengeCode = mobileViewModel.challengeCode,
                        sessionToken = mobileViewModel.sessionToken,
                        cameras = mobileViewModel.cameras,
                        selectedCamera = mobileViewModel.selectedCamera,
                        isViewerFullScreen = mobileViewModel.isViewerFullScreen,
                        statusText = mobileViewModel.statusText,
                        errorText = mobileViewModel.errorText,
                        onChallengeCodeChange = { newValue ->
                            handleChallengeCodeChange(newValue)
                        },
                        onScanQrClick = {
                            handleScanQrClick()
                        },
                        onCameraClick = { camera ->
                            mobileViewModel.selectedCamera = camera
                            mobileViewModel.isViewerFullScreen = false
                        },
                        onBackToCameraListClick = {
                            mobileViewModel.selectedCamera = null
                            mobileViewModel.isViewerFullScreen = false
                        },
                        onToggleViewerFullScreen = {
                            mobileViewModel.isViewerFullScreen =
                                !mobileViewModel.isViewerFullScreen
                        },
                        onExitViewerFullScreen = {
                            mobileViewModel.isViewerFullScreen = false
                        },
                    )
                }
            }
        }
    }

    private fun handleChallengeCodeChange(newValue: String) {
        val sanitizedCode = newValue
            .filter { it.isDigit() }
            .take(6)

        mobileViewModel.challengeCode = sanitizedCode

        val pairingInfo = parsePairingQrInfo(mobileViewModel.rawQrText)

        when {
            pairingInfo == null -> {
                mobileViewModel.pairingRequestStarted = false
                mobileViewModel.statusText = "Scan a valid AerisCam Desktop QR first."
            }

            sanitizedCode.length < 6 -> {
                mobileViewModel.pairingRequestStarted = false
                mobileViewModel.lastInvalidChallengeCode = ""
                mobileViewModel.statusText = "Enter the 6-digit code shown on AerisCam Desktop."
            }

            !isChallengeCodeValid(pairingInfo, sanitizedCode) -> {
                mobileViewModel.pairingRequestStarted = false
                mobileViewModel.clearSessionAndViewerState()
                mobileViewModel.errorText = ""

                val newAttemptCount = if (sanitizedCode != mobileViewModel.lastInvalidChallengeCode) {
                    mobileViewModel.lastInvalidChallengeCode = sanitizedCode
                    mobileViewModel.invalidChallengeAttempts + 1
                } else {
                    mobileViewModel.invalidChallengeAttempts
                }

                mobileViewModel.invalidChallengeAttempts = newAttemptCount

                if (newAttemptCount >= MAX_INVALID_CHALLENGE_ATTEMPTS) {
                    mobileViewModel.clearPairingChallengeAndSession()
                    mobileViewModel.statusText =
                        "Too many invalid codes. Please create and scan a new AerisCam Desktop QR."
                } else {
                    val attemptsLeft = MAX_INVALID_CHALLENGE_ATTEMPTS - newAttemptCount
                    mobileViewModel.statusText =
                        "Code does not match. $attemptsLeft attempt(s) left before a new QR is required."
                }
            }

            !mobileViewModel.pairingRequestStarted -> {
                val validPairingInfo = pairingInfo

                mobileViewModel.pairingRequestStarted = true
                mobileViewModel.errorText = ""
                mobileViewModel.statusText =
                    "Code accepted locally. Waiting for desktop approval..."

                lifecycleScope.launch {
                    try {
                        val result = sendPairingConfirmRequest(
                            pairingInfo = validPairingInfo,
                            challengeCode = sanitizedCode
                        )

                        if (result.status == "paired") {
                            mobileViewModel.invalidChallengeAttempts = 0
                            mobileViewModel.lastInvalidChallengeCode = ""
                            mobileViewModel.sessionToken = result.sessionToken
                            mobileViewModel.statusText = "Pairing successful. Loading cameras..."

                            mobileViewModel.cameras = fetchCameraList(
                                bridgeUrl = validPairingInfo.bridgeUrl,
                                sessionToken = result.sessionToken
                            )

                            mobileViewModel.statusText =
                                "Pairing successful. Loaded ${mobileViewModel.cameras.size} camera(s)."
                        } else {
                            mobileViewModel.statusText = result.message.ifBlank {
                                "Pairing response: ${result.status}"
                            }
                        }

                    } catch (exception: Exception) {
                        mobileViewModel.pairingRequestStarted = false

                        val message = exception.message ?: exception::class.java.simpleName

                        if (message.contains("HTTP 429")) {
                            mobileViewModel.clearPairingChallengeAndSession()
                            mobileViewModel.statusText =
                                "Too many invalid pairing attempts. Please create and scan a new AerisCam Desktop QR."
                            mobileViewModel.errorText = ""
                        } else {
                            mobileViewModel.statusText = "Pairing request failed."
                            mobileViewModel.errorText = message
                        }
                    }
                }
            }
        }
    }

    private fun handleScanQrClick() {
        mobileViewModel.errorText = ""
        mobileViewModel.statusText = "Opening QR scanner..."

        startQrScan(
            onRawValue = { scannedText ->
                mobileViewModel.resetForScannedQr(scannedText)

                val pairingInfo = parsePairingQrInfo(scannedText)

                mobileViewModel.statusText = if (pairingInfo == null) {
                    "QR scanned, but it does not look like AerisCam pairing JSON."
                } else {
                    "AerisCam QR scanned. Enter the 6-digit code shown on desktop."
                }
            },
            onCanceled = {
                mobileViewModel.statusText = "QR scan cancelled."
            },
            onFailure = { exception ->
                mobileViewModel.statusText = "QR scan failed."
                mobileViewModel.errorText = exception.message ?: exception::class.java.simpleName
            }
        )
    }

    private fun startQrScan(
        onRawValue: (String) -> Unit,
        onCanceled: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue

                if (rawValue.isNullOrBlank()) {
                    onFailure(IllegalStateException("QR code did not contain readable text."))
                    return@addOnSuccessListener
                }

                onRawValue(rawValue)
            }
            .addOnCanceledListener {
                onCanceled()
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }
}

@Composable
fun AerisCamMobilePairingScreen(
    rawQrText: String,
    challengeCode: String,
    sessionToken: String,
    cameras: List<MobileCamera>,
    selectedCamera: MobileCamera?,
    isViewerFullScreen: Boolean,
    statusText: String,
    errorText: String,
    onChallengeCodeChange: (String) -> Unit,
    onScanQrClick: () -> Unit,
    onCameraClick: (MobileCamera) -> Unit,
    onBackToCameraListClick: () -> Unit,
    onToggleViewerFullScreen: () -> Unit,
    onExitViewerFullScreen: () -> Unit
){
    val pairingInfo = parsePairingQrInfo(rawQrText)

    BackHandler(enabled = isViewerFullScreen) {
        onExitViewerFullScreen()
    }

    if (isViewerFullScreen && sessionToken.isNotBlank() && selectedCamera != null && pairingInfo != null) {
        CameraViewerCard(
            pairingInfo = pairingInfo,
            sessionToken = sessionToken,
            camera = selectedCamera,
            isFullScreen = true,
            onBackClick = onBackToCameraListClick,
            onToggleFullScreen = onToggleViewerFullScreen
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "AerisCam Mobile",
            style = MaterialTheme.typography.headlineMedium
        )



        Button(
            onClick = onScanQrClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan AerisCam QR")
        }

        Text(text = statusText)

        if (errorText.isNotBlank()) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (pairingInfo != null && sessionToken.isBlank()) {
            PairingSummaryCard(pairingInfo)
        }

        if (rawQrText.isNotBlank() && sessionToken.isBlank()) {
            OutlinedTextField(
                value = challengeCode,
                onValueChange = onChallengeCodeChange,
                label = { Text("6-digit challenge code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword
                )
            )

            Text(
                text = when {
                    challengeCode.length < 6 ->
                        "Auto-checks after 6 digits."

                    challengeCode.length == 6 && pairingInfo != null && isChallengeCodeValid(pairingInfo, challengeCode) ->
                        "Code accepted. Sending pairing request to AerisCam Desktop..."

                    challengeCode.length == 6 ->
                        "Invalid code."

                    else ->
                        ""
                }
            )
        }
        if (sessionToken.isNotBlank()) {
            if (selectedCamera != null && pairingInfo != null) {
                CameraViewerCard(
                    pairingInfo = pairingInfo,
                    sessionToken = sessionToken,
                    camera = selectedCamera,
                    isFullScreen = false,
                    onBackClick = onBackToCameraListClick,
                    onToggleFullScreen = onToggleViewerFullScreen
                )
            } else {
                CameraListCard(
                    cameras = cameras,
                    onCameraClick = onCameraClick
                )
            }
        }
    }
}

@Composable
private fun CameraListCard(
    cameras: List<MobileCamera>,
    onCameraClick: (MobileCamera) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Cameras",
                style = MaterialTheme.typography.titleMedium
            )

            if (cameras.isEmpty()) {
                Text("No cameras returned by AerisCam.")
                return@Column
            }

            cameras.forEachIndexed { index, camera ->
                Button(
                    onClick = { onCameraClick(camera) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "${index + 1}. ${camera.name}",
                            style = MaterialTheme.typography.titleSmall
                        )

                        Text(
                            text = "Open LQ viewer"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraViewerCard(
    pairingInfo: PairingQrInfo,
    sessionToken: String,
    camera: MobileCamera,
    isFullScreen: Boolean,
    onBackClick: () -> Unit,
    onToggleFullScreen: () -> Unit
) {
    var frame by remember(camera.id, sessionToken) {
        mutableStateOf<ImageBitmap?>(null)
    }

    var frameCount by remember(camera.id, sessionToken) {
        mutableStateOf(0)
    }

    var viewerStatus by remember(camera.id, sessionToken) {
        mutableStateOf("Opening LQ viewer...")
    }

    var viewerError by remember(camera.id, sessionToken) {
        mutableStateOf("")
    }

    androidx.compose.runtime.LaunchedEffect(
        pairingInfo.bridgeUrl,
        sessionToken,
        camera.id
    ) {
        var localFrameCount = 0

        while (isActive) {
            try {
                val image = fetchCameraSnapshotImage(
                    bridgeUrl = pairingInfo.bridgeUrl,
                    sessionToken = sessionToken,
                    thumbnailUrl = camera.thumbnailUrl
                )

                localFrameCount += 1
                frame = image
                frameCount = localFrameCount
                viewerError = ""
                viewerStatus = "LQ viewer active. Refreshed $localFrameCount frame(s)."

            } catch (exception: Exception) {
                viewerStatus = "Could not refresh camera image."
                viewerError = exception.message ?: exception::class.java.simpleName

                if (viewerError.contains("HTTP 401")) {
                    viewerStatus = "Session expired or no longer authorized."
                    break
                }
            }

            delay(2_000)
        }
    }

    if (isFullScreen) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                frame?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = "Fullscreen LQ camera image for ${camera.name}",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(camera.id) {
                                detectTapGestures(
                                    onDoubleTap = { onToggleFullScreen() }
                                )
                            },
                        contentScale = ContentScale.Fit
                    )
                } ?: Text(
                    text = "Waiting for first image...",
                    color = Color.White
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = camera.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Double tap to exit fullscreen",
                        color = Color.White
                    )
                }

                if (viewerError.isNotBlank()) {
                    Text(
                        text = viewerError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                    )
                }
            }
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = camera.name,
                style = MaterialTheme.typography.titleMedium
            )

            Text("Quality: LQ")
            Text(viewerStatus)

            frame?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = "LQ camera image for ${camera.name}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(camera.id) {
                            detectTapGestures(
                                onDoubleTap = { onToggleFullScreen() }
                            )
                        },
                    contentScale = ContentScale.FillWidth
                )
            } ?: Text("Waiting for first image...")

            if (viewerError.isNotBlank()) {
                Text(
                    text = viewerError,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text("Frames loaded: $frameCount")

            Button(
                onClick = onToggleFullScreen,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Fullscreen")
            }

            Button(
                onClick = onBackClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to cameras")
            }
        }
    }
}

@Composable
private fun PairingSummaryCard(pairingInfo: PairingQrInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "AerisCam QR detected",
                style = MaterialTheme.typography.titleSmall
            )

            Text("Bridge: ${pairingInfo.host}:${pairingInfo.port}")
            Text("Enter the 6-digit code shown on AerisCam Desktop.")
        }
    }
}

private fun parsePairingQrInfo(rawQrText: String): PairingQrInfo? {
    if (rawQrText.isBlank()) {
        return null
    }

    return try {
        val root = JSONObject(rawQrText)

        val type = root.optString("type", "")

        if (type != "aeriscam-mobile-pairing") {
            return null
        }

        PairingQrInfo(
            type = type,
            version = root.optInt("version", 0),
            host = root.optString("host", ""),
            port = root.optInt("port", 0),
            bridgeUrl = root.optString("bridgeUrl", ""),
            pairingId = root.optString("pairingId", ""),
            pairingToken = root.optString("pairingToken", ""),
            challengeSalt = root.optString("challengeSalt", ""),
            challengeVerifier = root.optString("challengeVerifier", ""),
            challengeVerifierAlgorithm = root.optString("challengeVerifierAlgorithm", ""),
            expiresAt = root.optString("expiresAt", "")
        )

    } catch (_: Exception) {
        null
    }
}




private fun isChallengeCodeValid(
    pairingInfo: PairingQrInfo,
    challengeCode: String
): Boolean {
    if (challengeCode.length != 6) {
        return false
    }

    if (pairingInfo.challengeVerifierAlgorithm != "SHA-256-v1") {
        return false
    }

    if (pairingInfo.pairingId.isBlank()
        || pairingInfo.challengeSalt.isBlank()
        || pairingInfo.challengeVerifier.isBlank()
    ) {
        return false
    }

    val calculatedVerifier = createChallengeVerifier(
        pairingId = pairingInfo.pairingId,
        challengeSalt = pairingInfo.challengeSalt,
        pairingCode = challengeCode
    )

    return calculatedVerifier.equals(
        pairingInfo.challengeVerifier,
        ignoreCase = true
    )
}

private fun createChallengeVerifier(
    pairingId: String,
    challengeSalt: String,
    pairingCode: String
): String {
    val verifierInput = "$pairingId:$challengeSalt:$pairingCode"

    return sha256Hex(verifierInput)
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(value.toByteArray(Charsets.UTF_8))

    return hash.joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}

private suspend fun sendPairingConfirmRequest(
    pairingInfo: PairingQrInfo,
    challengeCode: String
): PairingConfirmResult {
    val deviceNonce = createRandomToken()

    val challengeProof = createChallengeProof(
        pairingToken = pairingInfo.pairingToken,
        pairingId = pairingInfo.pairingId,
        deviceNonce = deviceNonce,
        challengeCode = challengeCode
    )

    val requestJson = JSONObject()
        .put("pairingId", pairingInfo.pairingId)
        .put("deviceName", androidDeviceName())
        .put("deviceNonce", deviceNonce)
        .put("challengeProof", challengeProof)
        .toString()

    val responseText = httpPostJson(
        url = "${normaliseBaseUrl(pairingInfo.bridgeUrl)}/api/mobile/pair/confirm",
        bearerToken = pairingInfo.pairingToken,
        jsonBody = requestJson
    )

    val root = JSONObject(responseText)

    return PairingConfirmResult(
        status = root.optString("status", "unknown"),
        message = root.optString("message", root.optString("error", "")),
        sessionToken = root.optString("sessionToken", ""),
        sessionExpiresInSeconds = root.optInt("sessionExpiresInSeconds", 0)
    )
}

private suspend fun httpPostJson(
    url: String,
    bearerToken: String,
    jsonBody: String
): String {
    return withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 65_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")

            connection.outputStream.use { outputStream ->
                outputStream.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode

            val responseBytes = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }

            val responseText = responseBytes.decodeToString()

            if (responseCode !in 200..299) {
                error("HTTP $responseCode: $responseText")
            }

            responseText

        } finally {
            connection.disconnect()
        }
    }
}

private fun createChallengeProof(
    pairingToken: String,
    pairingId: String,
    deviceNonce: String,
    challengeCode: String
): String {
    val message = "$pairingId:$deviceNonce:$challengeCode"

    return hmacSha256Hex(
        key = pairingToken,
        message = message
    )
}

private fun hmacSha256Hex(
    key: String,
    message: String
): String {
    val mac = Mac.getInstance("HmacSHA256")

    mac.init(SecretKeySpec(
        key.toByteArray(Charsets.UTF_8),
        "HmacSHA256"
    ))

    val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))

    return hash.joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}

private fun createRandomToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)

    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(bytes)
}

private fun androidDeviceName(): String {
    val manufacturer = Build.MANUFACTURER ?: ""
    val model = Build.MODEL ?: ""

    return listOf(manufacturer, model)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Android device" }
}

private fun normaliseBaseUrl(raw: String): String {
    return raw.trim().trimEnd('/')
}

private suspend fun fetchCameraList(
    bridgeUrl: String,
    sessionToken: String
): List<MobileCamera> {
    val responseText = httpGetJson(
        url = "${normaliseBaseUrl(bridgeUrl)}/api/cameras",
        bearerToken = sessionToken
    )

    val root = JSONObject(responseText)
    val camerasArray = root.optJSONArray("cameras") ?: return emptyList()

    val cameras = mutableListOf<MobileCamera>()

    for (index in 0 until camerasArray.length()) {
        val cameraObject = camerasArray.optJSONObject(index) ?: continue
        val qualitiesArray = cameraObject.optJSONArray("qualities")

        val qualities = mutableListOf<String>()

        if (qualitiesArray != null) {
            for (qualityIndex in 0 until qualitiesArray.length()) {
                val quality = qualitiesArray.optString(qualityIndex, "")

                if (quality.isNotBlank()) {
                    qualities.add(quality)
                }
            }
        }

        cameras.add(
            MobileCamera(
                id = cameraObject.optString("id", ""),
                name = cameraObject.optString("name", "Unnamed camera"),
                thumbnailUrl = cameraObject.optString("thumbnailUrl", ""),
                qualities = qualities
            )
        )
    }

    return cameras
}

private suspend fun httpGetJson(
    url: String,
    bearerToken: String
): String {
    return withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")

            val responseCode = connection.responseCode

            val responseBytes = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }

            val responseText = responseBytes.decodeToString()

            if (responseCode !in 200..299) {
                error("HTTP $responseCode: $responseText")
            }

            responseText

        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun fetchCameraSnapshotImage(
    bridgeUrl: String,
    sessionToken: String,
    thumbnailUrl: String
): ImageBitmap {
    val imageUrl = absoluteBridgeUrl(
        bridgeUrl = bridgeUrl,
        pathOrUrl = thumbnailUrl
    )

    val bytes = httpGetBytes(
        url = imageUrl,
        bearerToken = sessionToken,
        accept = "image/jpeg"
    )

    return withContext(Dispatchers.Default) {
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size
        ) ?: error("Could not decode camera JPEG image.")

        bitmap.asImageBitmap()
    }
}

private fun absoluteBridgeUrl(
    bridgeUrl: String,
    pathOrUrl: String
): String {
    val value = pathOrUrl.trim()

    if (value.startsWith("http://") || value.startsWith("https://")) {
        return value
    }

    return normaliseBaseUrl(bridgeUrl) + "/" + value.trimStart('/')
}

private suspend fun httpGetBytes(
    url: String,
    bearerToken: String,
    accept: String
): ByteArray {
    return withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 12_000
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")

            val responseCode = connection.responseCode

            val responseBytes = if (responseCode in 200..299) {
                connection.inputStream.use { it.readBytes() }
            } else {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            }

            if (responseCode !in 200..299) {
                val responseText = responseBytes.decodeToString()
                error("HTTP $responseCode: $responseText")
            }

            responseBytes

        } finally {
            connection.disconnect()
        }
    }
}