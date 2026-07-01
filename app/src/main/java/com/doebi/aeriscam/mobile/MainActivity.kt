package com.doebi.aeriscam.mobile

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

import android.os.Build
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

private const val MAX_INVALID_CHALLENGE_ATTEMPTS = 5

class MainActivity : ComponentActivity() {

    private lateinit var scanner: GmsBarcodeScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    var rawQrText by remember { mutableStateOf("") }
                    var challengeCode by remember { mutableStateOf("") }
                    var invalidChallengeAttempts by remember { mutableStateOf(0) }
                    var lastInvalidChallengeCode by remember { mutableStateOf("") }
                    var pairingRequestStarted by remember { mutableStateOf(false) }
                    var sessionToken by remember { mutableStateOf("") }
                    var cameras by remember { mutableStateOf<List<MobileCamera>>(emptyList()) }
                    var selectedCamera by remember { mutableStateOf<MobileCamera?>(null) }
                    var statusText by remember { mutableStateOf("Not paired. Scan the AerisCam Desktop QR code.") }
                    var errorText by remember { mutableStateOf("") }

                    AerisCamMobilePairingScreen(
                        rawQrText = rawQrText,
                        challengeCode = challengeCode,
                        sessionToken = sessionToken,
                        cameras = cameras,
                        selectedCamera = selectedCamera,
                        statusText = statusText,
                        errorText = errorText,
                        onChallengeCodeChange = { newValue ->
                            val sanitizedCode = newValue
                                .filter { it.isDigit() }
                                .take(6)

                            challengeCode = sanitizedCode

                            val pairingInfo = parsePairingQrInfo(rawQrText)

                            when {
                                pairingInfo == null -> {
                                    pairingRequestStarted = false
                                    statusText = "Scan a valid AerisCam Desktop QR first."
                                }

                                sanitizedCode.length < 6 -> {
                                    pairingRequestStarted = false
                                    lastInvalidChallengeCode = ""
                                    statusText = "Enter the 6-digit code shown on AerisCam Desktop."
                                }

                                !isChallengeCodeValid(pairingInfo, sanitizedCode) -> {
                                    pairingRequestStarted = false
                                    sessionToken = ""
                                    cameras = emptyList()
                                    selectedCamera = null
                                    errorText = ""

                                    val newAttemptCount = if (sanitizedCode != lastInvalidChallengeCode) {
                                        lastInvalidChallengeCode = sanitizedCode
                                        invalidChallengeAttempts + 1
                                    } else {
                                        invalidChallengeAttempts
                                    }

                                    invalidChallengeAttempts = newAttemptCount

                                    if (newAttemptCount >= MAX_INVALID_CHALLENGE_ATTEMPTS) {
                                        rawQrText = ""
                                        challengeCode = ""
                                        invalidChallengeAttempts = 0
                                        lastInvalidChallengeCode = ""
                                        pairingRequestStarted = false
                                        sessionToken = ""
                                        cameras = emptyList()
                                        selectedCamera = null

                                        statusText = "Too many invalid codes. Please create and scan a new AerisCam Desktop QR."
                                    } else {
                                        val attemptsLeft = MAX_INVALID_CHALLENGE_ATTEMPTS - newAttemptCount
                                        statusText = "Code does not match. $attemptsLeft attempt(s) left before a new QR is required."
                                    }
                                }

                                !pairingRequestStarted -> {
                                    pairingRequestStarted = true
                                    errorText = ""
                                    statusText = "Code accepted locally. Waiting for desktop approval..."

                                    lifecycleScope.launch {
                                        try {
                                            val result = sendPairingConfirmRequest(
                                                pairingInfo = pairingInfo,
                                                challengeCode = sanitizedCode
                                            )

                                            if (result.status == "paired") {
                                                invalidChallengeAttempts = 0
                                                lastInvalidChallengeCode = ""
                                                sessionToken = result.sessionToken
                                                statusText = "Pairing successful. Loading cameras..."

                                                cameras = fetchCameraList(
                                                    bridgeUrl = pairingInfo.bridgeUrl,
                                                    sessionToken = result.sessionToken
                                                )

                                                statusText = "Pairing successful. Loaded ${cameras.size} camera(s)."
                                            } else {
                                                statusText = result.message.ifBlank { "Pairing response: ${result.status}" }
                                            }

                                        } catch (exception: Exception) {
                                            pairingRequestStarted = false

                                            val message = exception.message ?: exception::class.java.simpleName

                                            if (message.contains("HTTP 429")) {
                                                rawQrText = ""
                                                challengeCode = ""
                                                invalidChallengeAttempts = 0
                                                lastInvalidChallengeCode = ""
                                                sessionToken = ""
                                                cameras = emptyList()
                                                selectedCamera = null
                                                statusText = "Too many invalid pairing attempts. Please create and scan a new AerisCam Desktop QR."
                                                errorText = ""
                                            } else {
                                                statusText = "Pairing request failed."
                                                errorText = message
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onScanQrClick = {
                            errorText = ""
                            statusText = "Opening QR scanner..."

                            startQrScan(
                                onRawValue = { scannedText ->
                                    rawQrText = scannedText
                                    challengeCode = ""
                                    invalidChallengeAttempts = 0
                                    lastInvalidChallengeCode = ""
                                    pairingRequestStarted = false
                                    sessionToken = ""
                                    cameras = emptyList()
                                    selectedCamera = null

                                    val pairingInfo = parsePairingQrInfo(scannedText)

                                    statusText = if (pairingInfo == null) {
                                        "QR scanned, but it does not look like AerisCam pairing JSON."
                                    } else {
                                        "AerisCam QR scanned. Enter the 6-digit code shown on desktop."
                                    }
                                },
                                onCanceled = {
                                    statusText = "QR scan cancelled."
                                },
                                onFailure = { exception ->
                                    statusText = "QR scan failed."
                                    errorText = exception.message ?: exception::class.java.simpleName
                                }
                            )
                        },
                        onCameraClick = { camera ->
                            selectedCamera = camera
                        },
                        onBackToCameraListClick = {
                            selectedCamera = null
                        },
                    )
                }
            }
        }
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
    statusText: String,
    errorText: String,
    onChallengeCodeChange: (String) -> Unit,
    onScanQrClick: () -> Unit,
    onCameraClick: (MobileCamera) -> Unit,
    onBackToCameraListClick: () -> Unit
){
    val pairingInfo = parsePairingQrInfo(rawQrText)

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
                    onBackClick = onBackToCameraListClick
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
    onBackClick: () -> Unit
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
                    modifier = Modifier.fillMaxWidth(),
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