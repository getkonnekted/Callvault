# Changelog

All notable changes to CallVault are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and this project uses semantic-ish versioning.

## [1.5.8] - 2026-08-18

### Fixed

- **Recording could stop for good, silently, and nothing said so.** A phone was found with the
  recorder dead: no daemon, no recordings, and a status card still showing nothing wrong. It had
  stayed that way through the app being force-quit *and* a full restart. Every minute CallVault tried
  to bring the recorder back, waited 45 seconds, gave up, and tried the identical thing again — a loop
  it could never escape, because an endpoint to connect to genuinely existed and connecting to it
  simply hung. Three changes, each of which alone would have shortened the outage:
  - After two failed attempts CallVault now stops trusting that connection: it tears it down, rebuilds
    it, and switches Wireless debugging on so there is a second route in. Doing exactly this by hand
    was what revived the affected phone.
  - The check for "is there a way in at all" was wrong in a way that could strand a phone with no
    route back. Having USB debugging switched on was treated as sufficient, but it only keeps
    Android's debug service running — it offers nothing for CallVault to connect *to*.
  - The failure is no longer invisible. Home now says **"Recording is down"** when recovery keeps
    failing, instead of leaving you to discover it. It stays quiet when the recorder is merely idle,
    which is normal and healthy.

- **A phone call could be mistaken for an app call.** Carrier calls are no longer treated as VoIP,
  which could suppress the recording of a real call.

## [1.5.7] - 2026-08-04

### Fixed

- **Retention now deletes what it says it deletes.** "Recordings older than the selected period are
  permanently deleted" was true only of recordings CallVault still had a record of. Measured on a
  phone set to keep one week: the list showed a convincing 64 recordings going back exactly seven
  days, while **131 files had outlived the period** — 8 on the device and 123 in Drive, the oldest by
  48 days. Four separate faults, each of which alone was enough to strand a recording for ever:
  - A delete that failed — Drive offline, a permission lost — still made CallVault forget the file.
    It then existed nowhere in the app: absent from the list, and past the reach of every future
    check. The entry is now kept when the file survives, so the next day's check tries again.
  - The daily check only ever looked at CallVault's own index, so a file missing from it was exempt
    from the retention period no matter how old it got. It now reads the recording folders too.
    Only files CallVault itself named are eligible, and a file whose age cannot be established is
    never deleted — your own audio kept in the same folder is not touched.
  - Changing **Run at** did not move the next check until the current 24-hour period happened to
    elapse. It now takes effect immediately.
  - Google Drive can renumber the account slot inside the folder link it gives out, which silently
    invalidates every saved link — uploads, deletions and folder listings all start failing, and
    re-picking the folder does not repair the ones already saved. They are now repaired
    automatically, so recordings do not become undeletable.
- **The USB-mode warning no longer disappears exactly when it matters.** Locking the screen mid-call
  can stop a recording when USB is set to a data mode, and CallVault warned about it — but only while
  the recorder was ready. Since that setting is one of the things that stops the recorder being
  ready, the warning vanished precisely when it applied. It is now shown either way, and when the
  setting cannot be read at all CallVault says so instead of staying silent.
  - One UI 8's **"Debugging only"** is recognised as a safe mode rather than an unknown one.
  - On phones whose `dumpsys usb` does not report the setting while a safe mode is selected — a
    OnePlus 12 among them — CallVault now reads it from a system property instead, so a correctly
    configured phone is recognised as such rather than staying permanently unknown.
  - Changing the USB mode no longer leaves the setting spinning on "Applying…" for tens of seconds.
    Choosing "Charging only" cuts USB data, and the app was trying to confirm the change over the
    very connection the change had just closed.
- **Hardening:** the background helper now invokes `sh` and `pkill` by absolute path, so its
  behaviour cannot depend on the shell's search path.

### Added

