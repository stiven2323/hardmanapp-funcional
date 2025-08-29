package com.hardman.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
// Bring in the DataStore API so we can refer to DataStore<Preferences>.
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale
// Random is used to generate unique IDs for recommended missions
import kotlin.random.Random
// Additional Compose utilities not auto‑imported
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale

/*
 * This file implements a simplified version of the “Sargento Hardman” fitness
 * application in Jetpack Compose. It includes a registration screen, a home
 * screen with missions, a simple chat with heuristic responses, a memory
 * minigame, a quiz, and basic settings. Voice synthesis is provided via
 * TextToSpeech and simple sound effects via ToneGenerator. DataStore is used
 * to persist preferences (profile, volumes, language, tone, intensity) and
 * missions across sessions. Room could be used for more complex data
 * persistence but is intentionally omitted here to keep the project concise.
 */

// Create a DataStore instance on the Context. This uses the Preferences API
// rather than Proto to keep the example straightforward. All preferences are
// stored in a single file named "hardman_prefs".
val Context.dataStore by preferencesDataStore(name = "hardman_prefs")

// Preference keys used in DataStore
private val KEY_NAME = stringPreferencesKey("name")
private val KEY_SURNAME = stringPreferencesKey("surname")
private val KEY_COUNTRY = stringPreferencesKey("country")
private val KEY_YEAR = stringPreferencesKey("year")
private val KEY_WEIGHT = stringPreferencesKey("weight")
private val KEY_HEIGHT = stringPreferencesKey("height")
private val KEY_GOAL = stringPreferencesKey("goal")
private val KEY_INTENSITY = stringPreferencesKey("intensity")
private val KEY_VOICE_TONE = stringPreferencesKey("voice_tone")
private val KEY_LANGUAGE = stringPreferencesKey("language")
private val KEY_VOICE_VOLUME = floatPreferencesKey("voice_volume")
private val KEY_SFX_VOLUME = floatPreferencesKey("sfx_volume")
private val KEY_XP = intPreferencesKey("xp")
private val KEY_MISSIONS = stringPreferencesKey("missions_json")

// Data class representing a mission. Missions have a title, a completion
// state, and an identifier. Identifiers are generated using the current
// timestamp when a mission is added.
data class Mission(val id: Long, val title: String, val done: Boolean = false)

