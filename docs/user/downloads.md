# Downloads And Offline Playback

[All user guides](../USAGE.md)

## Start and manage downloads

Downloads are available on Android, iPhone, iPad, macOS, and the Apple TV
preview when the account has **Content downloading** permission. Without it,
the destination and item actions are hidden.

JellyScope does not continuously refresh that permission in a signed-in
session. If permission is granted later, open Settings, choose
**Add account**, and authenticate the same server account again. This replaces
the stored session for that account and preserves its downloads. Do not use
**Remove account**, **Sign out**, or Android TV **Logout** merely to
refresh permission: those actions remove the affected downloaded media after
confirmation.

Before the first download, set an allocation:

1. Open **Downloads** and choose **Manage allocated size** on touch layouts or
   **Manage allocation** on Android TV, or **Download Storage** on Apple TV.
2. Choose how much storage JellyScope may use. A fresh installation shows
   **Quota not configured** and will not start a download until this is set.
3. On Android 13 or newer, JellyScope asks for notification permission when a
   download is confirmed while that permission is missing. Denying it hides
   download notifications; it does not cancel or prevent the download itself.

To download a movie or a specific episode:

1. Open its detail page and choose **Download**.
2. Choose **Original (exact source)** to save the server file unchanged, or
   **Fixed converted** to request the selected converted quality. Choose the
   audio track. Original also offers eligible subtitle choices. Fixed offers
   **Off** or a compatible embedded text subtitle; after confirmation, the
   selected subtitle is permanently burned into the converted picture and
   cannot be switched off during offline playback.
3. Select **Review download size**, check the estimate, and choose
   **Start download**.
4. Follow progress in **Downloads**. A paused item offers **Resume** and a
   failed item offers **Retry**. The bulk **Resume** action resumes paused
   items; it does not silently retry failures.

A completed item offers **Play offline**, or **Resume** after local progress, in
the touch Downloads screen; Android TV labels the action **Play**.
Its detail page offers **Play offline**. These actions use the saved copy. The
ordinary **Play** action on a detail page follows the server playback path even
when a completed download exists. Offline playback retains resume progress on
this device.
On Android, iOS, and macOS, downloaded playback can also make best-effort live
server reports when connected to the matching account. Missed reports are not
automatically replayed or synchronized. Apple TV offline progress stays local.

On iPhone, iPad, and Apple TV, offline playback requires VLCKit for that session.
This does not change any saved online player preference.
If VLCKit is unavailable, the offline session reports an error instead of
silently switching to AVPlayer.

If allocation is full, increase it or delete completed downloads, then resume
the paused item. Opening **Downloads** wakes eligible queued work on Android,
iOS, and macOS without overriding an explicit pause. Android can schedule work
with the operating system.

On iOS 26+, an explicit **Start download**, **Resume**, bulk **Resume**, or
**Retry** for either Original or Fixed requests continued-processing time. Work
can continue after backgrounding only when iOS grants that request. Expiration
or interruption checkpoints active work to Paused; return and choose **Resume**.
Earlier iOS versions, or iOS without a grant, require JellyScope to remain
active. This does not promise completion or survival after process death or a
force quit.

On macOS, transfers run while JellyScope is open. Launch recovers checkpointed
state, and opening **Downloads** wakes eligible queued work; an explicit pause
still requires **Resume**. On Apple TV, choose **Resume Queued Downloads**;
opening or refreshing the screen is passive, transfers run only while the app
is active, and tvOS may reclaim saved copies.

Switching accounts preserves downloads. On phone, tablet and desktop layouts,
**Remove account** and the global **Sign out** flow show the affected download
count and size before confirmation, then remove downloaded media for the
affected account or accounts. On Android TV, use **Switch Users** to
switch or **Logout** to sign out; logout confirms removal when downloaded media
is present. Android TV does not offer per-account removal. Apple TV offers
confirmed per-account removal through **Manage Accounts**, including the
affected download count and size.
OpenSubtitles files have a separate lifecycle and are removed only through
their own delete or clear actions.
