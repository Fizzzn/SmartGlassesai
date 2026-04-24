package com.example.smartglassesai

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var espImage: ImageView
    private lateinit var espStreamView: WebView
    private lateinit var statusText: TextView
    private lateinit var btnEspCapture: Button
    private lateinit var btnRestartStream: Button
    private lateinit var btnFindPlaces: Button
    private lateinit var btnVoiceCommand: Button
    private lateinit var btnRepeat: Button

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var tts: TextToSpeech

    private var lastSpokenText = ""

    // NEW: editable base URL (tap statusText to change; persisted)
    private var ESP_BASE = "http://10.49.72.2"

    // NEW: longer timeouts + clearer failure messages
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val text =
                    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()

                if (!text.isNullOrBlank()) {
                    statusText.text = "You said: \"$text\""
                    processVoiceCommand(text) // 👈 ADD THIS
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ View binding FIRST (CRITICAL)
        espImage = findViewById(R.id.espImage)
        espStreamView = findViewById(R.id.espStreamView)
        statusText = findViewById(R.id.statusText)
        btnEspCapture = findViewById(R.id.btnEspCapture)
        btnRestartStream = findViewById(R.id.btnRestartStream)
        btnFindPlaces = findViewById(R.id.btnFindPlaces)
        btnVoiceCommand = findViewById(R.id.btnVoiceCommand)
        btnRepeat = findViewById(R.id.btnRepeat)

        // Start in STREAM mode
        espStreamView.visibility = View.VISIBLE
        espImage.visibility = View.GONE
        espStreamView.bringToFront()

        espStreamView.loadUrl("$ESP_BASE/stream")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)

        // WebView setup
        espStreamView.settings.apply {
            javaScriptEnabled = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        espStreamView.webViewClient = WebViewClient()

        // Load saved ESP IP
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        ESP_BASE = prefs.getString("esp_base", ESP_BASE)!!

        espStreamView.loadUrl("$ESP_BASE/stream")

        statusText.text = "Ready. Streaming from ESP."

        // 👇 Tap to change ESP IP
        statusText.setOnClickListener {
            val input = EditText(this).apply { setText(ESP_BASE) }
            AlertDialog.Builder(this)
                .setTitle("Set ESP Base URL")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    ESP_BASE = input.text.toString().trim()
                    prefs.edit().putString("esp_base", ESP_BASE).apply()
                    espStreamView.loadUrl("$ESP_BASE/stream")
                    statusText.text = "ESP set to $ESP_BASE"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Capture from ESP
        btnEspCapture.setOnClickListener { captureFromEsp() }

        btnVoiceCommand.setOnClickListener { startVoiceInput() }

        btnRestartStream.setOnClickListener {
            restartStream()
        }

        btnFindPlaces.setOnClickListener {
            val input = EditText(this)

            AlertDialog.Builder(this)
                .setTitle("Search nearby")
                .setView(input)
                .setPositiveButton("Search") { _, _ ->
                    val query = input.text.toString()
                    findNearbyPlaces(if (query.isBlank()) "restaurant" else query)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnRepeat.setOnClickListener {
            if (lastSpokenText.isNotEmpty()) {
                tts.speak(lastSpokenText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun stopEspStream() {
        espStreamView.apply {
            loadUrl("about:blank")   // kills HTTP stream
            clearHistory()
            onPause()
        }
    }

    private fun restartStream() {
        statusText.text = "Restarting stream..."

        // Remove old WebView completely
        val parent = espStreamView.parent as FrameLayout
        parent.removeView(espStreamView)

        espStreamView.destroy()

        // Create a NEW WebView instance
        espStreamView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }

            webViewClient = WebViewClient()
        }

        parent.addView(espStreamView)

        // Switch UI state
        espImage.visibility = View.GONE
        espStreamView.visibility = View.VISIBLE
        espStreamView.bringToFront()

        // Reload stream cleanly
        espStreamView.loadUrl("$ESP_BASE/stream")

        btnRestartStream.visibility = View.GONE
        statusText.text = "Streaming from ESP"
    }

    private fun captureFromEsp() {
        lifecycleScope.launch {

            statusText.text = "Stopping stream…"
            stopEspStream()
            delay(300)

            statusText.text = "Capturing image from ESP…"

            try {
                val bytes = withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$ESP_BASE/capture")
                        .header("Accept", "image/jpeg")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null
                        else response.body?.bytes()
                    }
                }

                if (bytes == null || bytes.isEmpty()) {
                    statusText.text = "Empty image"
                    restartStream()
                    return@launch
                }

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: run {
                        statusText.text = "Decode failed"
                        restartStream()
                        return@launch
                    }

                espImage.setImageBitmap(bitmap)
                espImage.visibility = View.VISIBLE
                espStreamView.visibility = View.INVISIBLE

                btnRestartStream.visibility = View.VISIBLE
                statusText.text = "Image captured. Analyzing..."
                runTextRecognition(bitmap)

            } catch (e: Exception) {
                statusText.text = "ESP error: ${e::class.simpleName}"
                restartStream()
            }
        }
    }

    private fun describeImage(bitmap: Bitmap) {
        statusText.text = "Analyzing image..."

        lifecycleScope.launch {
            try {
                val inputPrompt = content {
                    image(bitmap)
                    text("Describe this image in one short sentence.")
                }

                val response = generativeModel.generateContent(inputPrompt)
                val result = response.text ?: "I couldn't describe the image."

                speakText(result)

            } catch (e: Exception) {
                statusText.text = "AI Error: ${e.message}"
            }
        }
    }

    private fun runTextRecognition(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener {
                val text = it.text

                if (text.isNotBlank()) {
                    speakText("I read: $text")
                } else {
                    // 👇 FALLBACK TO GEMINI
                    describeImage(bitmap)
                }
            }
            .addOnFailureListener {
                statusText.text = "OCR failed"
                describeImage(bitmap)
            }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechLauncher.launch(intent)
    }

    private fun processVoiceCommand(command: String) {
        val lower = command.lowercase()

        if (lower.contains("find") || lower.contains("nearby")) {
            val query = lower
                .replace("find", "")
                .replace("nearby", "")
                .trim()

            findNearbyPlaces(if (query.isBlank()) "restaurant" else query)
        }
        // 👇 NEW: handle simple words like "coffee"
        else if (!lower.contains(" ")) {
            findNearbyPlaces(lower)
        }
        else {
            runGeminiText(command)
        }
    }

    private fun findNearbyPlaces(query: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                100
            )
            return
        }

        statusText.text = "Finding $query nearby..."

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                statusText.text = "Location not available"
                return@addOnSuccessListener
            }

            lifecycleScope.launch {
                val result = searchGooglePlaces(
                    location.latitude,
                    location.longitude,
                    query
                )

                statusText.text = result
                speakText(result)
            }
        }
    }

    private suspend fun searchGooglePlaces(
        lat: Double,
        lng: Double,
        query: String
    ): String = withContext(Dispatchers.IO) {

        val apiKey = BuildConfig.PLACES_API_KEY

        val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                "?location=$lat,$lng&radius=1000&keyword=$query&key=$apiKey"

        val request = Request.Builder().url(url).build()

        return@withContext try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Places error: ${response.code}"
                }

                val body = response.body?.string() ?: return@withContext "No data"

                val json = org.json.JSONObject(body)
                val results = json.getJSONArray("results")

                if (results.length() == 0) {
                    return@withContext "No $query found nearby"
                }

                val builder = StringBuilder("Top places:\n")

                for (i in 0 until minOf(3, results.length())) {
                    val place = results.getJSONObject(i)
                    val name = place.getString("name")
                    builder.append("${i + 1}. $name\n")
                }

                builder.toString()
            }
        } catch (e: Exception) {
            "Network error"
        }
    }

    private fun runGeminiText(prompt: String) {
        lifecycleScope.launch {
            try {
                val response = generativeModel.generateContent(prompt)
                val text = response.text ?: "No response"
                speakText(text)
            } catch (e: Exception) {
                statusText.text = "AI Error"
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.US)
        }
    }

    private fun speakText(text: String) {
        lastSpokenText = text
        statusText.text = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}