- **Bug reports now include the background helper's own log, and the system's.** Everything the
  recorder helper says goes to the Android log and nowhere else — it runs as a separate process that
  cannot write CallVault's log file — so a debug report showed only the app's half of the story.
  Issue [#18](https://github.com/madkongo/CallVault/issues/18) spent a week there: the log the
  reporter sent was clean end to end, because the failure lived in the half nobody could see.
  - Turning debug logging on now also enlarges Android's log buffer, and restores it when you turn
    logging off. The default holds barely a minute on a busy phone — less time than it takes to
    reproduce a problem and reach Settings to share it.
  - **Share debug logs** attaches a second file with those lines, filtered to CallVault and to the
    Android audio, security and process services, with phone numbers redacted. Other applications'
    lines are not included, and the file says at the top what was collected and what was left out.

## [1.5.6] - 2026-07-31

### Added

- **Choose what CallVault records, for each kind of call.** Phone calls and app calls now each offer
  three settings: record automatically, ask first, or ignore completely.
  - **Phone calls can be switched off entirely** (Settings ▸ Recording ▸ Phone calls). This is what
    "record app calls only" needed: turning the two auto-record toggles off never stopped CallVault
    offering a **Record** button on every single call, which is the nagging the mode is meant to
    avoid.
  - **App calls can ask first** (Settings ▸ General ▸ Experimental ▸ VoIP calls ▸ *Start
    automatically*). A notification names the calling app and offers **Record**; it disappears when
    recording starts or the call ends.
  - Both default to what they did before, so nothing changes until you choose otherwise.
- **Share a recording** from the list, and **select several at once** by holding one down. A
  selection can be shared or deleted together.
- **Deleting several at once asks which copies to remove** when any of them is saved both on the
  device and in Drive, and says what it will keep — "Device only (1 of 2)", "Feroza will be kept —
  only in Drive". A recording you asked to delete and did not get is not something you should have to
  discover for yourself.
- **PayPal alongside Ko-fi** for supporting development. Ko-fi's card payments are unavailable or
  awkward in some countries.
- **The debug log can be read and deleted from Settings ▸ Debug**, and shows its size. Previously it
  was invisible, and clearing it meant switching logging off and on again.

### Fixed

- **An update could install in the middle of a call and cut the recording in two.** Installing over
  the running app kills it, and the recording with it — seen on a real call, where the second half
  came back mislabelled. Updates now wait, including during app calls, which never register as
  telephony calls at all.
- **Nothing checked that your bit rate was one the phone's encoder accepts.** Handed a rate outside
  its range, an encoder can quietly emit audio that decodes to silence — a correctly-sized recording
  that plays as nothing. The rate is now brought inside the supported range, and what the encoder
  accepts is written to the log.

### Changed

- The **Record phone calls** switch groups the incoming and outgoing settings under it, and the
  setup wizard now offers it too — the wizard cannot be re-run, and someone installing CallVault for
  app calls alone wants it on day one.
- The release note is followed once per release by a short note about supporting development.

## [1.5.5] - 2026-07-30

### Fixed

- **VoIP calls on Samsung lost one side of the conversation.** On One UI only one app gets the
  microphone, and when the calling app restarts its own capture ours is silenced — it keeps
  delivering audio, but silent audio, so nothing noticed. CallVault now detects that and takes the
  microphone back. Measured on a Galaxy S24 FE: the longest silent gap fell from 8 seconds to 1.5.
- **Resilient recording sounded crackly on some phones.** It held back far too little audio while the
  system was still writing it, so partly-written sound was being read. Inaudible on some devices and
  constant on others.
- **Recording could stall and stay stalled.** A second connection path could hang with no timeout,
  freezing every other connection attempt behind it. Both paths are now bounded.
- The setup wizard offered no **24 kbps** option — the recommended setting, and the default — so it
  displayed 8 kbps instead and writing that back quietly downgraded recordings.

### Added

- **Settings opens as a panel** over the app instead of replacing it, so closing it is instant.
- **Settings is grouped**: a General section, and sub-sections throughout, each collapsed on entry so
  the screen is a short list rather than everything at once.
- **Recordings show when the call happened and how long it lasted** — "Yesterday 14:30 · 12:41" —
  replacing a timestamp too cramped to read.
- The wizard now asks about **resilient recording**, **VoIP recording** and **update checking**, which
  had shipped without ever being offered during setup.

## [1.5.4] - 2026-07-30

### Fixed

- **Recording could stop silently until the app was restarted.** A connect to the recorder that was
  interrupted mid-handshake could park forever, because the ADB library's connect has no timeout —
  and it parked holding the locks every other ADB operation needs. A keep-alive latch then meant the
  watchdog never tried again, so recording stayed off with nothing on screen to say so. The handshake
  and the relaunch are both bounded now, and the app recovers on its own.

### Added

- **Choose when recordings upload to Drive** — immediately, daily or weekly, under
  Settings ▸ Storage. The schedule already worked, but its picker only existed in the setup wizard,
  which cannot be re-run ([#20](https://github.com/madkongo/CallVault/issues/20)).
- **Retention moved into Storage** as a sub-section, next to the upload schedule.

## [1.5.3] — 2026-07-29

### Added
- **The status card now tells you whether recording actually works.** Until now it reported that the
  app was ready — that the pieces were connected — which is not the same as knowing a call came out
  the other end. It now reports what your real calls proved: when recording was last verified, and,
  when something went wrong, what went wrong. An empty recording, a recorder that stopped mid-call,
  an app call where only your side came through: each says so plainly instead of leaving you to find
  out weeks later.
- **Calls CallVault never saw are caught too.** A sweep of the phone's own call log finds answered
  calls that produced no recording, so a setup that quietly stopped working is surfaced by the next
  call rather than by the one you needed. Where the cause is something you can fix — no recording
  folder, Developer options switched off, a permission lost to an update — it names that cause
  instead of reporting an unexplained gap.
- **Brazilian Portuguese.** CallVault is now available in Português (Brasil), selectable under
  Settings ▸ Visual settings ▸ Language.

### Fixed
- **Every other language was behind, and now none of them are.** German, Spanish, French, Hungarian,
  Italian, Polish, Russian, Vietnamese and Chinese were each missing dozens of strings, which
  rendered in English inside an otherwise translated screen — including the whole USB-debugging
  section and the VoIP messages. All ten languages are now complete, and the build refuses to
  produce a release if a language falls behind again.
- **A recording deletion that never happened is no longer reported as success.** When the storage
  provider refused a delete, CallVault carried on as though the file were gone.
- **A misleading log line** claimed recordings always went through scrcpy, which sent bug-report
  troubleshooting down the wrong path. It now names the capture route actually taken.

## [1.5.2] — 2026-07-28

### Fixed
- **Google Drive kept announcing calls it had already saved, sometimes an hour or more after they
  ended.** Every failed copy started over with a brand-new file in your cloud folder rather than
  recognising the one already there, and it never stopped trying: one recording had been re-uploading
  for a day, leaving a second, half-finished copy of the call beside the good one. A copy now sees a
  recording that is already up there and does nothing at all.
- **A copy cut short can no longer be mistaken for a finished one.** Android kills long uploads that
  run in the background; the recording is now written under a temporary name and only takes its real
  name once every byte has arrived. A half-finished copy left by an older version is replaced.
- **A recording that cannot be copied now tells you.** After ten attempts CallVault stops and shows a
  notification, keeping the recording on your device, instead of retrying silently for days. An empty
  recording is never uploaded as though it were a saved call.
- **The scheduled cloud sweep now matches the cadence you picked.** Leaving the setup wizard without
  finishing it could strand a daily or weekly sweep, which then uploaded in batches alongside the
  per-call copy.

## [1.5.1] — 2026-07-27

### Fixed
- **Switching USB debugging on now switches Wireless debugging off straight away**, instead of waiting
  for the next time CallVault happened to re-check. The reverse already worked; this makes both
  directions immediate.

### Added
- **If you switch Wireless debugging on while it isn't needed, CallVault switches it back off** — and
  now tells you why, in a dismissible notification, rather than silently undoing what you just did.
  CallVault can tell its own changes from yours, so it never fights its own start-up, and it only does
  this when Wireless debugging is genuinely redundant: USB debugging is covering the connection *and*
  the helper is already running.

## [1.5.0] — 2026-07-27

The headline: **you can now switch Wireless debugging off** — by turning on USB debugging instead.

### Added
- **USB debugging is now offered in the app**, under Settings → Experimental and in the setup
  checklist, marked *Recommended*. Turning it on is what lets CallVault switch **Wireless debugging
  off** and keep it off. No cable is needed, and unlike Wireless debugging it opens **no network port**,
  so it is the better of the two to leave enabled. It is genuinely optional — recording works either way.

### Fixed
- **CallVault could be left unable to record after you turned USB debugging off.** With USB debugging
  on, CallVault switches Wireless debugging off; if you then turned USB debugging back off, *both* were
  off, Android stopped its debugging service, and CallVault's helper went with it — silently, until a
  call was missed. It cost a real call: the helper needed 18 seconds to come back and the call lasted
  15. CallVault now notices immediately and switches Wireless debugging back on.

### Note
- Running with **neither** switch enabled is not possible without root: Android's debugging service
  does not exist without one of them, and a helper started through it is stopped along with it. This is
  the same limit Shizuku hits — its maintainer describes it as "work as intended… nothing we can do".

## [1.4.9] — 2026-07-26

### Fixed
- **The start-up loop from 1.4.8 could still happen** if you had "Record without Wi-Fi" switched on and
  USB debugging off. 1.4.8 assumed that option kept Android's debugging service alive so Wireless
  debugging could be switched off safely. It does not — the option saves a setting rather than holding
  a live connection, so switching Wireless debugging off still restarted the service and stopped
  CallVault's helper, and the loop returned. CallVault now keeps Wireless debugging on unless USB
  debugging is enabled, which is the only thing measured to hold the service open.

### Note
- This means most people will see Wireless debugging stay on while CallVault is ready, with the
  notification explaining why. Switching it off again for "Record without Wi-Fi" users needs a
  different approach and is being worked on.

## [1.4.8] — 2026-07-26

The headline: **CallVault now becomes ready reliably on phones where Wireless debugging is the only
way in** — found on a Samsung, and it turned out to affect the app everywhere, just invisibly.

### Fixed
- **CallVault could sit flipping Wireless debugging on and off without ever becoming ready.** Android's
  debugging service shuts down when its last connection is removed, and CallVault's background helper
  runs inside it — so switching Wireless debugging off right after starting the helper killed the
  helper, which restarted it, which switched it off again. On a Galaxy S24 FE this looped six times
  over two minutes and app-call recording could not arm at all. CallVault now leaves Wireless debugging
  on when it is the only way in, and says so in its notification, pointing at the two settings
  ("Record without Wi-Fi", or USB debugging) that let it be switched off again.
- **An app call could produce a failure notification even though it recorded perfectly.** On Samsung a
  WhatsApp call also raises the phone's call state, so CallVault started a *second*, carrier-style
  recording for the same call. That one captured nothing, was saved under an unrelated contact's name
  taken from the call log, and its empty file raised an error — while the real recording was fine. The
  carrier path now stands down when an app call is already being recorded.
- **App calls could be labelled with the wrong app entirely.** On Samsung a WhatsApp call reports the
  system as the owner of the audio, so recordings were named after *Device maintenance* and shown with
  its battery icon. CallVault now ignores the system and uses the calling app's own audio.

### Note
- **Resilient recording is not confirmed working on One UI.** Its audio handoff is rejected on that
  device. It fails safely — the call is still recorded through the normal path — but the extra
  protection is not active there. Under investigation.
- The README now carries a **tested-devices table** and a short **roadmap**.

## [1.4.7] — 2026-07-26

The headline: **CallVault can now record app calls (VoIP)** — WhatsApp, Signal, Telegram — both
sides of the conversation. Opt in under Settings → Experimental → VoIP calls.

### Added
- **Recording app calls — VoIP (opt-in, experimental).** Until now CallVault only recorded carrier phone
  calls; a call placed inside a messaging app could not be captured at all without root. It now can be,
  and it records **both sides** — the other person as well as you — as a normal recording that appears
  in the list and plays like any other.
  - **Off by default.** Enable under **Settings → Experimental → VoIP calls**. Turning it on asks you
    to confirm first: recording app calls is more tightly regulated than recording phone calls, and in
    many places every participant has to agree beforehand.
  - Verified working with **WhatsApp, Signal and Telegram**. It is genuinely experimental — an app can
    block recording, and that cannot be known until a call is under way. If only your side was
    captured, CallVault tells you so rather than leaving you to discover it later.
  - Recordings are named `…_voip-<App>[_<contact>]` and show the **calling app's icon** in the list.
  - Carrier Wi-Fi calling (VoWiFi/VoLTE) is **not** covered by this — it works differently and is out
    of reach by this route.
- **The "What's new" note now covers the last three releases**, each labelled with its version, instead
  of introducing one feature and leaving the rest unmentioned. It scrolls, and appears once per update.

### Fixed
- **VoIP recordings could be attributed to the wrong app.** A Telegram call was labelled as WhatsApp,
  and later as Google, because the app was identified by scanning notifications. The app is now taken
  from the audio stream being recorded, which cannot belong to anyone else.
- **Contact names were missing for some apps.** Telegram publishes the contact in a different field
  from WhatsApp, so its calls came out unnamed.
- **Calls with no contact name showed no app icon** — a Signal call saved as `…_voip_Signal` was read
  as a call with someone *named* "Signal", losing the app badge.

### Note
- Contact names for app calls come from the calling app's own call notification, which is the only
  place Android exposes them. If notifications are turned off for that app, its recordings are saved
  correctly but without a name — Settings now says so.
- The VoIP feature's text is currently English-only.

## [1.4.6] — 2026-07-26

The headline: **recording is now resistant to the background helper being stopped mid-call** — opt in under Settings → Reliability.

### Added
- **Resilient recording (opt-in).** Until now, the background helper held the microphone and did the
  encoding for the whole call — so if Android stopped it part-way through, the recording stopped with
  it and you were left with a half-length file. With this on, the helper only *starts* the capture and
  then hands it to CallVault itself, which keeps it and does the encoding. The helper can then be
  killed at any point mid-call and the recording simply carries on to the end.
  - **Off by default**, and turning it off restores exactly the previous behaviour. Enable it under
    **Settings → Reliability**, or from the one-time note shown after updating.
  - It doesn't change how a recording *starts*, so it complements the "Charging only" USB fix from
    1.4.5 rather than replacing it.

### Fixed
- **A call could occasionally not record at all.** When starting a recording, CallVault read a USB
  setting over its ADB connection with no time limit. Normally that is instant, but if the connection
  had gone half-dead the read never returned, and the recording never started — leaving an empty file.
  It is now capped at 1.5 seconds and falls back to the last known value. This affected every
  recording, not just the new opt-in.
- **The "Voice performance" audio source now uses the fast direct path.** It was matched against a
  source name that doesn't exist, so it silently fell back to the slower scrcpy path.

### Note
- CallVault now ships a small native library and therefore requires a **64-bit ARM device**
  (`arm64-v8a`) — effectively every phone running Android 11 or newer.

## [1.4.5] — 2026-07-24

The headline: **recording no longer stops if you lock the screen during a call** — on phones where it did.

### Added
- **Keep recording when the screen locks.** On many phones (OnePlus, Xiaomi, Samsung…), locking the
  screen during a call restarts the USB connection, which was killing the recorder mid-call. CallVault
  can now set your phone's **Default USB Configuration to "Charging only"** from inside the app, which
  prevents it — no digging through system menus.
  - A new **Settings → Reliability** section lets you pick the USB mode (Charging only is recommended)
    and holds the off-Wi-Fi recording option too.
  - A **setup step** offers the one-tap fix during onboarding.
  - If USB is on a data mode, the **Home screen** and the **recorder notification** show a gentle
    "locking the screen may stop recording — tap to fix" prompt.
  - Note: with "Charging only", plugging into a PC defaults to charging — pick "File transfer" manually
    when you actually want to move files.

## [1.4.4] — 2026-07-24

### Fixed
- **Call audio quality restored at the same bitrate — the far party is clear again.** Recording captured
  the call in stereo (your side on one channel, the other person's on the other) and encoded it as
  stereo, which split the bitrate and starved each side — so the **other party** sounded noticeably
  worse at the default 24 kbps. Calls are mono content, so CallVault now downmixes to a single channel
  and gives the whole bitrate to the voice. Same setting, much better quality.
- **No false "recording paused" warning while recording actually works.** The post-update permission
  banner now only appears when a call genuinely couldn't be recorded (the recorder isn't running), not
  while it's warm and recording normally — and CallVault keeps quietly restoring the permission in the
  background.

## [1.4.3] — 2026-07-24

### Fixed
- **No more false "recording paused" warning after an update.** When an update dropped a permission
  but recording kept working (the recorder was still running), CallVault showed an alarming
  "paused after update" banner anyway. It now **silently restores the permission** over the
  connection that's already open — no action, no reinstall — and only shows a (reworded, honest)
  prompt when it genuinely can't, telling you it may still be working and how to keep it that way.

## [1.4.2] — 2026-07-24

### Added
- **Support development.** An optional "♥ Support" button next to the app title on Home, and a
  matching row in Settings → About, open the maintainer's Ko-fi page in your browser. Entirely
  optional — CallVault stays fully free and open source.

### Fixed
- **The "What's new: off-Wi-Fi recording" note no longer pops up after every update.** It's a
  one-time introduction now — shown once, then never again (a small "updated to …" banner still
  confirms an update landed).

## [1.4.1] — 2026-07-24

A focused fix for a problem some people hit after updating to 1.4.0.

### Fixed
- **Recording no longer breaks after updating without a reinstall.** When CallVault was updated in place
  (e.g. via Obtainium or a sideloaded APK), Android could quietly drop a permission the app needs to run
  the recorder — leaving recording dead until a full clean reinstall. CallVault now heals this itself:
  right after an update it reconnects over any still-open channel and restores the permission
  automatically. If it can't (nothing to reconnect through), the Home screen shows a clear
  **"Recording paused after update"** banner — tap it and turn Wireless debugging on once, and recording
  restores itself. **No reinstall needed.**

## [1.4.0] — 2026-07-23

The headline: **record calls even without Wi-Fi**, plus a much faster, more reliable recorder.

### Added
- **Offline recording (opt-in) — record with no Wi-Fi.** A new option (Settings → Debug → "Offline
  recording") lets CallVault capture calls even when you're not on a Wi-Fi network — for the important
  call you get on the road. It's **off by default** and shows a short security note when you turn it on,
  because it opens a local debugging port on your own device. You can also enable it straight from the
  "What's new" note after updating.
- **The recorder stays warm and comes back fast.** CallVault now keeps its privileged recorder ready and
  relaunches it within a few seconds when the system reclaims it — so a call after your phone has been
  idle is captured almost immediately instead of after a long "starting up" wait.

### Changed
- **New audio-capture engine.** Calls are recorded through a direct on-device audio path instead of the
  previous screen-mirroring helper. Capture begins from the first moment (no clipped beginnings), the
  daemon boots faster, and the app has fewer moving parts. (Falls back to the old path automatically if a
  device can't use the new one.)
- **One clear "ready to record" notification** instead of the occasional duplicates.

### Fixed
- **No more Wireless-Debugging notification flapping** on and off while idle.
- **Recovery after the system reclaims the recorder is now seconds, not up to a minute** — the old
  behaviour left "starting up" showing long after recording was actually ready.

## [1.3.1] — 2026-07-22

A reliability release for recordings saved to cloud folders (e.g. Google Drive), based on field reports.

### Fixed
- **Recordings to a Google Drive folder no longer fail or vanish.** Some storage providers (Google
  Drive, and other cloud/synced folders) reject the read-write mode the recorder needs, and report a
  file's size as 0 immediately after writing (their upload is asynchronous). This caused recordings to
  either fail to start (`Unsupported mode: rw`) or be **falsely detected as empty and deleted**.
  CallVault now records into on-device storage first and copies the finished file into such folders,
  and it trusts the actual captured size instead of the provider's delayed report.
- **Honest error messages.** A storage failure is no longer mislabeled as "ADB connection failed".

### Added
- **Cloud folders are blocked as the recording folder.** Picking Google Drive / OneDrive / Dropbox as
  the *device* recording folder is now refused with guidance to choose on-device storage — use the
  **Google Drive backup** option for cloud copies instead. Any existing cloud recording folder now
  shows a warning in Settings so it's no longer mistaken for local storage.

## [1.3.0] — 2026-07-21

The headline feature: **in-app updates**. CallVault can now tell you when a new version is out and
install it for you, without hunting for the APK on GitHub.

### Added
- **In-app updater (manual).** CallVault checks GitHub daily (and when you open the app) for a new
  release. When one is available you get a notification and an **Update** banner on the Home screen;
  tap it and CallVault downloads, verifies, and installs the update itself, then reopens on the new
  version and confirms with an "updated" banner + notification. No more manually downloading APKs.
  - The download is **resumable** — a slow or flaky connection resumes where it left off instead of
    restarting the ~80 MB file.
  - Every update is **signature-pinned**: the downloaded APK is checked against CallVault's release
    certificate (and must be a genuinely newer build) before anything is installed.
  - The install also re-grants the permission an app update otherwise drops, so recording keeps
    working seamlessly across updates.
  - A **"Check for updates"** toggle (Settings → Updates, on by default) turns the checks off if you
    prefer to update manually.

### Notes
- Installing is always an explicit choice — CallVault never installs an update on its own.
- Distribution is still sideload/F-Droid/Obtainium; the in-app updater is an added convenience for
  GitHub releases, not a replacement.

## [1.2.3] — 2026-07-19

### Fixed
- **Filenames no longer lose the caller for unsaved numbers.** With a file-name template using
  `{contact_name}`, calls from numbers not saved in your contacts produced a name with an empty
  contact segment — no way to tell who the recording was from. The placeholder now falls back to
  the **phone number itself** when there is no saved contact (and it's not voicemail). If your
  template already includes `{phone_number}`, the fallback stays empty so the number isn't
  written twice.

## [1.2.2] — 2026-07-19

A field-report fix release: voicemail calls get their proper name, and the app now tells the truth
when recording can't work instead of pretending everything is fine.

### Fixed
- **Voicemail calls are now labeled correctly.** Carrier voicemail short codes (e.g. `123` in
  France) were being "standardized" into an invalid international number (`+33123`), which broke
  contact-name resolution — and voicemail isn't a real contact anyway, so lookups always came up
  empty. Short codes now keep their raw form, and calls to the carrier voicemail number are labeled
  with a localized **"Voicemail"** name in file names and the recordings list.
- **No more false "Ready to record" while Developer options is off.** If Developer options is
  disabled (e.g. after an OS update or manual toggle), the recorder daemon cannot survive and every
  "recording" comes out empty — but the Home screen still showed green. It now shows a clear red
  **"Developer options is off"** status explaining what to re-enable.
- **Empty recordings are no longer saved as if they succeeded.** A 0-byte file (daemon died before
  capture started) used to be cataloged, copied to Drive, and shown as an unplayable entry
  ("Can't read this file"). It is now deleted and reported with an error notification instead.
- **You are warned during the call if recording silently stops.** The app now watches the recorder
  daemon while a recording is live and immediately notifies **"this call is NOT being recorded"**
  if the daemon dies mid-call, instead of discovering the loss after hanging up.
- **Post-reboot and startup notifications are now translated.** The "Ready to record calls /
  Listening for calls after restart" notification (and the boot-time "Preparing call recorder…")
  were hardcoded in English; they now follow the app language like everything else.

### Internal
- Cross-country detection is preserved for invalid/foreign numbers (the ignore-cross-country
  auto-record rules keep working for them).
- The unit-test harness (JUnit/Robolectric) now lives on `main`, with tests covering the number
  enrichment, voicemail matching, file-name fallback, and Developer-options detection.

## [1.2.1] — 2026-06-26

A localization release: every shipped language is now fully translated, fixing screens that
appeared in English even when the rest of the app was localized.

### Fixed
- **Onboarding and main screens now follow the selected language.** The disclaimer/first page, the
  permissions and setup wizard, and the home screen (recordings list + status) previously fell back to
  English for many strings — most visibly the **first page stayed English** even with a French
  device/app language. All shipped locales (fr, de, es, it, hu, pl, ru, vi, zh-rCN) are now at **100%
  coverage** (289 UI strings each).
- **Recorder & pairing notifications are now translated.** The cold-start "Call recorder starting up…
  / Ready to record calls" status and the wireless-debugging pairing notifications were hardcoded in
  English; they are now string resources and localized in every language (using each locale's official
  Android wording for "Wireless debugging").

### Changed
- **Translation coverage is now enforced at build time.** Lint treats `MissingTranslation` and
  `ExtraTranslation` as errors, so a new untranslated string can no longer ship silently.

### Internal
- Hardened two notification posts (`DebugNotificationHelper`, `RecorderReadinessNotifier`) with an
  explicit `POST_NOTIFICATIONS` check, resolving the corresponding `MissingPermission` lint errors.

## [1.2.0] — 2026-06-25

A reliability release focused on **recording the first call after a reboot**, plus a rebuilt,
user-facing debug/bug-report flow and an audio default better suited to voice.

### Added
- **Debug section (always visible).** Settings now has a simple **Debug** section: turn on diagnostic
  logging, see an **"ON" reminder** (in-app warning + a persistent notification) so you don't leave it
  running, and **Share debug logs** in one tap via the system share-sheet to send a bug report. Logs
  are phone-number **redacted**.
- **24 kbps audio bit rate**, flagged **Recommended** and now the **default** for Opus — plenty for
  intelligible voice; higher rates only inflate file size.

### Changed
- **Removed the hidden "Developer Options"** (the 7-tap unlock, test-call simulator, and the
  redaction-off "Debug mode"). Log **redaction is now always on** and cannot be turned off.
- After a reboot **or an app update** the app briefly shows **"Call recorder starting up…"**, flipping
  to **"Ready to record calls"** once recording is actually possible — so you know when a call will be
  captured. Only appears while the recorder daemon is cold (nothing shown when it's already warm).

### Fixed
- **First call after a reboot now records.** A new bounded post-boot **live call-state listener**
  detects calls in real time instead of relying on the system `PHONE_STATE` broadcast, which on a
  freshly-booted device could arrive **~9 seconds late** — after the call had already ended.
- **Faster recorder warm-up after boot** (~5 s vs ~15 s): trimmed a redundant Wireless-Debugging wait
  and skip the stale-daemon scan in the first 90 s after boot (a reboot already cleared any daemon).
- **Daemon cold-start no longer over-waits.** The launcher returns the instant the daemon's binder
  arrives instead of blocking out the full keep-alive window, recovering calls that were previously
  aborted 1–3 s too late.
- **Redaction can no longer be left disabled.** A leftover developer "Debug mode" flag could keep real
  phone numbers in shared logs; redaction is now unconditional.
- **Number-less recordings rename correctly.** The end-of-call CallLog rename now uses
  `DocumentsContract.renameDocument` (the previous call threw on single-document SAF URIs), so files
  get the contact/number in their name.

## [1.1.1] — unreleased

A stability + features release built on top of v1.1.0. It keeps v1.1.0's proven on-demand
Wireless-Debugging behaviour (the daemon "keep-alive" experiment that made Wireless Debugging
repeatedly turn itself on was **not** included) and adds the safe fixes plus new settings.

### Added
- **Retention / auto-delete.** Automatically delete recordings older than a chosen period
  (Daily / Weekly / Bi-weekly / Monthly, or Keep forever). Set **one period for both device & cloud,
  or a separate period for each**. A daily background sweep runs at a **time you choose**, anchored to
  the **device's local time zone** (re-anchored automatically when you change time zone). Defaults to
  off; enabling it asks for confirmation.

### Changed
- **Settings reorganised.** "Recording & storage" is now **two separate sections** — **Recording**
  (filename template, auto-record rules) and **Storage** (target, device folder, Drive folder).
- **Accordion settings.** Sections open **one at a time** (Recording open by default); opening one
  collapses the others.

### Fixed
- **Phantom Drive recordings** no longer appear in the Home list — the list is now backed by a
  standalone on-device catalog instead of Drive's stale index.
- **Folder pickers** now open at their own currently-selected folder (best-effort; OEM file pickers
  may still ignore the hint).
- **Onboarding no longer skips the ADB step** after a reinstall (Auto Backup disabled), plus clearer
  guidance for the OEM per-app battery mode (e.g. OxygenOS "Allow background activity").
- **Stuck microphone** fixed: recording start is aborted if the call ends during daemon cold-start.

### Performance
- Faster scrcpy socket connect (tighter polling).

### Security / internal
- Removed the exported debug-only broadcast receivers used during development.

## [1.1.0] — 2026-06-11

Complete visual redesign ("Signal" theme) plus UX polish: setup wizard, Home screen with in-app
playback and filters, smoother Wireless-Debugging pairing. See the
[v1.1.0 release](https://github.com/madkongo/CallVault/releases/tag/v1.1.0).
