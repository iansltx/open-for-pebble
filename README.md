# open-pebble

Open Avigilon Alta–controlled doors with your Pebble Time 2.

```
┌──────────────┐  AppMessage   ┌────────────────────┐   intent w/      ┌──────────────────┐
│ Pebble Time 2│◄─────────────►│ Alta Pebble Bridge  │  SHORTCUT extras │ Avigilon Alta    │
│ "Alta Doors" │ (BT via the  │ (Android companion)│ ────────────────►│ Open (first-party│
│ watch app    │  Pebble app) │                     │                  │ app) unlocks    │
└──────────────┘               └────────────────────┘                  └──────────────────┘
                                        │
                                        ▼ (optional, one-time)
                            helium.prod.openpath.com — sign in to
                            discover door names/IDs for curation
```

## Why a companion app (and why not the Pebble settings hooks)

Short answer: Pebble's built-in hooks (PebbleKit JS, `Pebble.openURL` settings
pages) run inside the Pebble app's sandbox. They can make network requests but
they cannot talk to the Alta Open app, and — more importantly — **unlocking a
door requires security material that only the first-party app has**: an
enrolled mobile credential, per-device ECDSA keys in the Android keystore,
AWS IoT (MQTT) credentials for the site's access-control units, and a BLE
stack for readers. Re-enrolling our own device identity is possible in theory
but would be a fragile re-implementation of the whole Openpath SDK.

Instead, the companion drives the first-party app through the one integration
seam it exposes: its app-shortcut handler. See
[`docs/alta-open-internals.md`](docs/alta-open-internals.md) for how the Alta
Open APK was reverse-engineered, what that seam is, and the full map of its
cloud API.

**What you get:**

- Curated door list on the watch (filtering happens in the companion, as you
  suggested — the watch only ever sees your picked doors).
- Select a door on the watch → the phone triggers Alta Open → door unlocks
  over the same path the Alta app uses (BLE to the reader when in range,
  controller over Wi-Fi/MQTT otherwise).
- Feedback on the watch (vibration + status), with a phone-notification
  fallback if Android blocks a background activity start.

**Known limitations (by design of the Alta app):**

- If the door is *out of BLE range* and you have remote-unlock permission,
  Alta Open shows a confirmation dialog **on the phone** ("Are you sure you
  want to unlock X? This can pose a security risk…"). That's their security
  model; when you're standing at the door (the normal watch use case, phone
  in pocket within ~10–30 m of the reader) the unlock goes through directly.
- The Alta Open app must be installed and signed in (its foreground service
  keeps it alive; it does not need to be in the foreground).
- The trigger launches Alta Open's MainActivity, so the phone screen may
  briefly show the Alta app on Android 10+ unless you grant the bridge
  "Display over other apps" (which also allows triggering with the screen
  off / locked).

## Components

| Path | What it is |
|---|---|
| `pebble/` | Watch app (C, emery — Pebble Time 2). Menu of doors, sends unlock requests, shows results. Touch-enabled: opts into touch navigation (swipe/tap the door list, tap the status card to dismiss it). Builds with the Pebble SDK (`pebble build` → `build/pebble.pbw`). |
| `companion/` | Android app (Kotlin, **zero third-party dependencies**). Curates doors, speaks the PebbleKit broadcast protocol, triggers Alta Open. Auto-detects the phone-side relay — the current Core Devices app (`coredevices.coreapp`, JSON dialect) or the legacy Pebble app (`com.getpebble.android`, binary dialect) — and speaks the matching wire format both ways. |
| `docs/alta-open-internals.md` | Reverse-engineering findings from `Avigilon Alta Open.apk`. |

The PebbleKit wire protocol is vendored in
`companion/app/src/main/java/dev/ian/openpebble/pebble/PebbleBridge.kt`
(the old `com.getpebble.android:pebblekit` artifact is archived; the protocol
is a handful of documented broadcasts).

## Setup

### 1. Watch app (Pebble SDK)