// ViewModel responsible for managing the application state and interacting
// with DataStore. All UI state flows through this class. In a full
// application it would be split across multiple view models and layers.
class MainViewModel(private val dataStore: DataStore<Preferences>) : ViewModel() {
    // Flow of profile data loaded from DataStore. Use StateFlow so the UI
    // recomposes automatically when preferences change.
    val profileState: StateFlow<Profile> = dataStore.data
        .map { prefs ->
            Profile(
                nombre = prefs[KEY_NAME] ?: "",
                apellido = prefs[KEY_SURNAME] ?: "",
                pais = prefs[KEY_COUNTRY] ?: "",
                anio = prefs[KEY_YEAR] ?: "",
                peso = prefs[KEY_WEIGHT] ?: "",
                altura = prefs[KEY_HEIGHT] ?: "",
                objetivo = prefs[KEY_GOAL] ?: "bajar",
                intensidad = prefs[KEY_INTENSITY] ?: "firme",
                voz = prefs[KEY_VOICE_TONE] ?: "firme",
                idioma = prefs[KEY_LANGUAGE] ?: Locale.getDefault().toLanguageTag()
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Profile())

    // Flow of XP loaded from DataStore. When missions are completed, XP
    // increases. XP determines the user's level and rank.
    val xpState: StateFlow<Int> = dataStore.data
        .map { prefs -> prefs[KEY_XP] ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Flow of missions decoded from JSON stored in DataStore. Missions are
    // serialized to JSON for persistence; this avoids a Room dependency.
    val missionsState: StateFlow<List<Mission>> = dataStore.data
        .map { prefs ->
            val json = prefs[KEY_MISSIONS]
            if (json.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    val items = json.split(";").filter { it.isNotBlank() }
                    items.map { item ->
                        val parts = item.split("|")
                        Mission(
                            id = parts.getOrNull(0)?.toLongOrNull() ?: 0L,
                            title = parts.getOrNull(1) ?: "",
                            done = parts.getOrNull(2)?.toBoolean() ?: false
                        )
                    }
                } catch (_: Exception) {
                    emptyList<Mission>()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Volumes
    val voiceVolume: StateFlow<Float> = dataStore.data
        .map { prefs -> prefs[KEY_VOICE_VOLUME] ?: 1f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val sfxVolume: StateFlow<Float> = dataStore.data
        .map { prefs -> prefs[KEY_SFX_VOLUME] ?: 0.7f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.7f)

    /** Save a profile field to DataStore. Any of the profile properties can be
     * updated individually. When the user completes the registration form
     * this method will persist all values. */
    fun updateProfile(profile: Profile) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_NAME] = profile.nombre
                prefs[KEY_SURNAME] = profile.apellido
                prefs[KEY_COUNTRY] = profile.pais
                prefs[KEY_YEAR] = profile.anio
                prefs[KEY_WEIGHT] = profile.peso
                prefs[KEY_HEIGHT] = profile.altura
                prefs[KEY_GOAL] = profile.objetivo
                prefs[KEY_INTENSITY] = profile.intensidad
                prefs[KEY_VOICE_TONE] = profile.voz
                prefs[KEY_LANGUAGE] = profile.idioma
            }
        }
    }

    /** Update the voice and effects volumes. */
    fun updateVolumes(voice: Float, sfx: Float) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_VOICE_VOLUME] = voice
                prefs[KEY_SFX_VOLUME] = sfx
            }
        }
    }

    /** Increment XP by the specified amount. */
    fun addXp(delta: Int) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val current = prefs[KEY_XP] ?: 0
                prefs[KEY_XP] = current + delta
            }
        }
    }

    /** Save the list of missions as a compact string. Each mission is encoded
     * as "id|title|done" and missions are separated by semicolons. */
    private suspend fun saveMissions(list: List<Mission>) {
        val encoded = list.joinToString(";") { m ->
            "${m.id}|${m.title}|${m.done}"
        }
        dataStore.edit { prefs ->
            prefs[KEY_MISSIONS] = encoded
        }
    }

    /** Add a new mission at the top of the list. */
    fun addMission(title: String) {
        viewModelScope.launch {
            val current = missionsState.value
            val newMission = Mission(id = System.currentTimeMillis(), title = title, done = false)
            saveMissions(listOf(newMission) + current)
        }
    }

    /** Toggle the completion state of a mission. If marked done, award XP. */
    fun toggleMission(id: Long) {
        viewModelScope.launch {
            val updated = missionsState.value.map { m ->
                if (m.id == id) {
                    val toggled = m.copy(done = !m.done)
                    if (!m.done) {
                        addXp(10)
                    }
                    toggled
                } else {
                    m
                }
            }
            saveMissions(updated)
        }
    }

    /** Recommend missions based on the user's goal (bajar/musculo/cuerpo) and
     * the current time of day. The missions are added to the top of the list. */
    fun recommendMissions() {
        viewModelScope.launch {
            val p = profileState.value
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val list = mutableListOf<String>()
            when (p.objetivo) {
                "bajar" -> list.addAll(listOf("10 minutos de caminata a paso ligero", "Evita azúcares en tu próxima comida"))
                "musculo" -> list.addAll(listOf("3 series de flexiones (8-12 reps)", "Batido de proteína post-entreno"))
                else -> list.addAll(listOf("Planchas 3×30s", "Paseo del granjero 4×40m con peso moderado"))
            }
            if (hour < 12) list.add(0, "1 vaso de agua al despertar")
            val newMissions = list.map { Mission(id = System.currentTimeMillis() + Random.nextInt(1000), title = it, done = false) }
            saveMissions(newMissions + missionsState.value)
        }
    }
}

/** Data class describing the user's profile. Defaults are blank strings. */
data class Profile(
    val nombre: String = "",
    val apellido: String = "",
    val pais: String = "",
    val anio: String = "",
    val peso: String = "",
    val altura: String = "",
    val objetivo: String = "bajar", // bajar | musculo | cuerpo
    val intensidad: String = "firme", // moderado | firme | duro
    val voz: String = "firme", // suave | firme | militar
    val idioma: String = Locale.getDefault().toLanguageTag()
)

