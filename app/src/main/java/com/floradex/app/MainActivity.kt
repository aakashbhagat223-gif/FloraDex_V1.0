package com.floradex.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

// --- DATA MODEL ---
data class PlantEntry(
    val id: String = UUID.randomUUID().toString(),
    val dexNumber: String,
    val commonName: String,
    val localName: String,
    val scientificName: String,
    val plantType: String,
    val dexDescription: String,
    val healthStatus: String,
    val healthScore: Int,
    val healthAdvice: String,
    val careRoutine: String
)

// --- MODERN POKEDEX COLOR PALETTE ---
val DexCrimson = Color(0xFFD32F2F)
val DexDarkRed = Color(0xFF8E0000)
val DexBgDark = Color(0xFF0D0F12)
val DexCardDark = Color(0xFF181C22)
val DexCyan = Color(0xFF00E5FF)
val DexGreen = Color(0xFF00E676)
val DexAmber = Color(0xFFFFEA00)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = DexBgDark)) {
                FloraDexApp(
                    onSpeak = { text ->
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "DEX_ID")
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(0.9f)
            tts?.setSpeechRate(1.05f)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun FloraDexApp(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(0) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var activePlant by remember { mutableStateOf<PlantEntry?>(null) }
    var showHealthPanel by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var apiKey by remember { mutableStateOf(getActiveApiKey(context)) }
    var showApiKeyDialog by remember { mutableStateOf(apiKey.isBlank()) }

    var balconyPlants by remember { mutableStateOf(loadBalconyPlants(context)) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            capturedBitmap = bitmap
            if (apiKey.isBlank()) {
                errorMessage = "API Key missing. Tap the Gear icon in the top-right to paste your Gemini API Key."
                showApiKeyDialog = true
                return@rememberLauncherForActivityResult
            }
            isScanning = true
            errorMessage = null
            showHealthPanel = false
            scope.launch {
                val result = analyzePlantWithGeminiRest(bitmap, balconyPlants.size + 1, apiKey)
                isScanning = false
                result.onSuccess { entry ->
                    activePlant = entry
                    onSpeak("${entry.commonName}. ${entry.scientificName}. ${entry.dexDescription}")
                }.onFailure { err ->
                    errorMessage = err.localizedMessage ?: "Scan failed. Check API key or connection."
                }
            }
        }
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentKey = apiKey,
            onDismiss = { showApiKeyDialog = false },
            onSave = { newKey ->
                val cleaned = newKey.trim()
                saveApiKey(context, cleaned)
                apiKey = cleaned
                showApiKeyDialog = false
                errorMessage = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DexBgDark)
    ) {
        PokedexHeader(
            isScanning = isScanning,
            onOpenSettings = { showApiKeyDialog = true }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DexTabButton(
                title = "DEX SCANNER",
                selected = currentTab == 0,
                modifier = Modifier.weight(1f)
            ) { currentTab = 0 }

            DexTabButton(
                title = "MY BALCONY (${balconyPlants.size})",
                selected = currentTab == 1,
                modifier = Modifier.weight(1f)
            ) { currentTab = 1 }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (currentTab == 0) {
                ScannerScreen(
                    bitmap = capturedBitmap,
                    isScanning = isScanning,
                    plant = activePlant,
                    errorMessage = errorMessage,
                    showHealthPanel = showHealthPanel,
                    onLaunchCamera = { cameraLauncher.launch() },
                    onToggleHealth = { showHealthPanel = !showHealthPanel },
                    onSpeak = onSpeak,
                    onSaveToBalcony = { plant ->
                        if (balconyPlants.none { it.commonName.equals(plant.commonName, true) }) {
                            val updated = balconyPlants + plant
                            balconyPlants = updated
                            saveBalconyPlants(context, updated)
                        }
                        currentTab = 1
                    }
                )
            } else {
                BalconyScreen(
                    plants = balconyPlants,
                    onSelectPlant = { plant ->
                        activePlant = plant
                        showHealthPanel = true
                        currentTab = 0
                    },
                    onRemovePlant = { plant ->
                        val updated = balconyPlants.filterNot { it.id == plant.id }
                        balconyPlants = updated
                        saveBalconyPlants(context, updated)
                    }
                )
            }
        }
    }
}

@Composable
fun ApiKeyDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DexCardDark,
        title = {
            Text(
                text = "POKÉDEX AI CORE KEY",
                color = DexCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                Text(
                    text = "Paste your Gemini API Key from Google AI Studio (aistudio.google.com):",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("AIzaSy...", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DexCyan,
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(text) },
                colors = ButtonDefaults.buttonColors(containerColor = DexCyan)
            ) {
                Text("SAVE KEY", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.Gray, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

@Composable
fun PokedexHeader(isScanning: Boolean, onOpenSettings: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "lens")
    val lensAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isScanning) 350 else 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(DexCrimson, DexDarkRed)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White)
                .padding(4.dp)
                .clip(CircleShape)
                .background(DexCyan.copy(alpha = lensAlpha)),
            contentAlignment = Alignment.TopStart
        ) {
            Box(
                modifier = Modifier
                    .offset(x = 7.dp, y = 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.7f))
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusLed(color = Color(0xFFFF5252))
            StatusLed(color = DexAmber)
            StatusLed(color = DexGreen)
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = if (isScanning) "SCANNING..." else "FLORADEX OS",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.width(6.dp))

        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "API Key Settings",
                tint = Color.White
            )
        }
    }
}

