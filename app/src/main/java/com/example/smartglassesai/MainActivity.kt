package com.example.smartglassesai

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var espImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnEspCapture: Button
    private lateinit var tts: TextToSpeech

    // Inside MainActivity class
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var btnFindPlaces: Button

    private lateinit var btnVoiceCommand: Button // Add this    // NEW: Handle the result from the Speech Recognizer
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val spokenText: ArrayList<String>? = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val command = spokenText?.get(0) ?: ""

            if (command.isNotEmpty()) {
                statusText.text = "You said: \"$command\""
                processVoiceCommand(command)
            }
        }
    }
    // NEW: longer timeouts + clearer failure messages
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    // NEW: editable base URL (tap statusText to change; persisted)
    private var ESP_BASE = "http://192.168.1.89"  // set your ESP IP here initially

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        espImage = findViewById(R.id.espImage)
        statusText = findViewById(R.id.statusText)
        btnEspCapture = findViewById(R.id.btnEspCapture)

        // New FindPlaces button
        btnFindPlaces = findViewById(R.id.btnFindPlaces)

        // New Voice Command button
        btnVoiceCommand = findViewById(R.id.btnVoiceCommand)

        // NEW: load & edit ESP base URL
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        ESP_BASE = prefs.getString("esp_base", ESP_BASE)!!

        statusText.setOnClickListener {
            val input = EditText(this).apply { setText(ESP_BASE) }
            AlertDialog.Builder(this)
                .setTitle("Set ESP Base URL")
                .setMessage("Example: http://192.168.1.89")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    ESP_BASE = input.text.toString().trim()
                    prefs.edit().putString("esp_base", ESP_BASE).apply()
                    statusText.text = "ESP set to $ESP_BASE"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // NEW: long-press to probe /net (quick reachability test)
        statusText.setOnLongClickListener {
            probeEsp()
            true
        }

        tts = TextToSpeech(this, this)
        statusText.text = "Ready. Tap Capture."

        btnFindPlaces.setOnClickListener {
            // Check for permission BEFORE starting voice input
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // If we don't have permission, ask for it.
                // We use a specific request code (e.g., 101) to identify this request.
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            } else {
                // Permission already granted? Good, start listening.
                statusText.text = "Say what you want to find (e.g. 'Coffee', 'Gas Station')..."
                startVoiceInput()
            }
        }

        btnVoiceCommand.setOnClickListener { startVoiceInput() }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask for places, translation, or questions...")
        }

        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            statusText.text = "Speech Error: ${e.localizedMessage}"
        }
    }

    private fun processVoiceCommand(command: String) {
        val lowerCmd = command.lowercase()

        // improved logic: Check for intent keywords
        if (lowerCmd.contains("find") || lowerCmd.contains("nearby") || lowerCmd.contains("where is")) {

            // Simple keyword extraction:
            // If user says "Find nearby pizza", we want to search for "pizza".
            // This is a basic way to clean the string for the API:
            var query = lowerCmd
                .replace("find", "")
                .replace("nearby", "")
                .replace("restaurants", "restaurant") // normalize
                .replace("please", "")
                .trim()

            if (query.isBlank()) query = "restaurant" // fallback

            statusText.text = "Searching map for: $query"
            findNearbyPlaces(query)
        }
        // If the user just says a noun like "Gas station" or "Coffee", assume it's a search
        else if (lowerCmd.length < 20 && !lowerCmd.contains(" ")) {
            findNearbyPlaces(lowerCmd)
        }
        else {
            // General AI Query
            statusText.text = "Asking Gemini..."
            runGeminiWithoutImage(command)
        }
    }

    // Function for handling permission requests
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 101) { // 101 matches the request code we used above
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // User just granted permission! Start the voice input now.
                statusText.text = "Permission granted. Say what you want to find..."
                startVoiceInput()
            } else {
                statusText.text = "Location permission needed to find places."
            }
        }
    }

    // Function to search Google Places
    private suspend fun searchGooglePlaces(lat: Double, lng: Double, query: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.PLACES_API_KEY
        if (apiKey.isBlank()) return@withContext "Error: No Places API Key found."

        // CHANGE 1: Use Text Search endpoint (better for voice queries)
        val url = "https://places.googleapis.com/v1/places:searchText"

        // Default fallback if query is empty
        val safeQuery = if (query.isBlank()) "restaurant" else query

        // CHANGE 2: JSON Body uses 'textQuery' and 'locationBias'
        val jsonBody = """
            {
              "textQuery": "$safeQuery",
              "maxResultCount": 3,
              "locationBias": {
                "circle": {
                  "center": { "latitude": $lat, "longitude": $lng },
                  "radius": 1000.0
                }
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Goog-Api-Key", apiKey)
            // 👇 CHANGE: Added 'places.location' to the field mask
            .addHeader("X-Goog-FieldMask", "places.displayName.text,places.formattedAddress,places.location")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                return@withContext "Places API Error: ${response.code} - $errorBody"
            }
            return@withContext response.body?.string() ?: "No data found"
        }
    }


    // Update signature to take an optional query
    private fun findNearbyPlaces(query: String = "restaurant") {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100)
            return
        }

        statusText.text = "Locating for '$query'..."

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                statusText.text = "Found location. Searching Google for $query..."

                lifecycleScope.launch {
                    val jsonResponse = searchGooglePlaces(location.latitude, location.longitude, query)

                    // Error handling
                    if (jsonResponse.startsWith("Error") || jsonResponse.startsWith("Places API Error")) {statusText.text = jsonResponse
                        speakText("I encountered a technical error.")
                        return@launch
                    }

                    // 👇 NEW LOGIC: Parse JSON, calculate distance, and format for Gemini
                    val formattedList = StringBuilder()
                    try {
                        val root = JSONObject(jsonResponse)
                        val placesArray = root.optJSONArray("places")

                        if (placesArray == null || placesArray.length() == 0) {
                            statusText.text = "No places found for $query"
                            speakText("I couldn't find any $query nearby.")
                            return@launch
                        }

                        formattedList.append("Here are the top 3 results for '$query':\n\n")

                        for (i in 0 until placesArray.length()) {
                            val place = placesArray.getJSONObject(i)
                            val name = place.getJSONObject("displayName").getString("text")
                            val address = place.optString("formattedAddress", "Address not available")

                            // Get coordinates to calculate distance
                            val placeLoc = place.getJSONObject("location")
                            val lat = placeLoc.getDouble("latitude")
                            val lng = placeLoc.getDouble("longitude")

                            val distance = calculateDistanceMiles(location.latitude, location.longitude, lat, lng)

                            formattedList.append("${i + 1}. $name\n   Distance: $distance\n   Address: $address\n\n")
                        }

                    } catch (e: Exception) {
                        statusText.text = "Error parsing places: ${e.message}"
                        return@launch
                    }

                    // Show the clean list on screen immediately
                    statusText.text = formattedList.toString()

                    // Send to Gemini to read out politely
                    val prompt = """
                        Here is a list of nearby places with distances:
                        $formattedList
                        
                        Task:
                        1. Read out the options clearly to the user.
                        2. Mention the name and the distance for each one.
                        3. Ask the user which one they would like directions to.
                    """.trimIndent()

                    runGeminiWithoutImage(prompt)
                }
            } else {
                statusText.text = "Could not get location."
            }
        }
    }

    // Funtion to calculate distance in miles
    private fun calculateDistanceMiles(startLat: Double, startLng: Double, endLat: Double, endLng: Double): String {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(startLat, startLng, endLat, endLng, results)
        val meters = results[0]
        val miles = meters * 0.000621371 // Convert meters to miles
        return String.format(Locale.US, "%.1f miles", miles)
    }
    
    // Function to call Gemini with just text (no image)
    private fun runGeminiWithoutImage(prompt: String) {
        lifecycleScope.launch {
            try {
                val response = generativeModel.generateContent(prompt)
                val aiText = response.text ?: "I couldn't find an answer."

                statusText.text = aiText
                speakText(aiText) // Spoken directions!
            } catch (e: Exception) {
                statusText.text = "AI Error: ${e.localizedMessage}"
            }
        }
    }

    // NEW: /net probe with surfaced errors
    private fun probeEsp() {
        lifecycleScope.launch {
            statusText.text = "Probing $ESP_BASE/net …"
            val msg = withContext(Dispatchers.IO) {
                try {
                    val r = Request.Builder()
                        .url("$ESP_BASE/net")
                        .header("Cache-Control", "no-cache")
                        .build()
                    httpClient.newCall(r).execute().use { resp ->
                        if (!resp.isSuccessful) "HTTP ${resp.code} from /net"
                        else resp.body?.string() ?: "Empty /net body"
                    }
                } catch (e: Exception) {
                    "Network error: ${e.localizedMessage}"
                }
            }
            statusText.text = msg
        }
    }

    private fun captureFromEspAndOcr() {
        val url = "$ESP_BASE/capture"
        statusText.text = "GET $url …"
        lifecycleScope.launch {
            val bmp = fetchEspJpeg(url)
            if (bmp == null) {
                statusText.text = "Capture failed (check IP, same Wi-Fi, /capture)"
                return@launch
            }
            espImage.setImageBitmap(bmp)
            statusText.text = "Running OCR…"
            runTextRecognition(bmp)
        }
    }

    // NEW: clearer error reporting in fetch
    private suspend fun fetchEspJpeg(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "HTTP ${resp.code} from /capture"
                    }
                    return@withContext null
                }
                val bytes = resp.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) { statusText.text = "Empty body from /capture" }
                    return@withContext null
                }
                return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: run {
                        withContext(Dispatchers.Main) { statusText.text = "Decode error (JPEG)" }
                        null
                    }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { statusText.text = "Network error: ${e.localizedMessage}" }
            null
        }
    }

    private fun runTextRecognition(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val recognized = visionText.text.trim()
                if (recognized.isEmpty()) {
                    val msg = "No text found"
                    statusText.text = msg
                    speakText(msg)
                    return@addOnSuccessListener
                }

                // Gemini optional: falls back to raw OCR if key missing/offline
                lifecycleScope.launch {
                    if (BuildConfig.GEMINI_API_KEY.isBlank()) {
                        statusText.text = recognized
                        speakText(recognized)
                        return@launch
                    }
                    try {
                        statusText.text = "Summarizing…"
                        val prompt = "Summarize or translate if needed: \"$recognized\""
                        val resp = generativeModel.generateContent(prompt)
                        val ai = resp.text ?: recognized
                        statusText.text = ai
                        speakText(ai)
                    } catch (_: Exception) {
                        statusText.text = "AI offline. Reading OCR."
                        speakText(recognized)
                    }
                }
            }
            .addOnFailureListener { e ->
                statusText.text = "OCR Error: ${e.localizedMessage}"
            }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                statusText.text = "TTS language not supported"
            }
        }
    }

    private fun speakText(text: String) {
        if (!::tts.isInitialized) return
        tts.stop()
        text.chunked(3000).forEach {
            tts.speak(it, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
