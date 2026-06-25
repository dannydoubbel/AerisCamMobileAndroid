package com.doebi.aeriscam.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BridgeStatus(
    val app: String,
    val bridge: String,
    val version: String
)

data class CameraListResponse(
    val cameras: List<BridgeCamera>
)

data class BridgeCamera(
    val id: String,
    val name: String,
    val thumbnailUrl: String,
    val qualities: List<String>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AerisCamMobileScreen()
                }
            }
        }
    }
}

@Composable
fun AerisCamMobileScreen() {
    val scope = rememberCoroutineScope()

    var bridgeBaseUrl by remember { mutableStateOf("http://10.0.2.2:18080") }
    var statusText by remember { mutableStateOf("Not checked yet") }
    var errorText by remember { mutableStateOf("") }
    var cameras by remember { mutableStateOf<List<BridgeCamera>>(emptyList()) }
    var selectedCameraName by remember { mutableStateOf("") }
    var thumbnailBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

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

        OutlinedTextField(
            value = bridgeBaseUrl,
            onValueChange = { bridgeBaseUrl = it },
            label = { Text("Bridge base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        errorText = ""
                        statusText = "Checking bridge..."

                        try {
                            val status = fetchBridgeStatus(bridgeBaseUrl)
                            statusText = "Bridge ${status.bridge} - ${status.app} ${status.version}"
                        } catch (e: Exception) {
                            statusText = "Bridge check failed"
                            errorText = e.message ?: "Unknown error"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text("Check bridge status")
            }

            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        errorText = ""
                        statusText = "Loading cameras..."

                        try {
                            val response = fetchCameras(bridgeBaseUrl)
                            cameras = response.cameras
                            statusText = "Loaded ${response.cameras.size} camera(s)"
                        } catch (e: Exception) {
                            statusText = "Camera load failed"
                            errorText = e.message ?: "Unknown error"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text("Load cameras")
            }
        }

        Text(text = statusText)

        if (errorText.isNotBlank()) {
            Text(
                text = errorText,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (cameras.isNotEmpty()) {
            Text(
                text = "Cameras",
                style = MaterialTheme.typography.titleMedium
            )

            cameras.forEach { camera ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                busy = true
                                errorText = ""
                                selectedCameraName = camera.name
                                statusText = "Loading thumbnail for ${camera.name}..."

                                try {
                                    thumbnailBitmap = fetchCameraThumbnail(
                                        bridgeBaseUrl = bridgeBaseUrl,
                                        camera = camera
                                    )
                                    statusText = "Thumbnail loaded for ${camera.name}"
                                } catch (e: Exception) {
                                    thumbnailBitmap = null
                                    statusText = "Thumbnail load failed"
                                    errorText = e.message ?: "Unknown error"
                                } finally {
                                    busy = false
                                }
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = camera.name,
                            style = MaterialTheme.typography.titleSmall
                        )

                        Text(
                            text = "ID: ${camera.id} | Qualities: ${camera.qualities.joinToString()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedCameraName.isNotBlank()) {
            Text(
                text = "Selected: $selectedCameraName",
                style = MaterialTheme.typography.titleMedium
            )
        }

        thumbnailBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Camera thumbnail",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

private suspend fun fetchBridgeStatus(baseUrl: String): BridgeStatus {
    val jsonText = httpGetText("${normaliseBaseUrl(baseUrl)}/api/status")
    val root = JSONObject(jsonText)

    return BridgeStatus(
        app = root.optString("app", "unknown"),
        bridge = root.optString("bridge", "unknown"),
        version = root.optString("version", "unknown")
    )
}

private suspend fun fetchCameras(baseUrl: String): CameraListResponse {
    val jsonText = httpGetText("${normaliseBaseUrl(baseUrl)}/api/cameras")
    val root = JSONObject(jsonText)
    val cameraArray = root.optJSONArray("cameras")

    val cameras = mutableListOf<BridgeCamera>()

    if (cameraArray != null) {
        for (i in 0 until cameraArray.length()) {
            val cameraJson = cameraArray.getJSONObject(i)
            val qualitiesJson = cameraJson.optJSONArray("qualities")
            val qualities = mutableListOf<String>()

            if (qualitiesJson != null) {
                for (q in 0 until qualitiesJson.length()) {
                    qualities.add(qualitiesJson.optString(q))
                }
            }

            cameras.add(
                BridgeCamera(
                    id = cameraJson.optString("id"),
                    name = cameraJson.optString("name"),
                    thumbnailUrl = cameraJson.optString("thumbnailUrl"),
                    qualities = qualities
                )
            )
        }
    }

    return CameraListResponse(cameras = cameras)
}

private suspend fun fetchCameraThumbnail(
    bridgeBaseUrl: String,
    camera: BridgeCamera
): Bitmap {
    val thumbnailUrl = buildThumbnailUrl(
        bridgeBaseUrl = bridgeBaseUrl,
        camera = camera
    )

    val bytes = httpGetBytes(thumbnailUrl)

    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("Could not decode JPEG thumbnail")
}

private fun buildThumbnailUrl(
    bridgeBaseUrl: String,
    camera: BridgeCamera
): String {
    val thumbnailUrlFromBridge = camera.thumbnailUrl.trim()

    if (thumbnailUrlFromBridge.startsWith("http://") || thumbnailUrlFromBridge.startsWith("https://")) {
        return thumbnailUrlFromBridge
    }

    if (thumbnailUrlFromBridge.isNotBlank()) {
        return "${normaliseBaseUrl(bridgeBaseUrl)}/${thumbnailUrlFromBridge.trimStart('/')}"
    }

    return "${normaliseBaseUrl(bridgeBaseUrl)}/api/cameras/${camera.id}/thumbnail"
}

private suspend fun httpGetText(url: String): String {
    return httpGetBytes(url).decodeToString()
}

private suspend fun httpGetBytes(url: String): ByteArray {
    return withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 3_000
            connection.readTimeout = 8_000

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)

            if (responseCode !in 200..299) {
                val body = bytes.decodeToString()
                error("HTTP $responseCode from $url: $body")
            }

            bytes
        } finally {
            connection.disconnect()
        }
    }
}

private fun normaliseBaseUrl(raw: String): String {
    return raw.trim().trimEnd('/')
}