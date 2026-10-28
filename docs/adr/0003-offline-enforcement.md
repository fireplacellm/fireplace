# ADR 0003 - Offline enforcement

Status: accepted · 2026-10-21

## Decision

Fireplace declares **no network permissions at all** in its merged manifest. "Offline" is enforced by the Android OS, not by app logic. A script ([`scripts/verify_offline.sh`](../../scripts/verify_offline.sh)) checks every `AndroidManifest.xml` and, when given one, the built APK; [CI](../../.github/workflows/ci.yml) runs it on every push and **fails the build** if any of these appear:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.ACCESS_WIFI_STATE`
- `android.permission.CHANGE_NETWORK_STATE`
- `android.permission.CHANGE_WIFI_STATE`

We also set `android:usesCleartextTraffic="false"`, `android:allowBackup="false"` with data-extraction rules excluding everything, and request no Play Services.

## Why

- This is the one requirement with a crisp, binary, machine-checkable answer - and it is easy to get subtly wrong: an "offline build" the code never actually delivers because the offline variant was never wired or released. We do it the verifiable way: the network permission is stripped and checked in CI on every build.
- An app with no `INTERNET` permission *cannot* open a socket - the kernel blocks it. That is a far stronger claim than "we audited our code and found no network calls", and it's the claim a judge can verify in seconds with `aapt`.

## How a reviewer verifies it themselves

```
aapt dump permissions fireplace.apk        # expect: no INTERNET / network permissions
# or, from source:
bash scripts/verify_offline.sh
```

## Notes

- The only data that ever leaves the device is what the user explicitly shares via the system share sheet (e.g. exporting an answer). No implicit egress, no telemetry, no crash reporting, no analytics SDK.
- Model/pack downloads happen **outside the app** (browser or a setup script), so the app itself never needs network. This also matches "phone-only install, no adb" - packs import from the device's own Downloads.
- Caveat we will document honestly: a system component the app merely *hands off to* (TTS engine, a document provider during import) may itself use the network; we pick offline-capable paths and disclose this, as the OS-level guarantee only covers Fireplace's own process.
