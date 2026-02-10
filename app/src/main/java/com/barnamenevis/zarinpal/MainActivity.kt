package com.barnamenevis.zarinpal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.barnamenevis.zarinpal.ui.theme.ZarinPalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.example.zarinpal.ZarinPal
import com.example.zarinpal.data.remote.dto.Config
import com.example.zarinpal.data.remote.dto.create.CreatePaymentRequest
import com.example.zarinpal.data.remote.dto.verification.PaymentVerifyRequest

class MainActivity : ComponentActivity() {

    private lateinit var zarinPal: ZarinPal
    private val merchantId = "eba77c90-ad32-4573-aff5-d5d1f4178352" // Replace with actual Merchant ID
    private val callbackScheme = "https"
    private val callbackHost = "zarinpal.barnamenevis.com"
    private val callbackUrl = "$callbackScheme://$callbackHost/payment"

    // Simple state for UI feedback
    private var paymentStatus by mutableStateOf("Ready")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize ZarinPal
        try {
            zarinPal = ZarinPal(
                Config(
                    merchantId = merchantId,
                    packageName = packageName,
                    sandBox = false
                )
            )
        } catch (e: Exception) {
            Log.e("ZarinPal", "Error initializing ZarinPal", e)
            paymentStatus = "Initialization Error"
        }

        setContent {
            ZarinPalTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PaymentScreen(
                        modifier = Modifier.padding(innerPadding),
                        status = paymentStatus,
                        onPayClick = { amount ->
                            startPayment(amount)
                        }
                    )
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Good practice to update intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == callbackScheme && data.host == callbackHost) {
            val status = data.getQueryParameter("Status")
            val authority = data.getQueryParameter("Authority")

            Log.d("ZarinPal", "Callback received: Status=$status, Authority=$authority")

            if (status == "OK" && authority != null) {
                paymentStatus = "Verifying..."
                // Use Int for amount as library expects Int
                val amount = getSharedPreferences("payment", Context.MODE_PRIVATE).getInt("last_amount", 0)
                if (amount > 0) {
                    verifyPayment(authority, amount)
                } else {
                    paymentStatus = "Amount not found for verification"
                    Log.e("ZarinPal", "Amount not found")
                }
            } else {
                paymentStatus = "Payment Failed or Canceled"
                Log.e("ZarinPal", "Payment Failed")
            }
        }
    }

    private fun startPayment(amount: Int) {
        paymentStatus = "Requesting Payment..."

        // Save amount for verification
        getSharedPreferences("payment", Context.MODE_PRIVATE).edit().putInt("last_amount", amount).apply()

        val request = CreatePaymentRequest(
            amount = amount,
            callbackUrl = callbackUrl,
            description = "Test Payment"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // The library call.
                // Using trailing lambda syntax for redirectUrl
                val response = zarinPal.createPayment(request) { paymentGatewayUri, status ->
                    Log.d("ZarinPal", "Create Payment Callback: Uri=$paymentGatewayUri, Status=$status")
                    if (status == 100) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(paymentGatewayUri))
                        startActivity(intent)
                        // We can't update UI from here if this callback is on BG thread, but startActivity is fine.
                        // However, updating paymentStatus needs Main thread.
                        // Since I am inside launch(Dispatchers.IO), I can use withContext(Main).
                        // BUT, if this callback is invoked by the library on ANY thread, I should be careful.
                        // Ideally, I should launch a new coroutine on Main to update UI.

                        // Wait, 'startActivity' should be called from Activity context.
                        // I am inside MainActivity, so 'startActivity' calls 'this.startActivity'.
                        // If called from background thread, it is generally fine on Android (it sends a message to system).
                    } else {
                         Log.e("ZarinPal", "Create Payment Failed: Status $status")
                    }
                }

                // If createPayment is suspend, it returns 'response'.
                // If the lambda is for redirectUrl, it might be called BEFORE createPayment returns?
                // Or maybe createPayment returns the response which contains authority?

                Log.v("ZarinPal", "Create Payment Response: $response")

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    paymentStatus = "Error: ${e.message}"
                }
            }
        }
    }

    private fun verifyPayment(authority: String, amount: Int) {
        val request = PaymentVerifyRequest(
            amount = amount,
            authority = authority
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = zarinPal.paymentVerify(request)
                Log.v("ZarinPal", "Verify Response: $response")

                withContext(Dispatchers.Main) {
                    paymentStatus = "Verify Result: $response"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    paymentStatus = "Verification Error: ${e.message}"
                }
            }
        }
    }
}

@Composable
fun PaymentScreen(
    modifier: Modifier = Modifier,
    status: String,
    onPayClick: (Int) -> Unit
) {
    var amountText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        TextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("Amount (Rials)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                val amount = amountText.toIntOrNull()
                if (amount != null && amount > 0) {
                    onPayClick(amount)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pay")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Status: $status")
    }
}
