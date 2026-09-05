# Alta Doors — Pebble watch app

A door list for Avigilon Alta-controlled sites, driven by the companion app
(`../companion`). Pure C, no JS — the phone companion does all networking and
the unlock triggering.

## Files

- `package.json` — project manifest (UUID `2f9a7c41-5e3b-4d88-a6c2-7b1e0d5f4a33`, platform `emery` = Pebble Time 2)
- `wscript` — standard SDK build rules
- `src/c/main.c` — the app
- `build/pebble.pbw` — built artifact, ready to sideload

## Building

With the [Pebble SDK](https://developer.rebble.io/sdk/) (Core Devices'
`pebble-tool`):

```sh
uv tool install pebble-tool --python 3.13   # once
pebble sdk install latest                   # once
cd pebble && pebble build                   # produces build/pebble.pbw
```

Install over Wi-Fi via the Pebble phone app (its settings show the phone IP):

```sh
pebble install --phone <PHONE_IP> build/pebble.pbw
```

To target other watches, add their platforms to `targetPlatforms` in
`package.json` — the code uses `PBL_COLOR` guards and adapts automatically.

## Protocol (AppMessage keys)

Keep in sync with `companion/…/pebble/PebbleBridge.kt` (also mirrored as
`messageKeys` in `package.json`):

| Key | Direction | Type | Meaning |
|---|---|---|---|
| 0 `HELLO` | watch→phone | cstring | watch app opened (triggers door list push) |
| 1 `DOORS_COUNT` | phone→watch | uint32 | number of doors to expect |
| 2 `DOORS_END` | phone→watch | uint8 | list complete |
| 3/4/5 `DOOR_ID/NAME/TYPE` | phone→watch | uint32/cstring/uint8 | one message per door |
| 10/11/12 `UNLOCK_ID/TYPE/REQID` | watch→phone | uint32/uint8/uint32 | unlock request |
| 15/16/17 `RESULT_REQID/STATUS/TEXT` | phone→watch | uint32/uint8/cstring | outcome (0 OK, 1 error, 2 blocked→notification on phone) |

## Wire formats (phone ↔ companion)

The legacy Pebble phone app and the current Core Devices app
(`coredevices.coreapp`) use the same `com.getpebble.action.app.*` broadcast
actions but different extras (reverse-engineered from the Core APK):

- Legacy: `msg_data` bytes = 16-byte app UUID + binary tuples; `app_uuid`
  extra on START/STOP.
- Core: `msg_data` is a JSON string — array of
  `{"key","type","length","value"}` with type `"bytes"` (Base64),
  `"string"`, `"uint"`, `"int"` — and `uuid` is a UUID Serializable extra
  on SEND/START/STOP/RECEIVE, plus int `transaction_id`.

The companion auto-detects the relay app and speaks the matching dialect both
ways; the C code is unchanged (the phone app translates onto the watch
protocol).

Two Core-specific gotchas, both reverse-engineered from the Core APK:

- Core relays watch→companion traffic as an **implicit** broadcast, which
  Android 8+ never delivers to manifest-declared receivers. The companion
  therefore registers its receiver at runtime (`BridgeApp`); the manifest
  entry stays as a fallback for explicitly-routed apps.
- Core acknowledges companion→watch SENDs with `RECEIVE_ACK`/`RECEIVE_NACK`
  (transaction_id echo); the watch-side ACKs use plain `ACK`.

## UX notes

- Doors persist on the watch; each launch re-syncs from the phone.
- Select a door → "Requesting…" → result with vibration patterns.
- The trailing menu row ("Sync doors") re-requests the list.
