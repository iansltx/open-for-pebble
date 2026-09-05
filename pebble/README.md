# Alta Doors — Pebble watch app

A door list for Avigilon Alta-controlled sites, driven by the companion app
(`../companion`). Pure C, no JS — the phone companion does all networking and
the unlock triggering.

## Files

- `appinfo.json` — project manifest (UUID `2f9a7c41-5e3b-4d88-a6c2-7b1e0d5f4a33`, platform `emery` = Pebble Time 2)
- `src/main.c` — the app

## Building on CloudPebble

1. New project → name `Alta Doors`, **platform Emery**, type **Pebble app**.
2. Settings → set the project UUID to the one above (must match the
   companion; the companion also lets you edit its UUID on its main screen).
3. Add file `src/main.c`, paste `src/main.c`.
4. *Run → Build and Install*.

To target other watches (Pebble Time/basalt, Pebble 2/diorite), add them in
Settings — the code uses `PBL_COLOR` guards and adapts automatically.

## Building with the local Pebble SDK

```sh
cd pebble
pebble build            # produces build/…pbw
pebble install          # install over the Pebble app connection
```

## Protocol (AppMessage keys)

Keep in sync with `companion/…/pebble/PebbleBridge.kt`:

| Key | Direction | Type | Meaning |
|---|---|---|---|
| 0 `HELLO` | watch→phone | cstring | watch app opened (triggers door list push) |
| 1 `DOORS_COUNT` | phone→watch | uint32 | number of doors to expect |
| 2 `DOORS_END` | phone→watch | uint8 | list complete |
| 3/4/5 `DOOR_ID/NAME/TYPE` | phone→watch | uint32/cstring/uint8 | one message per door |
| 10/11/12 `UNLOCK_ID/TYPE/REQID` | watch→phone | uint32/uint8/uint32 | unlock request |
| 15/16/17 `RESULT_REQID/STATUS/TEXT` | phone→watch | uint32/uint8/cstring | outcome (0 OK, 1 error, 2 blocked→notification on phone) |

## UX notes

- Doors persist on the watch; each launch re-syncs from the phone.
- Select a door → "Requesting…" → result with vibration patterns.
- The trailing menu row ("Sync doors") re-requests the list.
