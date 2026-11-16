package com.healthscan.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.healthscan.app.ui.HealthScanApp
import com.healthscan.app.viewmodel.ScanViewModel

class MainActivity : ComponentActivity() {

    private companion object {
        const val TAG = "HealthScanScan"
    }

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleSharedIntent(intent)
        setContent { HealthScanApp(viewModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uri = streamUris(intent).lastOrNull() ?: clipUri(intent) ?: intent.data
        if (uri == null) {
            Log.w(TAG, "no usable EXTRA_STREAM in $action")
            return
        }
        Log.i(TAG, "shared $action uri=$uri")
        // A retained ACTION_SEND intent would otherwise re-run the scan on every activity recreation.
        setIntent(Intent())
        viewModel.scan(uri)
    }

    private fun streamUris(intent: Intent): List<Uri> {
        val extras = intent.extras ?: return emptyList()
        extras.classLoader = javaClass.classLoader
        val raw = extras.get(Intent.EXTRA_STREAM)
        val items = if (raw is Collection<*>) raw.toList() else listOf(raw)
        return items.mapNotNull { item ->
            when (item) {
                is Uri -> item
                is CharSequence -> item.toString().takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                else -> null
            }
        }
    }

    private fun clipUri(intent: Intent): Uri? {
        val clip = intent.clipData ?: return null
        return (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it).uri }
            .lastOrNull()
    }
}
