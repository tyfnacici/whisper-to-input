// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.whispertoinput

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIC_REQUEST_CODE = 200
private const val SAVE_DEBOUNCE_MS = 500L

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private var loaded = false

    private lateinit var endpointLayout: TextInputLayout
    private lateinit var endpointField: TextInputEditText
    private lateinit var connectionLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        endpointLayout = findViewById(R.id.layout_endpoint)
        endpointField = findViewById(R.id.field_endpoint)
        connectionLabel = findViewById(R.id.chip_connection)

        findViewById<TextView>(R.id.footer).text =
            getString(R.string.footer_autosave, packageManager.getPackageInfo(packageName, 0).versionName)
        findViewById<Button>(R.id.btn_test).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.btn_grant_mic).setOnClickListener { requestMicrophone() }

        bindSwitch(R.id.switch_auto_start, AUTO_RECORDING_START, default = true)
        bindSwitch(R.id.switch_auto_switch, AUTO_SWITCH_BACK, default = false)
        bindEndpoint()

        // Opened by the keyboard because the permission is missing: ask right away.
        if (!hasMicrophone()) requestMicrophone()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRow()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ endpoint

    private fun bindEndpoint() {
        scope.launch {
            endpointField.setText(readEndpoint())
            loaded = true
        }
        endpointField.doAfterTextChanged { text ->
            if (!loaded) return@doAfterTextChanged
            val value = text?.toString().orEmpty().trim()
            val valid = Endpoint.isValid(value)
            endpointLayout.error = if (valid || value.isEmpty()) null else getString(R.string.error_endpoint_invalid)
            if (!valid) return@doAfterTextChanged
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                dataStore.edit { it[ENDPOINT] = value }
            }
        }
    }

    private fun testConnection() {
        val value = endpointField.text?.toString().orEmpty().trim()
        if (!Endpoint.isValid(value)) {
            endpointLayout.error = getString(R.string.error_endpoint_invalid)
            return
        }
        connectionLabel.setText(R.string.conn_testing)
        scope.launch {
            val ms = ServerHealth.ping(value)
            if (ms != null) {
                connectionLabel.text = getString(R.string.conn_ok, ms)
                connectionLabel.setTextColor(getColor(R.color.brand_online))
            } else {
                connectionLabel.setText(R.string.conn_fail)
                connectionLabel.setTextColor(getColor(R.color.brand_error))
            }
        }
    }

    // ------------------------------------------------------------------ switches

    private fun bindSwitch(viewId: Int, key: Preferences.Key<Boolean>, default: Boolean) {
        val switch = findViewById<MaterialSwitch>(viewId)
        scope.launch {
            switch.isChecked = readBoolean(key, default)
            switch.setOnCheckedChangeListener { _, checked ->
                scope.launch { dataStore.edit { it[key] = checked } }
            }
        }
    }

    // --------------------------------------------------------------- permissions

    private fun hasMicrophone() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshPermissionRow() {
        val granted = hasMicrophone()
        findViewById<View>(R.id.chip_mic).visibility = if (granted) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_grant_mic).visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun requestMicrophone() {
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissionRow()
        if (requestCode == MIC_REQUEST_CODE && !hasMicrophone() &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            // Denied for good: the system dialog no longer appears, so send the user to app settings.
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }
}
