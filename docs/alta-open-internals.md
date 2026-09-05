# Avigilon Alta Open — reverse-engineering notes

Everything below was extracted from `Avigilon Alta Open.apk`
(`com.openpath.mobile`, the rebranded Openpath mobile app, React Native +
Hermes, with the native "Titanium" access SDK in `com.openpath.mobileaccesscore`).

## Cloud infrastructure

| Service | Base URL | Role |
|---|---|---|
| Helium | `https://helium.{env}.openpath.com` (prod: `https://helium.prod.openpath.com`) | REST API: auth, credentials, orgs, users, sync, tokens |
| Ozone | `https://ozone.{env}.openpath.com` | Allegion wireless-lock cloud unlock, lockdown plans, location measurement |
| Pulsar | `https://pulsar.{env}.openpath.com` | Entry permission tokens, phone configs |
| AWS IoT (MQTT) | credentials per user (`awsCredentials` + `userCert` from syncMobile) | Door commands to the ACUs (`opal/{orgId}/helium/alpha/{credentialId}/acu/{acuId}/notify`), shadow state |
| Platinum / control | `platinum.{env}.openpath.com`, `control.openpath.com`, `access.{region}.alta.avigilon.com` | Web setup links (`/setupMobile`), admin web |

`Environment` JSON: `{heliumEndpoint, opalEnv, opalRegion}`. Default:
`https://helium.prod.openpath.com`, `opalEnv: "prod"`, `opalRegion: "alpha"`.
User "opal" identifiers look like `opal:prod:helium:alpha:302:user:15010`.

## REST endpoints (helium)

Observed in the React Native bundle (`decompiled.js`) and the native SDK:

```
POST /auth/determineLoginCandidateNamespaces    {"email"}
POST /auth/login       {"namespaceId", "email", "password", "forMobileLogin": true [, "mfa": {"totpCode"}]}
POST /auth/resetPassword
POST /auth/sso/authorize
POST /auth/setupMobileSaml2

GET  /orgs/{orgId}/users/{userId}/credentials                     (list)
POST /orgs/{orgId}/users/{userId}/credentials/{credId}/generateSetupMobileToken
GET  /orgs/{orgId}/users/{userId}/credentials/{credId}/awsCredentials
POST /orgs/{orgId}/users/{userId}/credentials/{credId}/refreshMobile
POST /orgs/{orgId}/users/{userId}/credentials/{credId}/syncMobile  (native SDK)
     ?options=withFullCaChain,withMultiGenCaChain,withNfcData,withUserIotCert
     body: {httpUserAgent, version, build, os:"Android", mobileId, deviceToken, deviceMobileIds}
     → data.userCert (AWS IoT cert for MQTT)
POST /orgs/{orgId}/users/{userId}/credentials/{credId}/unprovisionMobile
POST /orgs/{orgId}/users/{userId}/credentials/cloudKey/{cloudKeyId}/generateUnlockToken

GET  /orgs/{orgId}/users/{userId}/acus/{acuId}?options=withShadows  (describe ACU:
     data.acu_config {acuId, org{...}, entries{id→{name}}, readers{...}, …},
     data.shadow {state.reported.entries…})
POST /orgs/{orgId}/users/{userId}/opvideoDevices/{id}/users/{userId}/generateUserLiveToken
POST /orgs/{orgId}/users/{userId}/reports/activity
GET  /orgs/{orgId}/users/{userId}/badge
GET  /orgs/{orgId}/users/{userId}/mobileSendFeedbackReasons
GET  /cloudServerRegions
POST /identities/{identityId}/termsAgreements/{checkSigned,sign,unsign}
GET  /applications/{appId}/termsVersions/latest
```

Request headers set by the app's fetch wrapper:
`Accept: application/json`, `Content-Type: application/json`,
`Authorization: <apiToken>` (raw token from login, no Bearer prefix observed
in the native calls), `X-App-Version: Openpath Titanium/<ver>.<build> <os> <osver>`.

## Unlock paths (native SDK, `OpenpathMobileAccessCore.unlock`)

`unlock("entry", entryId, requestId, …)` → `OpenpathForegroundService.batchUnlockEntry`:

- Wi-Fi direct: `https://{acu-ip}/entries/{id}/unlock` on the local network
  (client cert from `acu{n}.org{m}.{env}.openpath.local` mapping).
- MQTT relay to the ACU topic `opal/{orgId}/helium/alpha/{credId}/acu/{acuId}/notify`
  (AWS IoT, SigV4 with `awsCredentials`).