@Composable
fun StatusLed(color: Color) {
    Box(
        modifier = Modifier
            .size(11.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape)
    )
}

@Composable
fun DexTabButton(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DexCyan.copy(alpha = 0.18f) else DexCardDark)
            .border(
                width = 1.dp,
                color = if (selected) DexCyan else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) DexCyan else Color.Gray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
fun ScannerScreen(
    bitmap: Bitmap?,
    isScanning: Boolean,
    plant: PlantEntry?,
    errorMessage: String?,
    showHealthPanel: Boolean,
    onLaunchCamera: () -> Unit,
    onToggleHealth: () -> Unit,
    onSpeak: (String) -> Unit,
    onSaveToBalcony: (PlantEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DexCardDark)
                .border(2.dp, if (isScanning) DexCyan else Color.DarkGray, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Scanned Specimen",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.LocalFlorist,
                        contentDescription = null,
                        tint = DexCyan.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "READY FOR SPECIMEN TARGET",
                        color = Color.Gray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }

            if (isScanning) {
                PokedexLaserOverlay()
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onLaunchCamera,
            colors = ButtonDefaults.buttonColors(containerColor = DexCrimson),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "CAPTURE PLANT SPECIMEN",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = Color(0xFFFF5252),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }

        if (plant != null && !isScanning) {
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DexCardDark)
                    .border(1.dp, DexCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#${plant.dexNumber} ${plant.commonName.uppercase()}",
                        color = DexCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        onSpeak("${plant.commonName}. Local name: ${plant.localName}. ${plant.dexDescription}")
                    }) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Speak", tint = DexCyan)
                    }
                }

                Text(
                    text = "Local Name: ${plant.localName} • ${plant.scientificName}",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = DexCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "TYPE // ${plant.plantType.uppercase()}",
                            color = DexCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    val hpColor = if (plant.healthScore >= 75) DexGreen else DexAmber
                    Surface(
                        color = hpColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "HP ${plant.healthScore}/100 (${plant.healthStatus})",
                            color = hpColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                 )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = plant.dexDescription,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onToggleHealth,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Healing, contentDescription = null, tint = DexGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("HEALTH & CARE", color = DexGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }

                    Button(
                        onClick = { onSaveToBalcony(plant) },
                        colors = ButtonDefaults.buttonColors(containerColor = DexCyan),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ADD BALCONY", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                AnimatedVisibility(visible = showHealthPanel) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DexBgDark)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "DIAGNOSIS & RECOVERY ADVICE",
                            color = DexAmber,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = plant.healthAdvice, color = Color.White, fontSize = 13.sp)

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "DAILY / WEEKLY CARE PROTOCOL",
                            color = DexGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = plant.careRoutine, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun PokedexLaserOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanner")
    val scanLineY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laserY"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val yPos = size.height * scanLineY
        val step = 35.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = DexCyan.copy(alpha = 0.15f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f
            )
            x += step
        }
        drawLine(
            color = DexCyan,
            start = Offset(0f, yPos),
            end = Offset(size.width, yPos),
            strokeWidth = 6f
        )
    }
}

@Composable
fun BalconyScreen(
    plants: List<PlantEntry>,
    onSelectPlant: (PlantEntry) -> Unit,
    onRemovePlant: (PlantEntry) -> Unit
) {
    if (plants.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "NO SPECIMENS REGISTERED IN BALCONY",
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(plants, key = { it.id }) { plant ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DexCardDark)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .clickable { onSelectPlant(plant) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${plant.dexNumber} ${plant.commonName}",
                            color = DexCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Local: ${plant.localName} • HP ${plant.healthScore}/100 (${plant.healthStatus})",
                            color = if (plant.healthScore >= 75) DexGreen else DexAmber,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { onRemovePlant(plant) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color(0xFFFF5252))
                    }
                }
            }
        }
    }
}