The PBW is already built at `pebble/build/pebble.pbw` (or rebuild with the
[Pebble SDK](https://developer.rebble.io/sdk/) — Core Devices' `pebble-tool`):

```sh
uv tool install pebble-tool --python 3.13
pebble sdk install latest
cd pebble && pebble build      # produces build/pebble.pbw
```

Install it on the watch with the phone on the same Wi-Fi (the IP is shown in
the Pebble phone app's settings):

```sh
pebble install --phone <PHONE_IP> build/pebble.pbw
```

(The web IDE [CloudPebble](https://cloudpebble.repebble.com/) also works —
create an Emery project with the UUID above and paste in `src/c/main.c`.)

The watch app UUID is `2f9a7c41-5e3b-4d88-a6c2-7b1e0d5f4a33` (from
`pebble/package.json`); the companion expects this by default.

### 2. Companion app (Android Studio or command line)

The APK is already built at
`companion/app/build/outputs/apk/debug/app-debug.apk` (debug-signed,
installable directly). To rebuild: open `companion/` in Android Studio and let
Gradle sync (AGP 8.5, Kotlin 1.9), or from the command line with JDK 17:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=~/Library/Android/sdk
gradle -p companion assembleDebug
```

Install on the phone that is paired to your watch and has **Alta Open
installed and signed in**:

```sh
adb install companion/app/build/outputs/apk/debug/app-debug.apk
```

(or copy the APK to the phone and tap it — allow "Install unknown apps".)

### 3. Configure doors

Two ways to get your door names + entry IDs into the bridge:

- **Discover (preferred):** tap *Discover doors (sign in to Open)* in the
  companion, sign in with your Alta/Open account email + password (TOTP if
  your org uses MFA). The bridge logs into the same `helium.prod.openpath.com`
  API the Alta app uses, lists your credentials/ACUs, and offers found
  entries/readers as checkboxes. The API response shapes are not fully
  documented — the app shows a raw API debug log after the first run so the
  parser can be tightened against real data (see "End-to-end test checklist").
  Credentials are not stored; only the returned API token stays on-device.
- **Manual:** tap *Add door manually* — name + entry ID + type. (Entry IDs are
  visible to org admins in the Alta web portal, and the discovery flow aims
  to make this unnecessary.)

Then open **Alta Doors** on the watch: it sends a hello, the bridge pushes the
curated list, and you're set. Re-open the watch app any time to resync (or
choose the *Sync doors* row).

## Usage

1. Walk to a door, raise the watch, open **Alta Doors**.
2. Select the door. The watch shows *Requesting…* → *Unlock requested ✓*,
   and the reader unlocks (the phone does the BLE/radio work from your
   pocket).
3. If the phone can't trigger (missing overlay permission on Android 10+),
   you'll get a tap-to-confirm notification instead.

On a Pebble Time 2 / Core Time 2 you can also use the touchscreen: swipe up or
down to scroll the door list and tap a door to unlock it, then tap the status
card to dismiss it. The buttons keep working exactly as before. The app opts
into touch navigation at startup, so this works once *Touch navigation* is
enabled in the watch's system settings (Core Devices firmware gates
touch support for third-party apps on that setting).

## End-to-end test checklist

Verified live (Sept 2026, Core Devices app + Time 2 + Hyde Park ATX entry):

- [x] Companion ↔ phone-app AppMessage round-trip (HELLO → door list).
- [x] Watch unlock request → Alta Open `quickActionShortcut` handling with
      the phone **screen on**: door unlocks, no confirmation modal (in range).
- [x] Cloud sign-in + discovery against `helium.prod.openpath.com`
      (48 entries / 40 ACUs enumerated, no provisioning needed).
- [ ] Same unlock with **screen off/locked** after granting "Display over
      other apps".
- [ ] Out-of-range behavior (this account has no remote-unlock permission, so
      expect Alta's "out of range" refusal rather than a confirmation dialog).
- [ ] Touch navigation on the Time 2: swipe-scroll + tap-select on the door
      list, tap-to-dismiss on the status card (with *Touch navigation* enabled
      in watch settings).

## Security & privacy notes

- The bridge stores your curated door list and (optionally) an Open API token
  in app-private storage. Door names/IDs stay on your devices.
- Triggering unlocks is possible for *any* app on your phone via the same
  exported activity extras — nothing here reduces the Alta app's security
  posture below what any other app on the device could already do.
- The remote-unlock confirmation modal in Alta Open is deliberate protection
  against exactly the kind of indirect trigger we're doing; this project
  intentionally does not attempt to bypass it.

## Repository layout note

`Avigilon Alta Open.apk` (the input), `jadx-out/`, `decompiled.js`,
`apk-raw/` and `apktool-out/` are reverse-engineering **artifacts**; they are
large and can be deleted after reading the docs. To regenerate them:

```sh
jadx -d jadx-out "Avigilon Alta Open.apk"                 # Java sources
.venv/bin/hbc-decompiler apk-raw/assets/index.android.bundle decompiled.js
apktool d -o apktool-out "Avigilon Alta Open.apk"          # manifest/resources
```