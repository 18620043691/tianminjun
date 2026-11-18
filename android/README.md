# BitChat for Android (initial mesh client)

This is the native Android client for the repository's iOS-compatible offline
mesh. The initial release is one shared **Nearby mesh** group chat: public
messages are signed and broadcast over the existing BitChat BLE protocol, and
intermediate devices relay them. Separate named rooms would require a
cross-platform wire-format change and are intentionally not invented here.

The app implements the wire packet envelope used by `BitFoundation`, including
v1/v2 headers, signatures, compression, route metadata, padding, stream framing,
and bounded fragmentation for public text. Its Android debug build uses the
iOS DEBUG/testnet BLE service UUID; release uses the iOS mainnet UUID.

The initial app does not implement private chat, file/media transfer, Nostr,
or other iOS features. Android foreground BLE operation requires the system
connected-device foreground service and the corresponding user-granted
Bluetooth permissions. Background behavior remains subject to Android device
and vendor policies. iOS peers are also subject to CoreBluetooth's best-effort
background scheduling; a live conversation is most reliable while both apps
are foregrounded.

## Build and test

Use JDK 17, Android SDK 35, and Gradle 8.9 or later:

```text
gradle testDebugUnitTest
gradle assembleDebug
```

Protocol tests are JVM-only. BLE interoperability requires at least one
physical Android device and one physical iPhone; simulators do not validate
CoreBluetooth radio behavior. On Windows, use the same commands from this
directory in PowerShell or Android Studio.
