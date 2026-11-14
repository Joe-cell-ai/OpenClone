package org.openclone.app

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import org.openclone.app.data.ManagedWebViewPolicy

class ManagedWebViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("openclone_managed_webview", MODE_PRIVATE)
        val allowedHosts = preferences.getStringSet("allowed_hosts", emptySet()).orEmpty().toSet()
        android.webkit.CookieManager.getInstance().setAcceptCookie(false)
        val policy = ManagedWebViewPolicy(
            javaScriptEnabled = false,
            domStorageEnabled = false,
            allowFileAccess = false,
            allowContentAccess = false,
            mixedContentAllowed = false,
            allowedHosts = allowedHosts
        )
        setContent {
            var url by rememberSaveable { mutableStateOf("https://") }
            var message by rememberSaveable {
                mutableStateOf(if (allowedHosts.isEmpty()) "Add an HTTPS host in Extensions before opening a page." else "Only explicitly allowlisted HTTPS hosts and their subresources are loaded.")
            }
            val webView = remember { mutableStateOf<WebView?>(null) }
            BackHandler(enabled = webView.value != null) {
                if (webView.value?.canGoBack() == true) webView.value?.goBack() else finish()
            }
            DisposableEffect(Unit) {
                onDispose {
                    webView.value?.let { view ->
                        runCatching {
                            view.stopLoading()
                            view.loadUrl("about:blank")
                            view.clearHistory()
                            view.removeAllViews()
                            view.destroy()
                        }
                    }
                    webView.value = null
                }
            }
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Managed WebView", style = MaterialTheme.typography.headlineSmall)
                    Text("JavaScript, DOM storage, file access and mixed content are disabled. This browser is separate from cloned apps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.take(2048) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("HTTPS URL") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (policy.allows(url)) {
                                webView.value?.loadUrl(url)
                                message = "Loading allowlisted HTTPS page."
                            } else {
                                message = "Blocked. Add this exact host to the WebView allowlist in Extensions."
                            }
                        }) { Text("Load") }
                        Button(onClick = { webView.value?.reload() }, enabled = webView.value != null) { Text("Reload") }
                    }
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { context ->
                            WebView(context).apply {
                                policy.applyTo(this)
                                webView.value = this
                            }
                        },
                        update = { view -> if (webView.value !== view) webView.value = view }
                    )
                }
            }
        }
    }
}
