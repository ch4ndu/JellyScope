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
Some firmware may ignore or block the request. Test physical Home from another
app before relying on the helper; enabling the service alone does not prove it works.

## Limits and removal

Accessibility-enabled status and a successful manual launch do not prove the
firmware delivers Home to the service. Android must permit the service to launch
an activity from the background; device policies can prevent that. Consuming Home
may not reproduce the stock launcher's system-dialog behavior. There is no boot
launch, reconnect launch, polling, default-app change or stock-package disabling.
Updates may require rechecking accessibility enablement.

Disable this helper in Accessibility to restore normal key routing, or uninstall
only **JellyScope Home Helper**. The launcher and its session remain separate.
Physical Home has been observed on one TiVo device; broader firmware and reboot
support remain unverified. See [verification limits](../engineering/launcher-verification.md).

For local APK commands, see [build the Home helper](../engineering/launcher.md#build-the-home-helper).