- BLE to the reader (wave-to-unlock; `ReadersWithValidAvgBleRssi` etc.).
- Allegion wireless locks: `POST https://ozone.{env}.openpath.com/entry/unlock`
  with canonical-JSON body + SHA256withECDSA signature (keystore key
  `opKeyAlias-{id}`, registered during provisioning):

```json
{"action":"unlock","entryId":…,"mobileId":…,"requestId":"…","timestamp":…,
 "proximityProof":"…","signature":"<base64(SHA256withECDSA(canonJSON))>"}
Authorization: Bearer base64(token)
```

This is why unlocking can't be reimplemented in a watch app or Pebble JS:
the device identity (keystore ECDSA keys, IoT cert, provisioning) is created
during enrollment and lives in the first-party app's sandbox.

## The integration seam: app shortcuts

`com.openpath.mobile.MainActivity` is `exported="true"`, `launchMode="singleTask"`.
The RN module `ReactAppShortcutsModule` (`com.openpath.mobile.reactappshortcuts`)
reads intent extras on `onNewIntent` (and on cold start via `popInitialAction`):

| Extra | Type | Notes |
|---|---|---|
| `SHORTCUT_ID` | `long` | Dedup — must differ per request |
| `SHORTCUT_TYPE` | `String` | Not validated by the JS handler |
| `SHORTCUT_USER_INFO` | `String` (JSON) | Handler reads `userInfo.url` |

JS (`quickActionShortcut` event, mounted by the `ShortcutItems` component on
the home screen):

```js
const {itemType, itemId} = getItemForKey(userInfo.url);   // "entry-123" → {entry, 123}
logFirebaseEvent(SHORTCUT_ITEM_TAP);
showItemDetailScreen(itemType, itemId);
requestBatchUnlock(itemType, itemId, 'home_screen_shortcut');
```

`processRequestBatchUnlock` → `overrideUnlockAcuItemApiRequest`: if
`itemStates[key].isInRange` (BLE reader nearby) it calls
`processRequestBatchAcuEntryApiWithoutConfirmation` → native
`batchUnlockItem(itemType, itemId, requestId, …)` → unlock via all
transports. If out of range and the user has `selectUserHasRemoteUnlock`, it
shows the remote-unlock confirmation modal ("…security risk…"). If no
remote-unlock permission: "You cannot unlock X because you are out of range".

Item keys are `{itemType}-{itemId}`, `itemType ∈ {entry, reader}`; the JS
saga parses the id with `parseInt(_, 10)`; the door must exist in the app's
synced redux `selectItems` (its own doors — it does).

The iOS app has a parallel "apple_watch" request source (`RequestSource`
enum: `home_screen_shortcut`, `apple_watch`, `notification_tap`, …), i.e.
watch-initiated unlocks are an intended usage of this saga.

## App shortcuts the app itself registers

`setShortcutItems` (JS) creates dynamic launcher shortcuts:
`{type: 'Unlock', title: <door name>, subtitle: <org>, icon:
'shortcut_lock_icon', userInfo: {url: 'entry-<id>'}}` — one per favorite door,
which is how we knew the userInfo shape.

## Data model (helium)

- `User {id, opal, pictureUrl, identity{id, fullName, email}, org{id, name,
  adminSupportContact…}, status, startDate, endDate}`
- `Credential {id, opal, credentialType{modelName:'mobile'}, mobile{id, name,
  provisionedAt}, …}`
- ACU config (`data.acu_config`): `{acuId, org{...}, entries: {<id>: {name,
  …}}, readers: {<id>: {…}}}`; shadow (`data.shadow`):
  `{state: {reported: {entries: {…}}}}`
- `apiTokens`: map of userOpal → apiToken (login response; read by JS via
  `getApiTokenForUserOpal`)

## Misc. facts useful for testing

- `MainActivity` handles deep links `https://…/setupMobile` and
  `openpath://setupmobile` (enrollment only — no unlock deep link exists).
- `OpenpathForegroundService` is `exported="false"`; its notifications use
  `op-notification` extras internally.
- The app's redux state is persisted to the `REDUX_PERSIST_FILE`
  SharedPreferences via the RN module's `getStorageItem/setStorageItem` —
  inside the app sandbox, not readable by other apps.
- The Hermes bundle string table (`strings` on
  `assets/index.android.bundle`) is a decent index into features, e.g.
  `ShowRemoteUnlockWarning`, `TapAndHoldUnlock`, `EncryptionEnabled`,
  `credential_gatt_profile.json` BLE UUIDs
  (`1e345cbb-1103-43f4-8d53-d19cae536400/6401`).
