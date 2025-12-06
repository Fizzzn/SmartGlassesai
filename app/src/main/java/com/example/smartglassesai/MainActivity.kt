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

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var espImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnEspCapture: Button
    private lateinit var tts: TextToSpeech

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

        espImage = findViewById(R.id.espImage)
        statusText = findViewById(R.id.statusText)
        btnEspCapture = findViewById(R.id.btnEspCapture)

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

        btnEspCapture.setOnClickListener { captureFromEspAndOcr() }
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
