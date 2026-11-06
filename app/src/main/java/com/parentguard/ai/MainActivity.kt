package com.parentguard.ai

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.text.KeyboardOptions

private const val API_BASE_URL =
    "https://parentguardai-render-api.onrender.com"

private val httpClient = OkHttpClient()
private val mainHandler = Handler(Looper.getMainLooper())
private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

/**
 * Sends an authenticated POST request to the Render API.
 */
private fun postToRender(
    path: String,
    idToken: String,
    body: JSONObject,
    onComplete: (JSONObject?, String?) -> Unit
) {
    val requestBody = body.toString()
        .toRequestBody(jsonMediaType)

    val request = Request.Builder()
        .url("$API_BASE_URL/$path")
        .header("Authorization", "Bearer $idToken")
        .post(requestBody)
        .build()

    httpClient.newCall(request).enqueue(object : okhttp3.Callback {
        override fun onFailure(
            call: okhttp3.Call,
            e: IOException
        ) {
            mainHandler.post {
                onComplete(
                    null,
                    "Network error. Check your internet connection and try again."
                )
            }
        }

        override fun onResponse(
            call: okhttp3.Call,
            response: okhttp3.Response
        ) {
            response.use {
                val responseText = it.body?.string().orEmpty()

                val responseJson = try {
                    JSONObject(responseText)
                } catch (_: Exception) {
                    JSONObject()
                }

                mainHandler.post {
                    if (it.isSuccessful) {
                        onComplete(responseJson, null)
                    } else {
                        val errorMessage = responseJson.optString(
                            "error",
                            "Request failed (HTTP ${it.code})."
                        )
                        onComplete(null, errorMessage)
                    }
                }
            }
        }
    })
}

/**
 * Retrieves a fresh Firebase ID token and calls the Render API.
 */
private fun authenticatedPost(
    path: String,
    body: JSONObject,
    onComplete: (JSONObject?, String?) -> Unit
) {
    val user = FirebaseAuth.getInstance().currentUser

    if (user == null) {
        onComplete(null, "Please sign in again.")
        return
    }

    user.getIdToken(false)
        .addOnSuccessListener { tokenResult ->
            val token = tokenResult.token

            if (token.isNullOrBlank()) {
                onComplete(null, "Could not obtain your authentication token.")
                return@addOnSuccessListener
            }

            postToRender(path, token, body, onComplete)
        }
        .addOnFailureListener {
            onComplete(
                null,
                "Could not verify your account. Please sign in again."
            )
        }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LoginScreen()
                }
            }
        }
    }
}

