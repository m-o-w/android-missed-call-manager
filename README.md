# Missed Call → Telegram (Android, personal/sideload)

When your **business phone** gets a missed call, this app reads the caller's
number, builds a WhatsApp click-to-chat (`wa.me`) link with a pre-filled
greeting, and sends it to you over **Telegram**. You open the link from your
primary phone (where WhatsApp Business lives) and tap send — one tap to start a
WhatsApp conversation with the lead.

Designed for **personal, sideloaded use**.

## Why Telegram (not SMS)

SMS forwarding hit two walls: Android's Restricted Settings block on the
`SEND_SMS` permission for sideloaded apps, and carrier-side blocking of
automated/app-sent SMS. Telegram sidesteps both — it sends over HTTPS, needs no
SMS permission, and is reliable. It's also multi-device, so a message sent from
the business phone appears instantly on your primary phone's Telegram.

## The flow

```
Lead calls business phone → no answer
  → app detects missed call, reads number from call log
  → builds wa.me link with your greeting pre-filled
  → POSTs the message to your Telegram bot (HTTPS)
  → message appears in Telegram on all your devices
  → on your primary phone, tap the link → WhatsApp Business opens the
    lead's chat, greeting pre-filled → you tap send
```

This is **semi-automatic by design**: the final send is one tap, which keeps it
within WhatsApp's terms. Fully automatic sending would require the WhatsApp
Business Cloud API.

## One-time Telegram setup (~5 min, in-app guided)

Tap **How to set up** in the app for the same steps:

1. In Telegram, open **@BotFather**, send `/newbot`, follow the prompts. Copy the
   **bot token** it gives you.
2. Open your new bot and tap **Start** (or send it any message).
3. In the app, paste the token and tap **Detect chat id** — it auto-fills your
   chat id.
4. **Save**, then **Send test message now** to confirm delivery.
5. Install Telegram on your primary phone too, so alerts arrive there.

## Permissions

- `READ_PHONE_STATE`, `READ_CALL_LOG` — detect missed calls and read the number.
- `INTERNET` — send to Telegram.
- `POST_NOTIFICATIONS` (optional) — status notifications.
- `RECEIVE_BOOT_COMPLETED` — re-arm after reboot.

No SMS permission, so no Restricted Settings hurdle.

## Build

Open in **Android Studio** (it generates the Gradle wrapper on sync), or:

```
JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./gradlew assembleDebug
```

Requirements: JDK 17, Android SDK 34. APK at `app/build/outputs/apk/debug/`.

## Install & configure

1. Install the debug APK on your **business phone** (the one with the SIM that
   receives lead calls).
2. Grant phone + call-log permissions (and notifications if prompted).
3. Complete the Telegram setup above.
4. Set your **greeting** and tweak the **message template** if you like.
5. Exempt the app from **battery optimization** (button in the app). On Xiaomi/
   Oppo/Vivo/Realme also enable **Autostart**.
6. Turn **Forwarding enabled** on.

## Features

- Missed-call detection (PHONE_STATE transitions + call-log confirmation),
  excludes rejected calls where the OS distinguishes them.
- **SIM filter**: respond to missed calls on SIM 1, SIM 2, or both. If a call's
  SIM can't be determined (OEM inconsistency), it's forwarded anyway so leads
  aren't lost.
- Telegram delivery with a pre-filled `wa.me` greeting link.
- **Durable retry**: the send runs in WorkManager with a network constraint and
  exponential backoff, so transient failures (no signal, flaky data, Telegram
  5xx/429) retry automatically and survive app/process restarts. Permanent
  errors (bad token/chat id) don't retry.
- Per-number dedup window (default 60 min).
- Daily message cap.
- Configurable greeting + message template (`{number}` `{time}` `{wa}`).
- Country auto-detection from SIM/network, with optional override.
- Save / discard, send-test-message, in-app setup guide.
- Self-dismissing permission and battery prompts.
- Forward history with per-call status (sent / suppressed / capped / failed /
  disabled / other SIM).

## Configuration internals

- **Settings** (DataStore): enabled, bot token, chat id, greeting, template,
  dedup minutes, daily cap, country override.
- **Room `forward_log`**: history + dedup source.
- Bot token is stored in app-private settings and never logged. Anyone with the
  token can send messages as your bot, so keep it private.

## Project layout

```
app/src/main/java/com/example/missedcallforwarder/
  ForwarderApp.kt              Application + notification channel
  core/
    CallLogReader.kt           Reads latest missed call (+ SIM slot) from the log
    SimResolver.kt             Maps call-log phone account to a physical SIM slot
    MissedCallProcessor.kt     Gating (enabled/SIM/dedup/cap) + one send attempt
    MessageBuilder.kt          Template + wa.me link with greeting
    PhoneNumbers.kt            wa.me formatting via libphonenumber
    DeviceRegion.kt            SIM/network region detection
    TelegramClient.kt          Bot API: sendMessage / getUpdates / getMe
  work/
    SendWorker.kt              Durable WorkManager send with backoff retry
  data/                        Room DB, ForwardLog, SettingsStore
  notify/Notifier.kt           Status notifications
  receiver/                    CallReceiver (PHONE_STATE), BootReceiver
  ui/                          MainActivity (Compose), MainViewModel, Permissions
```
