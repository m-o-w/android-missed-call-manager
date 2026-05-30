package com.example.missedcallforwarder.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.missedcallforwarder.core.DeviceRegion
import com.example.missedcallforwarder.core.SimInfo
import com.example.missedcallforwarder.core.SimOption
import com.example.missedcallforwarder.data.ForwardLog
import com.example.missedcallforwarder.data.ForwardStatus
import com.example.missedcallforwarder.data.Settings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val saved by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    // Until settings load from disk, show a spinner so we never seed the form
    // with placeholder defaults (which caused fields to fight user input).
    val loaded = saved
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    SettingsForm(vm = vm, saved = loaded, history = history, context = context)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsForm(
    vm: MainViewModel,
    saved: Settings,
    history: List<ForwardLog>,
    context: Context
) {
    // --- Local editable draft. The form edits THIS, not the stored flow. ---
    // Keyed on `saved` so an external change (or first load) re-seeds the draft.
    var draft by remember(saved) { mutableStateOf(saved) }
    val dirty = draft != saved

    var permissionsGranted by remember { mutableStateOf(hasEssentialPermissions(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var simOptions by remember { mutableStateOf(SimInfo.options(context)) }
    val detectedRegion = remember(permissionsGranted) { DeviceRegion.detect(context) }

    // Re-check permission + battery state every time the app returns to the
    // foreground, so the prompt cards hide when satisfied and reappear if the
    // user later revokes a setting from outside the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = hasEssentialPermissions(context)
                batteryExempt = isBatteryExempt(context)
                simOptions = SimInfo.options(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Re-check from the system rather than trusting the result map, and gate
        // on essential permissions only (optional ones must not block the app).
        permissionsGranted = hasEssentialPermissions(context)
        // SIM details require READ_PHONE_STATE; refresh once it's granted.
        simOptions = SimInfo.options(context)
    }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Missed Call Forwarder") }) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            // Persistent Save bar so the user always knows the config state.
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { draft = saved },
                        enabled = dirty,
                        modifier = Modifier.weight(1f)
                    ) { Text("Discard") }
                    Button(
                        onClick = {
                            vm.save(draft.normalized())
                            scope.launch { snackbarHost.showSnackbar("Settings saved") }
                        },
                        enabled = dirty,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (dirty) "Save" else "Saved") }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!permissionsGranted) {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                        Text("Phone state, call log, and SMS access are required for forwarding. " +
                            "Notifications are optional but recommended for status alerts.")
                        Button(onClick = { permissionLauncher.launch(Permissions.all()) }) {
                            Text("Grant permissions")
                        }
                    }
                }
            }

            // --- Master toggle (applies immediately; it's not a text field) ---
            ElevatedCard {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Forwarding enabled", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (draft.enabled) "Missed calls will be forwarded" else "Paused",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { on -> draft = draft.copy(enabled = on) }
                    )
                }
            }

            // --- Configuration ---
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Configuration", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = draft.destinationNumber,
                        onValueChange = { v -> draft = draft.copy(destinationNumber = v) },
                        label = { Text("Destination number (where alerts are sent)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draft.defaultCountryCode,
                        onValueChange = { v -> draft = draft.copy(defaultCountryCode = v) },
                        label = { Text("Country code override (optional)") },
                        placeholder = { Text(detectedRegion ?: "auto") },
                        supportingText = {
                            Text(
                                if (detectedRegion != null)
                                    "Auto-detected: $detectedRegion. Only set this if caller numbers " +
                                        "come from a different country."
                                else
                                    "Used only for local-format numbers that lack a country code."
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Include WhatsApp link in SMS")
                        Switch(
                            checked = draft.includeWhatsAppLink,
                            onCheckedChange = { on -> draft = draft.copy(includeWhatsAppLink = on) }
                        )
                    }

                    OutlinedTextField(
                        value = draft.dedupMinutesText,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }.take(5)
                            draft = draft.copy(dedupMinutes = digits.toIntOrNull() ?: 0)
                        },
                        label = { Text("Suppress repeats from same number (minutes)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draft.dailySmsCapText,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }.take(5)
                            draft = draft.copy(dailySmsCap = digits.toIntOrNull() ?: 0)
                        },
                        label = { Text("Daily SMS cap (cost guard)") },
                        supportingText = { Text("Max SMS sent per rolling 24h. Blank or 0 = unlimited.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draft.messageTemplate,
                        onValueChange = { v -> draft = draft.copy(messageTemplate = v) },
                        label = { Text("Message template") },
                        supportingText = { Text("Placeholders: {number} {time} {wa}") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    SimDropdown(
                        options = simOptions,
                        selectedId = draft.sendSubscriptionId,
                        onSelected = { id -> draft = draft.copy(sendSubscriptionId = id) }
                    )

                    HorizontalDivider()

                    // --- Send a test SMS through the real send path ---
                    var testNumber by remember { mutableStateOf("+15551234567") }
                    OutlinedTextField(
                        value = testNumber,
                        onValueChange = { testNumber = it },
                        label = { Text("Sample caller number (for test)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (dirty) "Test uses your last saved settings. Save first to test pending changes."
                        else "Sends one [TEST] SMS to your destination now. Ignores enable/dedup/cap.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            vm.sendTestSms(testNumber.trim()) { msg ->
                                scope.launch { snackbarHost.showSnackbar(msg) }
                            }
                        },
                        enabled = permissionsGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Send test SMS now") }
                }
            }

            // --- Reliability (hidden once the app is battery-exempt) ---
            if (!batteryExempt) {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Improve reliability", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Some phones kill background apps, which stops forwarding. " +
                                "Exempt this app from battery optimization. On Xiaomi/Oppo/Vivo/" +
                                "Realme, also enable Autostart in system settings (no app can " +
                                "detect that one, so this tip won't auto-dismiss for it).",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = { requestBatteryExemption(context) }) {
                            Text("Battery optimization settings")
                        }
                    }
                }
            }

            // --- History ---
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("History", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { vm.clearHistory() }) { Text("Clear") }
                    }
                    if (history.isEmpty()) {
                        Text("No missed calls processed yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 400.dp)) {
                            items(history) { entry -> HistoryRow(entry) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimDropdown(
    options: List<SimOption>,
    selectedId: Int,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.id == selectedId }?.label
        ?: options.firstOrNull()?.label ?: "Default SIM"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("SIM used to send SMS") },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ForwardLog) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(entry.callerNumber.ifBlank { "unknown" }, style = MaterialTheme.typography.bodyLarge)
            StatusChip(entry.status)
        }
        Text(fmt.format(Date(entry.callTime)), style = MaterialTheme.typography.bodySmall)
        entry.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    HorizontalDivider()
}

@Composable
private fun StatusChip(status: ForwardStatus) {
    val label = when (status) {
        ForwardStatus.SENT -> "sent"
        ForwardStatus.SUPPRESSED -> "suppressed"
        ForwardStatus.FAILED -> "failed"
        ForwardStatus.SKIPPED_DISABLED -> "disabled"
        ForwardStatus.CAPPED -> "capped"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

// Numeric fields show "" for 0 so the user can clear them while typing.
private val Settings.dedupMinutesText: String get() = if (dedupMinutes == 0) "" else dedupMinutes.toString()
private val Settings.dailySmsCapText: String get() = if (dailySmsCap == 0) "" else dailySmsCap.toString()

// Normalize free-text fields once, at save time, so typing isn't disrupted.
private fun Settings.normalized(): Settings = copy(
    destinationNumber = destinationNumber.trim(),
    defaultCountryCode = defaultCountryCode.trim().uppercase()
)

private fun hasEssentialPermissions(context: Context): Boolean =
    Permissions.essential.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun isBatteryExempt(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestBatteryExemption(context: Context) {
    val pkg = context.packageName
    val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:$pkg")
    }
    context.startActivity(intent)
}
