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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.missedcallforwarder.core.DeviceRegion
import com.example.missedcallforwarder.core.SimResolver
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

@Composable
fun AppScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val saved by vm.settings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

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
    var draft by remember(saved) { mutableStateOf(saved) }
    val dirty = draft != saved

    var permissionsGranted by remember { mutableStateOf(hasEssentialPermissions(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    val detectedRegion = remember(permissionsGranted) { DeviceRegion.detect(context) }
    var activeSims by remember { mutableStateOf(SimResolver.activeSims(context)) }
    var showSetupHelp by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }

    val connection by vm.connection.collectAsStateWithLifecycle()

    // Verify the token whenever the draft token settles (debounced). This drives
    // the connection status indicator without a manual button.
    LaunchedEffect(draft.botToken) {
        val token = draft.botToken.trim()
        if (token.isBlank()) {
            vm.verifyToken("")
        } else {
            kotlinx.coroutines.delay(600) // debounce typing
            vm.verifyToken(token)
        }
    }

    val telegramConfigured = draft.botToken.isNotBlank() && draft.chatId.isNotBlank()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = hasEssentialPermissions(context)
                batteryExempt = isBatteryExempt(context)
                activeSims = SimResolver.activeSims(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionsGranted = hasEssentialPermissions(context)
        activeSims = SimResolver.activeSims(context)
    }

    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (showSetupHelp) {
        TelegramSetupDialog(onDismiss = { showSetupHelp = false })
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Missed Call → Telegram") }) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
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
            // --- First-run hint: shown until Telegram is configured ---
            if (!telegramConfigured) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Get started", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Connect a Telegram bot so missed calls reach you.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { showSetupHelp = true }) { Text("Set up") }
                    }
                }
            }

            if (!permissionsGranted) {
                ElevatedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Permissions needed", style = MaterialTheme.typography.titleMedium)
                        Text("Phone and call-log access are required to detect missed calls. " +
                            "Notifications are optional but recommended for status alerts.")
                        Button(onClick = { permissionLauncher.launch(Permissions.all()) }) {
                            Text("Grant permissions")
                        }
                    }
                }
            }

            // --- Master toggle ---
            ElevatedCard {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Forwarding enabled", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (draft.enabled) "Missed calls will be sent to Telegram" else "Paused",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { on -> draft = draft.copy(enabled = on) }
                    )
                }
            }

            // --- Telegram connection ---
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Telegram", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showSetupHelp = true }) { Text("How to set up") }
                    }

                    ConnectionStatusRow(connection)

                    OutlinedTextField(
                        value = draft.botToken,
                        onValueChange = { v -> draft = draft.copy(botToken = v) },
                        label = { Text("Bot token") },
                        supportingText = { Text("From @BotFather. Keep this secret.") },
                        singleLine = true,
                        visualTransformation = if (tokenVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    imageVector = if (tokenVisible) Icons.Filled.VisibilityOff
                                        else Icons.Filled.Visibility,
                                    contentDescription = if (tokenVisible) "Hide token" else "Show token"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draft.chatId,
                        onValueChange = { v -> draft = draft.copy(chatId = v) },
                        label = { Text("Chat id (where alerts go)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            vm.detectChatId(draft.botToken.trim()) { id, msg ->
                                if (id != null) draft = draft.copy(chatId = id)
                                scope.launch { snackbarHost.showSnackbar(msg) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Detect chat id") }
                }
            }

            // --- Message ---
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Message", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = draft.greeting,
                        onValueChange = { v -> draft = draft.copy(greeting = v) },
                        label = { Text("WhatsApp greeting (pre-filled in the chat)") },
                        supportingText = { Text("Sent into the wa.me link's text. You confirm send on your phone.") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = draft.messageTemplate,
                        onValueChange = { v -> draft = draft.copy(messageTemplate = v) },
                        label = { Text("Telegram message template") },
                        supportingText = { Text("Placeholders: {number} {time} {wa}") },
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
                                    "Auto-detected: $detectedRegion. Set only if callers are from another country."
                                else
                                    "Used for local-format numbers that lack a country code."
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // --- Limits ---
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Call source & limits", style = MaterialTheme.typography.titleMedium)

                    SimFilterDropdown(
                        activeSims = activeSims,
                        selected = draft.simFilter,
                        onSelected = { v -> draft = draft.copy(simFilter = v) }
                    )

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
                        value = draft.dailyCapText,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }.take(5)
                            draft = draft.copy(dailyMessageCap = digits.toIntOrNull() ?: 0)
                        },
                        label = { Text("Daily message cap") },
                        supportingText = { Text("Max Telegram messages per rolling 24h. Blank or 0 = unlimited.") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // --- Test ---
            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Test", style = MaterialTheme.typography.titleMedium)
                    var testNumber by remember { mutableStateOf("+15551234567") }
                    OutlinedTextField(
                        value = testNumber,
                        onValueChange = { testNumber = it },
                        label = { Text("Sample caller number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (dirty) "Test uses your last saved settings. Save first to test pending changes."
                        else "Sends one [TEST] message to Telegram now. Ignores enable/dedup/cap.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = {
                            vm.sendTest(testNumber.trim()) { msg ->
                                scope.launch { snackbarHost.showSnackbar(msg) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Send test message now") }
                }
            }

            // --- Reliability (hidden once battery-exempt) ---
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

@Composable
private fun TelegramSetupDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("Connect Telegram") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("One-time setup, about 5 minutes:")
                SetupStep("1", "In Telegram, search for @BotFather and open it.")
                SetupStep("2", "Send /newbot, then follow the prompts to name your bot. " +
                    "BotFather replies with a bot token like 7712345678:AAE... — copy it into " +
                    "the Bot token field.")
                SetupStep("3", "Open your new bot (BotFather gives a t.me link) and tap Start, " +
                    "or send it any message. This lets the bot message you.")
                SetupStep("4", "Back here, tap \"Detect chat id\". It finds the chat you just " +
                    "started and fills in the Chat id automatically.")
                SetupStep("5", "Tap Save, then \"Send test message now\" to confirm it arrives " +
                    "in your Telegram.")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tip: install Telegram on your primary phone too — messages appear there " +
                        "automatically, and you tap the WhatsApp link from there.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}

@Composable
private fun SetupStep(num: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(num, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus) {
    val (text, color, icon) = when (status) {
        is ConnectionStatus.Connected ->
            Triple("Connected as @${status.botUsername}", Color(0xFF2E7D32), Icons.Filled.CheckCircle)
        ConnectionStatus.Checking ->
            Triple("Checking connection…", MaterialTheme.colorScheme.onSurfaceVariant, null)
        ConnectionStatus.Invalid ->
            Triple("Token rejected by Telegram", MaterialTheme.colorScheme.error, Icons.Filled.Error)
        ConnectionStatus.NotConfigured ->
            Triple("No bot token set", MaterialTheme.colorScheme.onSurfaceVariant, null)
        ConnectionStatus.Unknown ->
            Triple("Not checked yet", MaterialTheme.colorScheme.onSurfaceVariant, null)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (status is ConnectionStatus.Checking) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimFilterDropdown(
    activeSims: List<Pair<Int, String>>,
    selected: Int,
    onSelected: (Int) -> Unit
) {
    // Build options: "Both SIMs" plus a per-slot entry. Fall back to generic
    // SIM 1 / SIM 2 labels if carrier names aren't available (no permission).
    val options = buildList {
        add(Settings.SIM_BOTH to "Both SIMs")
        if (activeSims.isEmpty()) {
            add(Settings.SIM_1 to "SIM 1")
            add(Settings.SIM_2 to "SIM 2")
        } else {
            activeSims.forEach { (slot, carrier) -> add(slot to "SIM $slot ($carrier)") }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second
        ?: "Both SIMs"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Respond to calls on") },
            supportingText = { Text("If a call's SIM can't be determined, it's forwarded anyway.") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelected(value); expanded = false }
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
        ForwardStatus.SKIPPED_SIM -> "other SIM"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

// Numeric fields show "" for 0 so the user can clear them while typing.
private val Settings.dedupMinutesText: String get() = if (dedupMinutes == 0) "" else dedupMinutes.toString()
private val Settings.dailyCapText: String get() = if (dailyMessageCap == 0) "" else dailyMessageCap.toString()

// Normalize free-text fields once, at save time, so typing isn't disrupted.
private fun Settings.normalized(): Settings = copy(
    botToken = botToken.trim(),
    chatId = chatId.trim(),
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