// --- DIRECT GEMINI REST API CALL (CRASH-FREE) ---
suspend fun analyzePlantWithGeminiRest(
    bitmap: Bitmap,
    nextDexNum: Int,
    apiKey: String
): Result<PlantEntry> = withContext(Dispatchers.IO) {
    try {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val base64Image = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

        val prompt = """
            Analyze this image like a futuristic botanical Pokedex.
            (If it is not a plant, playfully classify the object as an Artificial/Household Mimic specimen).
            Respond ONLY with a valid raw JSON object (no markdown fences) using these exact keys:
            {
              "commonName": "English common name",
              "localName": "Indian local name (Hindi / Marathi) and regional name",
              "scientificName": "Latin botanical name",
              "plantType": "Elemental style type e.g. FLOWER / MEDICINAL / FOLIAGE / SYNTHETIC",
              "dexDescription": "2-sentence Pokedex style encyclopedia entry describing the specimen and its traits.",
              "healthStatus": "Thriving, Mild Stress, or Needs Attention",
              "healthScore": 85,
              "healthAdvice": "Specific visible diagnosis and how to boost its health.",
              "careRoutine": "Watering schedule, sunlight hours, soil mix, and fertilizer plan."
            }
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    }))
                })
            ))
        }

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 20000
            readTimeout = 20000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        val code = conn.responseCode
        val responseBody = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""

        if (code !in 200..299) {
            val errJson = runCatching {
                JSONObject(responseBody).getJSONObject("error").getString("message")
            }.getOrDefault("HTTP $code: $responseBody")
            return@withContext Result.failure(Exception("Gemini API Error: $errJson"))
        }

        val root = JSONObject(responseBody)
        val rawText = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val json = JSONObject(rawText)
        val formattedDexNum = nextDexNum.toString().padStart(3, '0')

        Result.success(
            PlantEntry(
                dexNumber = formattedDexNum,
                commonName = json.optString("commonName", "Unknown Flora"),
                localName = json.optString("localName", "N/A"),
                scientificName = json.optString("scientificName", "Species Incognita"),
                plantType = json.optString("plantType", "BOTANICAL"),
                dexDescription = json.optString("dexDescription", "No entry data available."),
                healthStatus = json.optString("healthStatus", "Good"),
                healthScore = json.optInt("healthScore", 80),
                healthAdvice = json.optString("healthAdvice", "Maintain regular watering and inspect leaves."),
                careRoutine = json.optString("careRoutine", "Water when topsoil is dry. Provide bright indirect light.")
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// --- API KEY & BALCONY STORAGE ---
fun getActiveApiKey(context: Context): String {
    val saved = context.getSharedPreferences("floradex_prefs", Context.MODE_PRIVATE)
        .getString("custom_api_key", "") ?: ""
    return if (saved.isNotBlank()) saved else BuildConfig.GEMINI_API_KEY
}

fun saveApiKey(context: Context, key: String) {
    context.getSharedPreferences("floradex_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("custom_api_key", key)
        .apply()
}

fun saveBalconyPlants(context: Context, plants: List<PlantEntry>) {
    val array = JSONArray()
    plants.forEach { p ->
        val obj = JSONObject().apply {
            put("id", p.id)
            put("dexNumber", p.dexNumber)
            put("commonName", p.commonName)
            put("localName", p.localName)
            put("scientificName", p.scientificName)
            put("plantType", p.plantType)
            put("dexDescription", p.dexDescription)
            put("healthStatus", p.healthStatus)
            put("healthScore", p.healthScore)
            put("healthAdvice", p.healthAdvice)
            put("careRoutine", p.careRoutine)
        }
        array.put(obj)
    }
    context.getSharedPreferences("floradex_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("balcony_roster", array.toString())
        .apply()
}

fun loadBalconyPlants(context: Context): List<PlantEntry> {
    val raw = context.getSharedPreferences("floradex_prefs", Context.MODE_PRIVATE)
        .getString("balcony_roster", null) ?: return emptyList()
    return try {
        val array = JSONArray(raw)
        List(array.length()) { i ->
            val obj = array.getJSONObject(i)
            PlantEntry(
                id = obj.optString("id"),
                dexNumber = obj.optString("dexNumber", "001"),
                commonName = obj.optString("commonName"),
                localName = obj.optString("localName"),
                scientificName = obj.optString("scientificName"),
                plantType = obj.optString("plantType"),
                dexDescription = obj.optString("dexDescription"),
                healthStatus = obj.optString("healthStatus"),
                healthScore = obj.optInt("healthScore", 80),
                healthAdvice = obj.optString("healthAdvice"),
                careRoutine = obj.optString("careRoutine")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