@Composable
fun LoginScreen() {
    val auth = remember { FirebaseAuth.getInstance() }
    val database = remember { FirebaseDatabase.getInstance().reference }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Parent") }
    var isRegister by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var savedRole by remember { mutableStateOf("") }
    var signedInEmail by remember { mutableStateOf(auth.currentUser?.email) }

    LaunchedEffect(signedInEmail) {
        val user = auth.currentUser

        if (signedInEmail != null && user != null &&
            savedRole.isBlank() && !loading
        ) {
            loading = true

            database.child("users")
                .child(user.uid)
                .get()
                .addOnSuccessListener { snapshot ->
                    savedRole = snapshot.child("role")
                        .getValue(String::class.java) ?: ""

                    if (savedRole != "Parent" && savedRole != "Child") {
                        message = "Account role is missing or invalid."
                    }

                    loading = false
                }
                .addOnFailureListener { error ->
                    message = error.localizedMessage
                        ?: "Could not load your account."
                    loading = false
                }
        }
    }

    if (signedInEmail != null) {
        when (savedRole) {
            "Parent" -> ParentDashboard(
                email = signedInEmail!!,
                onSignOut = {
                    auth.signOut()
                    signedInEmail = null
                    savedRole = ""
                    password = ""
                    message = "You have signed out."
                }
            )

            "Child" -> ChildDashboard(
                email = signedInEmail!!,
                onSignOut = {
                    auth.signOut()
                    signedInEmail = null
                    savedRole = ""
                    password = ""
                    message = "You have signed out."
                }
            )

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "ParentGuard AI",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (loading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            message.ifBlank {
                                "Unable to load your account role."
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                auth.signOut()
                                signedInEmail = null
                                savedRole = ""
                                password = ""
                                loading = false
                                message = ""
                            }
                        ) {
                            Text("Back to Login")
                        }
                    }
                }
            }
        }

        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "ParentGuard AI",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("Smart Child Usage Monitoring")
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = if (isRegister) "Create Account" else "Login",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("Choose your role")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            listOf("Parent", "Child").forEach { option ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = role == option,
                            onClick = { role = option }
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = role == option,
                        onClick = { role = option }
                    )
                    Text(option)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email address") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email
            ),
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password
            ),
            singleLine = true,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                val cleanEmail = email.trim()
                val enteredPassword = password
                val registering = isRegister
                val selectedRole = role

                if (cleanEmail.isEmpty() || enteredPassword.isBlank()) {
                    message = "Enter your email and password."
                    return@Button
                }

                if (registering && enteredPassword.length < 6) {
                    message = "Password must contain at least 6 characters."
                    return@Button
                }

                loading = true
                message = ""

                val task = if (registering) {
                    auth.createUserWithEmailAndPassword(
                        cleanEmail,
                        enteredPassword
                    )
                } else {
                    auth.signInWithEmailAndPassword(
                        cleanEmail,
                        enteredPassword
                    )
                }

                task.addOnCompleteListener { result ->
                    if (!result.isSuccessful) {
                        loading = false
                        message = result.exception?.localizedMessage
                            ?: "Authentication failed."
                        return@addOnCompleteListener
                    }

                    val user = auth.currentUser

                    if (user == null) {
                        loading = false
                        message = "Could not load the signed-in user."
                        return@addOnCompleteListener
                    }

                    val userRef = database.child("users").child(user.uid)

                    if (registering) {
                        val profile = mapOf(
                            "email" to user.email,
                            "role" to selectedRole,
                            "createdAt" to ServerValue.TIMESTAMP
                        )

                        userRef.setValue(profile)
                            .addOnSuccessListener {
                                savedRole = selectedRole
                                signedInEmail = user.email
                                message = ""
                                loading = false
                            }
                            .addOnFailureListener { error ->
                                auth.signOut()
                                signedInEmail = null
                                savedRole = ""
                                loading = false
                                message = "Could not save profile: " +
                                        (error.localizedMessage ?: "Unknown error")
                            }
                    } else {
                        userRef.get()
                            .addOnSuccessListener { snapshot ->
                                if (!snapshot.exists()) {
                                    auth.signOut()
                                    signedInEmail = null
                                    savedRole = ""
                                    message =
                                        "Profile not found. Please register again."
                                } else {
                                    val fetchedRole = snapshot.child("role")
                                        .getValue(String::class.java) ?: ""

                                    if (fetchedRole != "Parent" &&
                                        fetchedRole != "Child"
                                    ) {
                                        auth.signOut()
                                        signedInEmail = null
                                        savedRole = ""
                                        message =
                                            "Invalid account role. Contact support."
                                    } else {
                                        savedRole = fetchedRole
                                        signedInEmail = user.email
                                        message = ""
                                    }
                                }

                                loading = false
                            }
                            .addOnFailureListener { error ->
                                auth.signOut()
                                signedInEmail = null
                                savedRole = ""
                                loading = false
                                message = "Could not load profile: " +
                                        (error.localizedMessage ?: "Unknown error")
                            }
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    loading -> "Please wait..."
                    isRegister -> "Create Account"
                    else -> "Login"
                }
            )
        }

        TextButton(
            onClick = {
                isRegister = !isRegister
                message = ""
            },
            enabled = !loading
        ) {
            Text(
                if (isRegister) {
                    "Already registered? Login"
                } else {
                    "New user? Create an account"
                }
            )
        }

        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun ParentDashboard(
    email: String,
    onSignOut: () -> Unit
) {
    var pairingToken by remember { mutableStateOf("") }
    var expiresAt by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text("ParentGuard AI", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Parent Dashboard", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Welcome, $email")
        Spacer(modifier = Modifier.height(24.dp))

        DashboardCard(
            title = "Connect Child Device",
            description = "Generate a temporary QR code. Ask your child to scan it within 2 minutes."
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                loading = true
                message = ""
                pairingToken = ""
                expiresAt = 0L
                authenticatedPost("createPairingQR", JSONObject()) { data, error ->
                    if (error != null) {
                        message = error
                    } else {
                        val token = data?.optString("token").orEmpty()
                        val expiry = data?.optLong("expiresAt", 0L) ?: 0L
                        if (token.matches(Regex("[a-fA-F0-9]{64}")) && expiry > 0L) {
                            pairingToken = token
                            expiresAt = expiry
                            message = "QR code generated. Ask your child to scan it now."
                        } else {
                            message = "The server returned an invalid QR response."
                        }
                    }
                    loading = false
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading) "Generating QR code..." else "Generate Pairing QR Code")
        }

        if (loading) {
            Spacer(modifier = Modifier.height(12.dp))
            CircularProgressIndicator()
        }

        if (pairingToken.isNotBlank()) {
            val qrBitmap = remember(pairingToken) { createQrBitmap(pairingToken) }
            Spacer(modifier = Modifier.height(20.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Scan this QR code", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (qrBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Parent-child pairing QR code",
                            modifier = Modifier.fillMaxWidth(0.8f).height(240.dp)
                        )
                    } else {
                        Text("Could not create QR image. Generate a new one.")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("This QR code expires after 2 minutes.")
                    Text(
                        "Expires at: " + SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                            .format(Date(expiresAt))
                    )
                }
            }
        }

        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(message, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(24.dp))
        DashboardCard(title = "Today's Screen Time", description = "Not available yet")
        DashboardCard(title = "Daily Screen-Time Limit", description = "Not configured yet")
        DashboardCard(title = "Study and Sleep Mode", description = "Not configured yet")
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign Out")
        }
    }
}

