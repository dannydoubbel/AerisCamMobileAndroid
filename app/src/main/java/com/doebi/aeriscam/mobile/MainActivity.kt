package com.doebi.aeriscam.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject

data class PairingQrInfo(
    val type: String,
    val version: Int,
    val host: String,
    val port: Int,
    val bridgeUrl: String,
    val pairingId: String,
    val expiresAt: String,
    val hasObfuscatedChallenge: Boolean,
    val hasPairingToken: Boolean
)

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
                    var statusText by remember { mutableStateOf("Not paired. Scan the AerisCam Desktop QR code.") }
                    var errorText by remember { mutableStateOf("") }

                    AerisCamMobilePairingScreen(
                        rawQrText = rawQrText,
                        challengeCode = challengeCode,
                        statusText = statusText,
                        errorText = errorText,
                        onChallengeCodeChange = { newValue ->
                            challengeCode = newValue
                                .filter { it.isDigit() }
                                .take(6)
                        },
                        onScanQrClick = {
                            errorText = ""
                            statusText = "Opening QR scanner..."

                            startQrScan(
                                onRawValue = { scannedText ->
                                    rawQrText = scannedText
                                    challengeCode = ""

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
                        }
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
    statusText: String,
    errorText: String,
    onChallengeCodeChange: (String) -> Unit,
    onScanQrClick: () -> Unit
) {
    val pairingInfo = parsePairingQrInfo(rawQrText)
    val prettyJson = formatJsonForDisplay(rawQrText)

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

        Text(
            text = "QR pairing test",
            style = MaterialTheme.typography.titleMedium
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

        if (pairingInfo != null) {
            PairingSummaryCard(pairingInfo)
        }

        if (rawQrText.isNotBlank()) {
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
                    challengeCode.length < 6 -> "Enter the 6-digit code shown on AerisCam Desktop."
                    else -> "Code entered locally. No LAN communication yet."
                }
            )

            Text(
                text = "Decoded QR JSON",
                style = MaterialTheme.typography.titleMedium
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                SelectionContainer {
                    Text(
                        text = prettyJson,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
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
                text = "Pairing QR detected",
                style = MaterialTheme.typography.titleSmall
            )

            Text("Type: ${pairingInfo.type}")
            Text("Version: ${pairingInfo.version}")
            Text("Host: ${pairingInfo.host}")
            Text("Port: ${pairingInfo.port}")
            Text("Bridge URL: ${pairingInfo.bridgeUrl}")
            Text("Pairing ID: ${pairingInfo.pairingId}")
            Text("Expires at: ${pairingInfo.expiresAt}")
            Text("Obfuscated challenge present: ${yesNo(pairingInfo.hasObfuscatedChallenge)}")
            Text("Pairing token present: ${yesNo(pairingInfo.hasPairingToken)}")
        }
    }
}

private fun parsePairingQrInfo(rawQrText: String): PairingQrInfo? {
    if (rawQrText.isBlank()) {
        return null
    }

    return try {
        val root = JSONObject(rawQrText)

        PairingQrInfo(
            type = root.optString("type", ""),
            version = root.optInt("version", 0),
            host = root.optString("host", ""),
            port = root.optInt("port", 0),
            bridgeUrl = root.optString("bridgeUrl", ""),
            pairingId = root.optString("pairingId", ""),
            expiresAt = root.optString("expiresAt", ""),
            hasObfuscatedChallenge = root.optString("pairingChallengeObfuscated", "").isNotBlank(),
            hasPairingToken = root.optString("pairingToken", "").isNotBlank()
        )

    } catch (_: Exception) {
        null
    }
}

private fun formatJsonForDisplay(rawQrText: String): String {
    if (rawQrText.isBlank()) {
        return ""
    }

    return try {
        JSONObject(rawQrText).toString(2)
    } catch (_: Exception) {
        rawQrText
    }
}

private fun yesNo(value: Boolean): String {
    return if (value) "yes" else "no"
}