/** Helper that computes the user's BMI given weight in kg and height in cm. */
fun computeBmi(weight: String, height: String): Float? {
    val w = weight.toFloatOrNull()
    val h = height.toFloatOrNull()
    if (w == null || h == null || w <= 0f || h <= 0f) return null
    val meters = h / 100f
    return w / (meters * meters)
}

/** Convert BMI to a color for the gauge. */
fun colorForBmi(bmi: Float): Color {
    return when {
        bmi < 18.5f -> Color(0xFF6C8A3F)
        bmi < 25f -> Color(0xFFC59B2A)
        bmi < 30f -> Color(0xFFB5651D)
        else -> Color(0xFF8E2F2A)
    }
}

/** Convert BMI to a label. */
fun labelForBmi(bmi: Float): String {
    return when {
        bmi < 18.5f -> "Bajo peso"
        bmi < 25f -> "Normal"
        bmi < 30f -> "Sobrepeso"
        else -> "Obesidad"
    }
}

/** Calculate the user's rank and level based on XP. The thresholds are the
 * same as in the original React example. */
fun calculateRank(xp: Int): Pair<String, Int> {
    val thresholds = listOf(0, 50, 120, 220, 360, 540)
    val rangos = listOf("Recluta", "Soldado", "Cabo", "Sargento", "Subteniente", "Teniente")
    var index = 0
    for (i in thresholds.indices.reversed()) {
        if (xp >= thresholds[i]) {
            index = i
            break
        }
    }
    return rangos[index] to (index + 1)
}

/** Main activity sets up the root compose content. A simple NavHost switches
 * between the registration and home screens based on whether the user has
 * completed registration. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Instantiate the view model using a custom factory so we can pass
        // DataStore. In a larger app, this would be done using Hilt or
        // another dependency injection framework.
        val viewModel = MainViewModel(applicationContext.dataStore)
        setContent {
            // Instantiate TextToSpeech once for the whole composable tree.
            val context = LocalContext.current
            val tts = remember { TextToSpeech(context) { } }
            DisposableEffect(Unit) {
                onDispose { tts.shutdown() }
            }

            // Load voice preferences from DataStore. Compose flows require
            // collecting as state. Combine language and tone.
            val profile by viewModel.profileState.collectAsState()
            val voiceVol by viewModel.voiceVolume.collectAsState()
            val sfxVol by viewModel.sfxVolume.collectAsState()

            val navController = rememberNavController()
            // Determine start destination based on whether name and surname are
            // provided. If either is blank we show the registration screen.
            val startDestination = if (profile.nombre.isBlank() || profile.apellido.isBlank()) "register" else "home"
            // Top‑level Material theme. Use dark colors to resemble the
            // original app. The Material3 dark color scheme is customised
            // manually because the built‑in dynamic scheme may not match the
            // aesthetic.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFC59B2A),
                    onPrimary = Color(0xFF141414),
                    secondary = Color(0xFF6C8A3F),
                    onSecondary = Color(0xFF141414),
                    background = Color(0xFF1E231A),
                    onBackground = Color(0xFFE7E0C9),
                    surface = Color(0xFF262B21),
                    onSurface = Color(0xFFE7E0C9),
                )
            ) {
                NavHost(navController = navController, startDestination = startDestination) {
                    composable("register") {
                        RegisterScreen(
                            profile = profile,
                            onProfileChange = { viewModel.updateProfile(it) },
                            onContinue = { navController.navigate("home") { popUpTo("register") { inclusive = true } } },
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            viewModel = viewModel,
                            tts = tts,
                            voiceVolume = voiceVol,
                            sfxVolume = sfxVol,
                        )
                    }
                }
            }
        }
    }
}

/** Composable for the registration screen. Collects user input and shows BMI
 * calculation. Once required fields are filled out, a continue button
 * navigates to the home screen. */