@Composable
fun ChildDashboard(
    email: String,
    onSignOut: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text("ParentGuard AI", fontSize = 28.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Child Dashboard", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Welcome, $email")
        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Connect to Parent", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Ask your parent to generate a QR code, then scan it below.")
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        message = ""
                        loading = true
                        val scanner = GmsBarcodeScanning.getClient(context)
                        scanner.startScan()
                            .addOnSuccessListener { barcode ->
                                val token = barcode?.rawValue.orEmpty().trim()
                                if (token.matches(Regex("[a-fA-F0-9]{64}"))) {
                                    authenticatedPost(
                                        path = "redeemPairingQR",
                                        body = JSONObject().put("token", token)
                                    ) { data, error ->
                                        if (error != null) {
                                            message = error
                                        } else {
                                            message = data?.optString(
                                                "message", "Parent account linked successfully."
                                            ) ?: "Parent account linked successfully."
                                        }
                                        loading = false
                                    }
                                } else {
                                    loading = false
                                    message = "That QR code is not a valid ParentGuard pairing code."
                                }
                            }
                            .addOnCanceledListener {
                                loading = false
                                message = "QR scanning was cancelled."
                            }
                            .addOnFailureListener { error ->
                                loading = false
                                message = error.localizedMessage ?: "Could not scan the QR code."
                            }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "Connecting..." else "Scan Parent QR Code")
                }
                if (loading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
                if (message.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        DashboardCard(title = "Today's Screen Time", description = "Not available yet")
        DashboardCard(title = "Daily Screen-Time Limit", description = "Not configured yet")
        DashboardCard(title = "Study Mode", description = "Not configured yet")
        DashboardCard(title = "Sleep Mode", description = "Not configured yet")
        DashboardCard(title = "Break Reminders", description = "Coming soon")
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign Out")
        }
    }
}

private fun createQrBitmap(content: String): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600)
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.RGB_565)
        for (x in 0 until 600) {
            for (y in 0 until 600) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}

@Composable
fun DashboardCard(
    title: String,
    description: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(text = description)
        }
    }
}
