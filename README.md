# Missed Call Forwarder (Android, personal/sideload)

When you get a **missed call**, this app reads the caller's number from the call
log, builds a WhatsApp click-to-chat (`wa.me`) link for it, and **sends an SMS**
with that info to a number you configure. Repeat calls from the same number
within a configurable window are suppressed.

This is designed for **personal, sideloaded use** — not the Play Store (see
"SMS permission" below).

## What it does

- Detects missed calls via `PHONE_STATE` transitions (RINGING → IDLE without
  OFFHOOK) and confirms the number from the call log.
- Excludes calls the system marks as **rejected** (only genuine misses forward).
  Caveat: some OEMs log a declined call as "missed", so a rejected call may
  occasionally slip through — a platform limitation.
- Sends an SMS to your configured destination using `SmsManager` (no tap needed,
  works while the phone is locked).
- Message uses a template with `{number}`, `{time}`, and `{wa}` (the `wa.me`
  link) placeholders.
- Dedup window (default 60 min) suppresses repeat forwards per number.
- History screen shows every detected call and its outcome (sent / suppressed /
  failed / disabled).
- Extras: master on/off, default-country setting for link formatting, dual-SIM
  send selection, battery-optimization prompt, boot receiver.

## Build

This project has no checked-in Gradle wrapper JAR. Easiest path:

1. Open the folder in **Android Studio** (Giraffe or newer). It will sync and
   generate the wrapper automatically.
2. Or from a machine with Gradle 8.7+ installed: run `gradle wrapper` once, then
   `./gradlew assembleDebug`.

Requirements: JDK 17, Android SDK 34. The APK is `app/build/outputs/apk/debug/`.

> Note: this was scaffolded on a machine without a JDK/Android SDK, so it has not
> been compiled here. Build in Android Studio and report any errors back.

## Install & configure

1. Install the debug APK on your phone (enable "install unknown apps" for your
   file manager).
2. Open the app and tap **Grant permissions** (phone state, call log, SMS,
   notifications).
3. Set the **Destination number** (where alerts go).
4. Set **Country code override** only if needed — the app auto-detects your
   region from the SIM/network, so local-format caller numbers usually convert
   into `wa.me` links correctly. Set it only if callers come from a different
   country than your SIM.
5. Adjust the **dedup minutes** and **message template** if you like.
6. Flip **Forwarding enabled** on.
7. Tap **Battery optimization settings** and exempt the app. On Xiaomi / Oppo /
   Vivo / Realme, also enable **Autostart** in system settings, or the OS may
   kill the background receiver.

## SMS permission (important)

`SEND_SMS` lets the app send messages silently. Google Play restricts this
permission to default SMS handlers and a few exempt categories, so a published
"missed call forwarder" would likely be rejected. For a **sideloaded personal
app on your own phone, this is fine** — that's the intended use here.

Each forward is a real SMS at your carrier's rate. The dedup window also acts as
a basic cost guard.

## How detection works (and its limits)

- `PHONE_STATE` fires multiple times per call and, on Android 9+, does not
  include the number. We infer "missed" from the state sequence and then read
  the number from the call log, retrying briefly because the log lags the
  broadcast.
- If the OS denies call-log access or the entry never appears, that call is
  skipped (logged, not forwarded).

## Project layout

```
app/src/main/java/com/example/missedcallforwarder/
  ForwarderApp.kt              Application + notification channel
  core/
    CallLogReader.kt           Reads latest missed call from the call log
    MissedCallProcessor.kt     Dedup + message build + send + log
    PhoneNumbers.kt            wa.me link via libphonenumber
    SmsSender.kt               SmsManager send (dual-SIM, multipart)
  data/
    AppDatabase.kt             Room DB
    ForwardLog.kt / Dao        History + dedup source
    SettingsStore.kt           DataStore-backed settings
  notify/Notifier.kt           Status notifications
  receiver/
    CallReceiver.kt            PHONE_STATE state machine
    BootReceiver.kt            Re-arm after reboot
  ui/
    MainActivity.kt            Compose settings + history screen
    MainViewModel.kt
    Permissions.kt
```

## Possible next steps

- Per-number allowlist / blocklist.
- Daily SMS cap as a hard cost ceiling.
- Delivery confirmation via `PendingIntent` callbacks.
