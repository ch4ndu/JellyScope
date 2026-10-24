# Optional Home-button helper

JellyScope Home Helper is a separate optional Android app for devices whose
firmware keeps sending Home to the stock launcher. It attempts to open JellyScope
Launcher when Android delivers a physical Home key to its accessibility service.
It does not change the default Home selection. Firmware support must be tested.

## Install and enable

1. Install JellyScope Launcher first, then the helper APK. The helper appears as
   **JellyScope Home Helper** in the device app list.
2. Open the helper and check that the launcher is installed and enabled.
3. Choose **Open accessibility settings**, select JellyScope Home Helper and enable
   it using Android's normal confirmation screen. Leave other services unchanged.
   On Android 13 or later, sideload restrictions may require **Allow restricted
   settings** in the helper's App info before enabling.
4. Return to the helper. Its enabled status refreshes on resume.
5. **Open JellyScope Launcher** checks the target launch only. To verify Home
   interception, press Home on the physical remote from another app and from the
   stock launcher. Confirm JellyScope opens on Home/Apps. Check held/repeated Home
   and ordinary Back as well. ADB-injected Home is not physical-key proof.

The helper requests key filtering but no screen-content retrieval, gestures,
network access or storage permission. Only Home is handled. Android grants an
accessibility service broad trust, so enable it only if you want this behavior.
The service attempts one synchronous launch per Home press and consumes its
matching key events when Android accepts the launch request without a caught
error. Missing/disabled launcher or a caught launch error leaves the original key
unconsumed. Request acceptance does not prove a foreground transition: firmware
that silently blocks it can still leave that Home sequence consumed.

## Limits and removal

Accessibility-enabled status and a successful manual launch do not prove the
firmware delivers Home to the service. Android must permit the service to launch
an activity from the background; device policies can prevent that. Consuming Home
may not reproduce the stock launcher's system-dialog behavior. There is no boot
launch, reconnect launch, polling, default-app change or stock-package disabling.
Updates may require rechecking accessibility enablement.

Disable this helper in Accessibility to restore normal key routing, or uninstall
only **JellyScope Home Helper**. The launcher and its session remain separate.
Reboot persistence and physical-remote behavior are unqualified until observed;
see [qualification](qualification.md).

## Build locally

```sh
./gradlew :android-launcher-home-helper:assembleRelease
adb install -r android-launcher-home-helper/build/outputs/apk/release/android-launcher-home-helper-release.apk
```

The initial minified Release uses the local debug signing key. It is not a
published release and is not collected by the main release-artifact script or
included automatically in the aggregate verification script. Package:
`com.udnahc.jellyscope.launcher.homehelper`. The SDK-only module has no player or
launcher-library dependency; source uses MPL-2.0.
