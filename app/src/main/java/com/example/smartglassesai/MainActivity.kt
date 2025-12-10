package com.example.smartglassesai

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
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
//import androidx.compose.ui.semantics.text
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var espImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var btnEspCapture: Button
    private lateinit var tts: TextToSpeech

    // Inside MainActivity class
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var btnFindPlaces: Button

    private lateinit var btnVoiceCommand: Button // Add this    // NEW: Handle the result from the Speech Recognizer

    private lateinit var btnRepeat: Button
    private var lastFoundPlaces: List<JSONObject> = emptyList()
    private var lastUserLocation: android.location.Location? = null
    private var lastSpokenText: String = ""

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

    // ... existing speechLauncher ...

    // 👇 NEW: Handle the result from the Camera App
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val photo = result.data?.extras?.get("data") as? Bitmap
            if (photo != null) {
                espImage.setImageBitmap(photo)
                statusText.text = "Photo captured. Analyzing..."

                // Optional: Run OCR or Description immediately
                runTextRecognition(photo)
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

        btnRepeat = findViewById(R.id.btnRepeat)

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

        // 👇 NEW: Launch Phone Camera
        btnEspCapture.setOnClickListener {
            // Check Camera Permission
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 103)
            } else {
                openCamera()
            }
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

        // When clicked, say the last text again
        btnRepeat.setOnClickListener {
            if (lastSpokenText.isNotEmpty()) {
                tts.speak(lastSpokenText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    // ... inside MainActivity class ...

    private fun openCamera() {
        val cameraIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        try {
            cameraLauncher.launch(cameraIntent)
        } catch (e: Exception) {
            statusText.text = "Error opening camera: ${e.localizedMessage}"
        }
    }

    // Update existing permission handler
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // ... existing code for 101 (Location) ...
        if (requestCode == 101) {
            // ... keep existing location logic ...
        }

        // 👇 NEW: Handle Camera Permission
        if (requestCode == 103) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                statusText.text = "Camera permission needed to take photos."
            }
        }
    }

    private fun runTextRecognition(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val resultText = visionText.text
                if (resultText.isNotBlank()) {
                    statusText.text = "Read: $resultText"
                    speakText("I read: $resultText")
                } else {
                    statusText.text = "No text found in image."
                    describeImage(bitmap) // Fallback to AI description
                }
            }
            .addOnFailureListener { e ->
                statusText.text = "OCR Failed: ${e.localizedMessage}"
            }
    }

    private fun describeImage(bitmap: Bitmap) {
        statusText.text = "Asking Gemini to describe..."

        lifecycleScope.launch {
            try {
                val inputPrompt = content {
                    image(bitmap)
                    text("Describe this image in one short sentence.")
                }
                val response = generativeModel.generateContent(inputPrompt)
                response.text?.let {
                    statusText.text = it
                    speakText(it)
                }
            } catch (e: Exception) {
                statusText.text = "AI Error: ${e.message}"
            }
        }
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

        // Check if user is selecting an option (and we have places saved)
        if (lastFoundPlaces.isNotEmpty() &&
            (lowerCmd.contains("option") || lowerCmd.contains("number") ||
                    lowerCmd.contains("first") || lowerCmd.contains("second") || lowerCmd.contains("third") ||
                    lowerCmd == "one" || lowerCmd == "two" || lowerCmd == "three")) {

            handlePlaceSelection(lowerCmd)
            return
        }

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
            return
        }
        // Check for Direct Navigation Intent
        // Examples: "Directions to Starbucks", "How do I get to home", "Navigate to work"
        if (lowerCmd.contains("directions to") || lowerCmd.contains("navigate to") || lowerCmd.contains("how do i get to")) {
            statusText.text = "Getting location for directions..."
            handleDirectNavigation(command)
            return
        }

        // If the user just says a noun like "Gas station" or "Coffee", assume it's a search
        else if (lowerCmd.length < 20 && !lowerCmd.contains(" ")) {
            findNearbyPlaces(lowerCmd)
        }
        else {
            // General AI Query
            statusText.text = "Asking Gemini..."
            // 👇 CHANGE: Wrap the user's command in a "system instruction" for brevity
            val briefPrompt = """
                User Question: "$command"
                
                Instructions:
                1. Answer the question accurately but VERY briefly.
                2. Keep the response under 3 sentences.
                3. Use simple, spoken-style language.
            """.trimIndent()

            runGeminiWithoutImage(briefPrompt)
        }
    }

    // Function for handling permission requests
    /*override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
    }*/

    private fun handleDirectNavigation(command: String) {
        // 1. Check Permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
            return
        }

        // 2. Get Location
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                // Save location for future context
                lastUserLocation = location

                statusText.text = "Asking AI for directions..."

                // Clean up the command to get the place name
                var destinationName = command.lowercase()
                    .replace("directions to", "")
                    .replace("navigate to", "")
                    .replace("how do i get to", "")
                    .trim()

                if (destinationName.isEmpty()) destinationName = "restaurant"

                lifecycleScope.launch {
                    // STEP A: Search for the place first to get its exact coordinates
                    val searchJson = searchGooglePlaces(location.latitude, location.longitude, destinationName)

                    try {
                        val root = JSONObject(searchJson)
                        val places = root.optJSONArray("places")

                        if (places != null && places.length() > 0) {
                            // We found the place! Get its location.
                            val firstPlace = places.getJSONObject(0)
                            val realName = firstPlace.getJSONObject("displayName").getString("text")
                            val placeLoc = firstPlace.getJSONObject("location")
                            val destLat = placeLoc.getDouble("latitude")
                            val destLng = placeLoc.getDouble("longitude")

                            statusText.text = "Calculating route to $realName..."

                            // STEP B: Ask for directions using specific coordinates (Lat,Lng)
                            // This prevents "No route found" errors caused by ambiguous names
                            val coordinateString = "$destLat,$destLng"
                            val directionsJson = fetchDirections(location.latitude, location.longitude, coordinateString)

                            processDirectionsResponse(directionsJson, realName)
                        } else {
                            statusText.text = "Could not find '$destinationName'"
                            speakText("I couldn't find a place named $destinationName nearby.")
                        }
                    } catch (e: Exception) {
                        statusText.text = "Error locating place: ${e.message}"
                    }
                }
            } else {
                statusText.text = "Could not determine location."
                speakText("I need to know where you are, but I couldn't find your GPS signal.")
            }
        }
    }

    // Add this to MainActivity class
    private suspend fun fetchDirections(originLat: Double, originLng: Double, destAddress: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.PLACES_API_KEY // Ensure this key has Directions API enabled
        val encodedDest = URLEncoder.encode(destAddress, "utf-8")
        val url = "https://maps.googleapis.com/maps/api/directions/json?origin=$originLat,$originLng&destination=$encodedDest&mode=walking&key=$apiKey"
        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext "Error: ${response.code}"
            return@withContext response.body?.string() ?: "Error: No data"
        }
    }

    private fun handlePlaceSelection(command: String) {
        val lowerCmd = command.lowercase()
        var selectedIndex = -1

        // Figure out which index they want
        if (lowerCmd.contains("one") || lowerCmd.contains("1") || lowerCmd.contains("first")) selectedIndex = 0
        else if (lowerCmd.contains("two") || lowerCmd.contains("2") || lowerCmd.contains("second")) selectedIndex = 1
        else if (lowerCmd.contains("three") || lowerCmd.contains("3") || lowerCmd.contains("third")) selectedIndex = 2

        if (selectedIndex != -1 && selectedIndex < lastFoundPlaces.size) {
            val place = lastFoundPlaces[selectedIndex]
            val name = place.getJSONObject("displayName").getString("text")

            // 👇 KEY FIX: Get coordinates directly from the JSON object
            val placeLoc = place.getJSONObject("location")
            val destLat = placeLoc.getDouble("latitude")
            val destLng = placeLoc.getDouble("longitude")

            statusText.text = "Fetching walking directions to $name..."

            // Use stored location for the prompt
            val userLat = lastUserLocation?.latitude ?: 0.0
            val userLng = lastUserLocation?.longitude ?: 0.0

            // 👇 NEW LOGIC: Call Directions API
            lifecycleScope.launch {
                val coordinateString = "$destLat,$destLng"
                val directionsJson = fetchDirections(userLat, userLng, coordinateString)

                // Process the result
                processDirectionsResponse(directionsJson, name)
            }

            lastFoundPlaces = emptyList()
        } else {
            speakText("I didn't understand which option. Please say Option 1, 2, or 3.")
        }
    }

    private fun processDirectionsResponse(jsonResponse: String, destinationName: String) {
        try {
            val root = JSONObject(jsonResponse)
            val routes = root.optJSONArray("routes")

            if (routes == null || routes.length() == 0) {
                statusText.text = "No route found."
                speakText("I couldn't find a walking route to $destinationName.")
                return
            }

            val legs = routes.getJSONObject(0).getJSONArray("legs")
            val steps = legs.getJSONObject(0).getJSONArray("steps")
            val duration = legs.getJSONObject(0).getJSONObject("duration").getString("text")
            val distance = legs.getJSONObject(0).getJSONObject("distance").getString("text")

            val stepsBuilder = StringBuilder()
            stepsBuilder.append("Route to $destinationName ($distance, $duration):\n")

            // Loop through steps to get specific instructions
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                // Google returns instructions with HTML tags (e.g., <b>Turn Left</b>)
                // We verify 'html_instructions' exists
                var instruction = step.getString("html_instructions")

                // Remove HTML tags for plain text
                instruction = instruction.replace(Regex("<[^>]*>"), "")

                val dist = step.getJSONObject("distance").getString("text")
                stepsBuilder.append("${i + 1}. $instruction ($dist)\n")
            }

            val cleanSteps = stepsBuilder.toString()
            statusText.text = cleanSteps // Show detailed list on screen

            // Ask Gemini to summarize specifically
            val prompt = """
                Here are the official walking directions:
                $cleanSteps
                
                Task:
                1. Tell the user the total time and distance.
                2. Read the first 3 major turns/instructions clearly.
                3. If there are many steps, just summarize the rest as "and follow the path to the destination."
                4. Keep it spoken-style and helpful.
            """.trimIndent()

            runGeminiWithoutImage(prompt)

        } catch (e: Exception) {
            statusText.text = "Error parsing directions: ${e.message}"
            speakText("I found the place, but I couldn't read the map directions.")
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
                        statusText.text = jsonResponse
                        speakText("I encountered a technical error.")
                        return@launch
                    }

                    // 👇 NEW LOGIC: Parse JSON, calculate distance, and format for Gemini
                    val formattedList = StringBuilder()
                    val tempPlacesList = mutableListOf<JSONObject>()

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
                            tempPlacesList.add(place)
                            val name = place.getJSONObject("displayName").getString("text")
                            val address = place.optString("formattedAddress", "Address not available")

                            // Get coordinates to calculate distance
                            val placeLoc = place.getJSONObject("location")
                            val lat = placeLoc.getDouble("latitude")
                            val lng = placeLoc.getDouble("longitude")

                            val distance = calculateDistanceMiles(location.latitude, location.longitude, lat, lng)

                            formattedList.append("${i + 1}. $name\n   Distance: $distance\n   Address: $address\n\n")
                        }

                        lastFoundPlaces = tempPlacesList
                        lastUserLocation = location

                    } catch (e: Exception) {
                        statusText.text = "Error parsing places: ${e.message}"
                        return@launch
                    }

                    // Show the clean list on screen immediately
                    statusText.text = formattedList.toString()

                    // Send to Gemini to read out politely
                    // Send to Gemini to read out politely
                    val prompt = """
                        Here is a list of nearby places with distances:
                        $formattedList
                        
                        Instructions:
                        1. Read the options clearly.
                        2. Mention the name and distance for each.
                        3. Be extremely concise. Do not add extra commentary.
                        4. End by asking: "Which one would you like?"
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

    /*private fun runTextRecognition(bitmap: Bitmap) {
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
    }*/

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                statusText.text = "TTS language not supported"
            }
        }
    }

    private fun speakText(text: String) {
        /*if (!::tts.isInitialized) return
        tts.stop()
        text.chunked(3000).forEach {
            tts.speak(it, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }*/

        // 1. Save the text so we can repeat it later
        lastSpokenText = text

        // 2. Ensure UI updates run on the main thread
        runOnUiThread {            // Reveal the repeat button if it's hidden
            if (btnRepeat.visibility == View.GONE) {
                btnRepeat.visibility = View.VISIBLE
            }

            // 3. Speak the text
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }
}