@Composable
fun RegisterScreen(profile: Profile, onProfileChange: (Profile) -> Unit, onContinue: () -> Unit) {
    val bmi = computeBmi(profile.peso, profile.altura)
    val bmiColor = bmi?.let { colorForBmi(it) } ?: Color(0xFF3A3F34)
    val bmiLabel = bmi?.let { labelForBmi(it) } ?: "Completa peso y altura"
    val bmiPercent = bmi?.let { ((it - 10f) / 30f).coerceIn(0f, 1f) } ?: 0f

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "REGISTRO",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            )
        }
        // Nombre y apellido
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = profile.nombre,
                    onValueChange = { onProfileChange(profile.copy(nombre = it)) },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = profile.apellido,
                    onValueChange = { onProfileChange(profile.copy(apellido = it)) },
                    label = { Text("Apellido") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Pais
        item {
            OutlinedTextField(
                value = profile.pais,
                onValueChange = { onProfileChange(profile.copy(pais = it)) },
                label = { Text("País") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // Año de nacimiento (simple input for brevity)
        item {
            OutlinedTextField(
                value = profile.anio,
                onValueChange = { onProfileChange(profile.copy(anio = it)) },
                label = { Text("Año de nacimiento") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        // Peso y altura
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = profile.peso,
                    onValueChange = { onProfileChange(profile.copy(peso = it)) },
                    label = { Text("Peso (kg)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                    // Removed keyboardOptions to avoid requiring ui-text dependency; default keyboard will be used
                )
                OutlinedTextField(
                    value = profile.altura,
                    onValueChange = { onProfileChange(profile.copy(altura = it)) },
                    label = { Text("Altura (cm)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                    // Removed keyboardOptions to avoid requiring ui-text dependency; default keyboard will be used
                )
            }
        }
        // BMI panel
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("ÍNDICE DE MASA CORPORAL (IMC)", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = bmiPercent,
                        color = bmiColor,
                        trackColor = Color(0xFF3A3F34),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "IMC: ${if (bmi != null) String.format("%.1f", bmi) else "--"}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        Text(text = bmiLabel, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
            }
        }
        // Objetivo toggle buttons
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("OBJETIVO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(text = "Bajar peso", active = profile.objetivo == "bajar") { onProfileChange(profile.copy(objetivo = "bajar")) }
                        ToggleChip(text = "Ganar músculo", active = profile.objetivo == "musculo") { onProfileChange(profile.copy(objetivo = "musculo")) }
                        ToggleChip(text = "Fortalecer cuerpo", active = profile.objetivo == "cuerpo") { onProfileChange(profile.copy(objetivo = "cuerpo")) }
                    }
                }
            }
        }
        // Voice and language (display only)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("VOZ E IDIOMA", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = when (profile.idioma) {
                                "es-MX" -> "Español LAT"
                                "en-US" -> "Inglés"
                                else -> "Español"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Idioma") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = when (profile.voz) {
                                "suave" -> "Suave"
                                "militar" -> "Militar"
                                else -> "Firme"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Tono de voz") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        // Continue button
        item {
            Button(
                onClick = {
                    if (profile.nombre.isBlank() || profile.apellido.isBlank()) {
                        // don't navigate if missing name
                        return@Button
                    }
                    onContinue()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("ALISTARME", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Helper composable for toggle chips used in the objective selection. */
@Composable
fun ToggleChip(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (active) colors.primary else Color(0xFF3B412F),
        contentColor = if (active) Color(0xFF141414) else colors.onSurface,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Home screen shows the user’s current rank, level and XP, mission list,
 * chat, minigames and settings. Because it uses components such as
 * TopAppBar from Material3 that are currently marked as experimental,
 * opt‑in to the ExperimentalMaterial3Api. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel, tts: TextToSpeech, voiceVolume: Float, sfxVolume: Float) {
    val profile by viewModel.profileState.collectAsState()
    val xp by viewModel.xpState.collectAsState()
    val missions by viewModel.missionsState.collectAsState()
    val (rank, level) = calculateRank(xp)
    var speaking by remember { mutableStateOf(false) }
    var newMission by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }

    // Sound effect helper using ToneGenerator
    fun playSfx(type: String) {
        val toneType = when (type) {
            "success" -> ToneGenerator.TONE_PROP_BEEP
            "error" -> ToneGenerator.TONE_PROP_NACK
            else -> ToneGenerator.TONE_PROP_ACK
        }
        val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, (sfxVolume * 100).toInt())
        tg.startTone(toneType, 150)
    }

    // Function to speak using TTS respecting the chosen tone and intensity
    fun speak(text: String) {
        if (tts.isSpeaking) tts.stop()
        tts.setLanguage(Locale.forLanguageTag(profile.idioma))
        when (profile.voz) {
            "suave" -> {
                tts.setPitch(1.15f)
                tts.setSpeechRate(0.95f)
            }
            "militar" -> {
                tts.setPitch(0.75f)
                tts.setSpeechRate(1.15f)
            }
            else -> {
                tts.setPitch(0.9f)
                tts.setSpeechRate(1.05f)
            }
        }
        // The TextToSpeech API does not provide a direct volume setter. The
        // system volume governs the absolute output. We adjust pitch and
        // speech rate above; the user can control volume via device settings.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hardman")
    }

    // Looping voice effect triggered by pressing the motivation button
    LaunchedEffect(speaking, profile.intensidad, profile.voz, voiceVolume) {
        if (speaking) {
            while (speaking) {
                val phrase = when (profile.intensidad) {
                    "moderado" -> "¡Vamos, soldado! Respiración, postura y foco."
                    "duro" -> "¡EN GUARDIA! ¡EJECUTA LA MISIÓN AHORA!"
                    else -> "¡Arriba! Hoy no negocias con la pereza."
                }
                speak(phrase)
                // Wait 3 seconds between shouts
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HARDMAN") },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Rank, level, XP badges
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(label = "RANGO", value = rank)
                Badge(label = "NL", value = level.toString())
                Badge(label = "XP", value = xp.toString())
            }
            // Voice button
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SARGENTO HARDMAN",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Pulsa para iniciar / detener el discurso",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    MotivationButton(
                        speaking = speaking,
                        onToggle = { speaking = !speaking }
                    )
                }
            }
            // Mission section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "MISIONES",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 18.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (newMission.isNotBlank()) {
                                viewModel.addMission(newMission.trim())
                                newMission = ""
                            }
                        }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                            Text("+ Agregar misión")
                        }
                        Button(onClick = { viewModel.recommendMissions() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)) {
                            Text("Recomendada por IA")
                        }
                    }
                    OutlinedTextField(
                        value = newMission,
                        onValueChange = { newMission = it },
                        placeholder = { Text("Escribe una misión...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        singleLine = true
                    )
                    // Mission list
                    Column(Modifier.fillMaxWidth()) {
                        missions.forEach { m ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(Color(0xFF20251D), RoundedCornerShape(8.dp))
                                    .clickable { viewModel.toggleMission(m.id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = m.done, onCheckedChange = { viewModel.toggleMission(m.id) })
                                Text(
                                    text = m.title,
                                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (m.done) {
                                    Text("+10 XP", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            // Chat section
            ChatSection(profile = profile, bmi = computeBmi(profile.peso, profile.altura), onSend = { msg ->
                // Evaluate heuristics for chat reply
            }, speak = { speak(it) })
            // Games section with simple tabs for Memory and Quiz
            GamesSection(viewModel = viewModel, playSfx = { playSfx(it) })
            // Settings dialog
            if (settingsOpen) {
                SettingsDialog(
                    voiceVolume = voiceVolume,
                    sfxVolume = sfxVolume,
                    onVolumesChanged = { v, s -> viewModel.updateVolumes(v, s) },
                    onClose = { settingsOpen = false }
                )
            }
        }
    }
}

/** Simple badge showing a label and a value. */
@Composable
fun Badge(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Motivation button that toggles between off and on states. It displays
 * different icons based on whether speaking is active. */
@Composable
fun MotivationButton(speaking: Boolean, onToggle: () -> Unit) {
    val imageRes = if (speaking) R.drawable.btn_motivation_active else R.drawable.btn_motivation
    Image(
        painter = painterResource(id = imageRes),
        contentDescription = "Motivation Button",
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(36.dp))
            .clickable { onToggle() },
        contentScale = ContentScale.Crop
    )
}

/** Chat section with heuristic AI. Messages from the assistant and the user
 * are displayed in a column. The assistant responds to simple keywords
 * describing BMI, weight loss, muscle gain and routines. */
@Composable
fun ChatSection(profile: Profile, bmi: Float?, onSend: (String) -> Unit, speak: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(Message(role = "assistant", text = "¡Reporte su estado, soldado!"))) }
    fun reply(userMsg: String) {
        val lower = userMsg.lowercase(Locale.getDefault())
        val ans = when {
            lower.contains("imc") || lower.contains("bmi") -> {
                if (bmi != null) "Su IMC es ${String.format("%.1f", bmi)}. ${labelForBmi(bmi)}." else "Completa peso y altura para calcular tu IMC."
            }
            lower.contains("peso") || lower.contains("bajar") -> "Para bajar peso: déficit calórico, paso firme diario y proteína en cada comida."
            lower.contains("músculo") || lower.contains("musculo") -> "Gane músculo: superávit ligero, progresión de cargas y descanso disciplinado."
            lower.contains("rutina") -> "Rutina base: flexiones, sentadillas, planchas y caminata rápida. ¡Sin excusas!"
            else -> "Recibido."
        }
        messages = messages + Message(role = "user", text = userMsg) + Message(role = "assistant", text = ans)
        speak(ans)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("CHATBOT — SARGENTO", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 180.dp)
                    .background(Color(0x33000000), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                messages.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (m.role == "assistant") Arrangement.Start else Arrangement.End
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (m.role == "assistant") Color(0xFF2C3327) else Color(0xFF3B412F)
                        ) {
                            Text(m.text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Pregúntale al sargento...") },
                    singleLine = true
                )
                Button(onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        reply(text)
                        input = ""
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black)) {
                    Text("Enviar")
                }
            }
        }
    }
}

data class Message(val role: String, val text: String)

/** Games section containing two mini games: a memory game and a quiz. The user
 * can swipe between games (simplified to a tab row). */
@Composable
fun GamesSection(viewModel: MainViewModel, playSfx: (String) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Memoria", "Quiz")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("JUEGOS MENTALES", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> MemoryGame(playSfx = playSfx)
            else -> QuizGame(playSfx = playSfx)
        }
    }
}

/** Simple memory game: a 4×4 grid of emoji pairs. When all pairs are matched
 * the game resets and awards XP. */
@Composable
fun MemoryGame(playSfx: (String) -> Unit) {
    val icons = listOf("🪖","🔫","🧨","🛡️","🪙","✈️","🛰️","🚁")
    var deck by remember { mutableStateOf(shufflePairs(icons)) }
    var opened by remember { mutableStateOf(listOf<Int>()) }
    var matched by remember { mutableStateOf(setOf<Int>()) }
    var moves by remember { mutableStateOf(0) }
    // Check for match
    LaunchedEffect(opened) {
        if (opened.size == 2) {
            val first = opened[0]
            val second = opened[1]
            moves++
            if (deck[first] == deck[second]) {
                matched = matched + first + second
                playSfx("success")
            } else {
                playSfx("error")
                kotlinx.coroutines.delay(600)
            }
            opened = emptyList()
        }
    }
    // Reset when all matched
    LaunchedEffect(matched) {
        if (matched.size == deck.size) {
            // Award a small amount of XP (via a local callback) or just restart
            kotlinx.coroutines.delay(800)
            deck = shufflePairs(icons)
            opened = emptyList()
            matched = emptySet()
            moves = 0
        }
    }
    Column {
        Text("Movimientos: $moves", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(8.dp))
        val gridSize = 4
        val cellSize = 64.dp
        for (row in 0 until gridSize) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (col in 0 until gridSize) {
                    val index = row * gridSize + col
                    val isOpen = opened.contains(index) || matched.contains(index)
                    Card(
                        onClick = {
                            if (!isOpen && opened.size < 2) {
                                opened = opened + index
                            }
                        },
                        modifier = Modifier.size(cellSize),
                        colors = CardDefaults.cardColors(containerColor = if (isOpen) Color(0xFF3B412F) else Color(0xFF141714))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isOpen) {
                                Text(deck[index], fontSize = 28.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

fun shufflePairs(symbols: List<String>): List<String> {
    val taken = symbols.shuffled().take(8)
    val deck = (taken + taken).shuffled()
    return deck
}

/** Simple quiz game. Presents a question with four options. The user earns
 * points by choosing the correct answer. When points exceed a threshold the
 * game restarts with new questions. */
@Composable
fun QuizGame(playSfx: (String) -> Unit) {
    var level by remember { mutableStateOf(1) }
    var score by remember { mutableStateOf(0) }
    var question by remember { mutableStateOf(generateQuestion(level)) }
    var timeLeft by remember { mutableStateOf(25) }
    // Countdown timer
    LaunchedEffect(level) {
        timeLeft = 25 - (level * 2).coerceAtMost(15)
        while (timeLeft > 0) {
            kotlinx.coroutines.delay(1000)
            timeLeft--
        }
        // Timeout -> reset level
        playSfx("timeout")
        level = 1
        score = 0
        question = generateQuestion(level)
    }
    Column {
        Text("Nivel $level", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Puntuación: $score", fontSize = 14.sp)
        Text("Tiempo: ${timeLeft}s", fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(question.text, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        question.options.forEachIndexed { index, option ->
            Button(
                onClick = {
                    if (index == question.correct) {
                        playSfx("success")
                        score += 10
                        level++
                    } else {
                        playSfx("error")
                        level = if (level > 1) level - 1 else 1
                        score = 0
                    }
                    question = generateQuestion(level)
                    timeLeft = 25 - (level * 2).coerceAtMost(15)
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B412F), contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Text(option)
            }
        }
    }
}

data class QuizQuestion(val text: String, val options: List<String>, val correct: Int)

fun generateQuestion(level: Int): QuizQuestion {
    val questions = listOf(
        QuizQuestion(
            text = "Hay 5 sillas en fila. Si un soldado se sienta en la primera y otro en la última, ¿cuántas sillas quedan libres entre ellos?",
            options = listOf("3", "2", "4", "0"),
            correct = 0
        ),
        QuizQuestion(
            text = "Un convoy tarda 10 min en cruzar un túnel. Si duplica su velocidad, ¿cuánto tarda?",
            options = listOf("5 min", "10 min", "20 min", "No se puede saber"),
            correct = 0
        ),
        QuizQuestion(
            text = "Secuencia: 2, 6, 12, 20, ¿siguiente?",
            options = listOf("30", "28", "24", "32"),
            correct = 0
        ),
        QuizQuestion(
            text = "Tengo dientes pero no muerdo; me usan los mecánicos.",
            options = listOf("Llave inglesa", "Sierra", "Peine", "Tanque"),
            correct = 1
        ),
        QuizQuestion(
            text = "Sin ser soldado, siempre voy en formación. Si me rompes, hago explosión. ¿Qué soy?",
            options = listOf("Huevo", "Granada", "Línea", "Fila"),
            correct = 0
        )
    )
    return questions[level % questions.size]
}

/** Settings dialog allowing the user to adjust voice and sound effect volumes. */
@Composable
fun SettingsDialog(voiceVolume: Float, sfxVolume: Float, onVolumesChanged: (Float, Float) -> Unit, onClose: () -> Unit) {
    var vVol by remember { mutableStateOf(voiceVolume) }
    var sVol by remember { mutableStateOf(sfxVolume) }
    AlertDialog(
        onDismissRequest = { onClose() },
        title = { Text("Ajustes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Volumen de voz (Sargento)", fontSize = 12.sp)
                    Slider(
                        value = vVol,
                        onValueChange = { vVol = it },
                        valueRange = 0f..1f
                    )
                    Text("${(vVol * 100).toInt()}%", fontSize = 12.sp)
                }
                Column {
                    Text("Efectos de sonido", fontSize = 12.sp)
                    Slider(
                        value = sVol,
                        onValueChange = { sVol = it },
                        valueRange = 0f..1f
                    )
                    Text("${(sVol * 100).toInt()}%", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onVolumesChanged(vVol, sVol)
                onClose()
            }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = { onClose() }) { Text("Cancelar") }
        }
    )
}