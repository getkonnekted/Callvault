# Backlog

Agreed work that is **not** started, so it does not get lost between sessions. Ordered by the value it
delivers, not by effort. Anything already researched lives in `capture-research-directions.md`; this
file is for decided product and engineering work.

Status key: 🔵 agreed, not started · 🟡 in progress · ✅ done (kept briefly, then deleted)

**A section header is a claim about a release, so verify it against the tags rather than trusting it.**
Every ✅/🟡 below now names the release it shipped in. On 2026-08-14 three sections still read "not yet
on a device" / "awaiting device test" for work that had shipped in **v1.5.5**, two releases earlier —
they were written before that release was cut and never revisited, and reading them cost a session's
worth of wrong conclusions about what was pending. When a release is cut, reconcile this file:

```bash
# does <tag> contain the file that implements <entry>?
git ls-tree -r <tag> --name-only | grep -c '<TheClass>.kt'
```

Use `git ls-tree`, not `git cat-file -e` inside a shell loop — the latter's exit code interacts badly
with `&&`/`||` chains and silently reported the opposite answer while this was being checked.

---

## Current state — 2026-08-05, status reconciled against the tags 2026-08-14

Kept at the top so a session can start from disk instead of from recall. **Update it whenever a
release is cut, a branch lands, or something starts or stops being blocked.**

**Released:** `v1.5.7` is the latest release users can get (versionCode **10720**, tag `v1.5.7`, asset
`CallVault.apk`, published 2026-08-04 and verified byte-identical to the locally built artifact). Also
published, both **pre-releases** invisible to the in-app updater and **never to be merged**:
`v1.5.2-diag-scrcpy` (issue #18, branch `diag/scrcpy-only`) and `v1.5.7-loopbackdiag` (issue #22,
branch `diag/loopback-oneui`).

**What 1.5.7 shipped:**

- **Daemon + system log collection** (`2026-07-28-daemon-and-system-logs-design.md`). Ring grows on
  logging-enable and restores on disable; Share attaches a filtered, redacted logcat slice. Verified
  on the OP12.
- **CodeQL triage** of 2026-08-01: ten alerts dismissed with rationale on the alert itself, three
  relative-path-command **fixed** (`sh`/`pkill` by absolute path).
- **Retention actually deletes what it promises** — four faults, all found by measuring the OP12 on
  2026-08-04 and all fixed and device-verified the same day. See below.
- **The USB-mode warning is no longer hidden behind readiness**, `UNKNOWN` is surfaced instead of
  silently treated as safe, One UI 8's "Debugging only" is recognised as safe, the mode is resolved
  from `sys.usb.config` where `dumpsys usb` omits it, and the picker no longer spins on a confirming
  read-back that could not work. Device-verified except the `COULD_NOT_CHECK` message, which needs a
  phone whose mode was never read.

**Open:** issue **#22** (Galaxy S25, One UI 8.5) — the reporter holds `v1.5.7-loopbackdiag` and has not
yet sent logs. Nothing in 1.5.7 addresses their loopback failure. **1.5.7 will be offered to them as a
normal update, which replaces the diagnostic build and loses its instrumentation** — tell them not to
update if those logs are still wanted. Issue **#18 is closed**: the reporter found the cause himself
(Meta Ray-Ban glasses), written up in `2026-08-04-bluetooth-headsets-and-silent-recordings.md`.

**Waiting for the next release — this is the whole list, one item:** `fix/voip-carrier-collision`
(`75868ca`) — a carrier call could be mistaken for an app call on ROMs that route calls over IMS.
Merged to `main`, unit-tested, **not yet run on a device**; see the section below for what to check
when it ships. Note it is on **local `main` only** — `main` sits 4 commits ahead of `origin/main`.

**What each recent section actually shipped in, verified with `git ls-tree` on 2026-08-14.** The
sections below are the detail; this table is the truth about *release membership*:

| Entry (implementing file) | Shipped in |
|---|---|
| Keep-alive rewarm latch (`RewarmGate.kt`) | **v1.5.5** |
| Upload schedule in Settings, issue #20 (`SyncScheduleLabels.kt`) | **v1.5.5** |
| Settings "General" restructure (`SettingsSidebar.kt`) | **v1.5.5** |
| Resilient-recording ring + guard fix (`handoff/HandoffGeometry.kt`, `GUARD_FRAMES = 960`) | **v1.5.3** |
| No install while recording (`CallInProgressGate.kt`) | **v1.5.6** |
| Encoder validation (`EncoderLimits.kt`) | **v1.5.6** |
| VoIP/carrier collision (`VoipTelephonyGate.kt`) | **unreleased — `main` only** |

**The retention story, because it cost a day and the shape recurs.** With retention set to 7 days the
app showed a convincing 64 recordings going back exactly 7 days while **131 files had outlived the
window** (8 device, 123 Drive, oldest by 48 days). Four independent faults:

1. `deleteFile` cleared the catalog entry even when the delete failed, so a failed Drive delete made
   the file invisible *and* unreachable for ever. Fixed: the entry survives a failed delete.
2. The sweep walked only the catalog, so anything missing from it was exempt regardless of age. Fixed:
   it reads the folders too, gated on `RetentionPolicy.isEligible` (only names CallVault writes) and
   never deleting a file whose age is unknown.
3. `ExistingPeriodicWorkPolicy.UPDATE` ignored the new initial delay, so changing **Run at** moved
   nothing for up to a day. Fixed: `CANCEL_AND_REENQUEUE`.
4. **Google Drive renumbers the account slot inside its SAF URIs** (`acc=1` → `acc=4` here), which
   invalidates every stored Drive URI — uploads, deletes and listings all throw SecurityException, and
   re-picking the folder does not repair the URIs already stored. Fixed: `DriveCatalogRepair`
   re-points them against the live listing before each sweep. **This is a recurring hazard, not a
   one-off** — it will happen again whenever the user's Drive accounts change.

Device-verified 2026-08-04 across three sweeps: `deletedLocal=9`, then `deletedDrive=124`, then
`63 re-pointed, 1 forgotten`. The one pre-cutoff file the sweep deliberately left alone was
`callvault-signing.keystore`, sitting in the same Drive folder — the eligibility gate earning its keep
on its first real run.

**Hard constraint on the next release:** versionCode must exceed **10720** — what 1.5.7 shipped as, and
what the maintainer's OP12 now carries. The floor climbs faster than the version number: 1.5.6 shipped
as 10670, then test builds and a published diagnostic pre-release took it through 10678, 10680, 10690
and 10700-10714 before 1.5.7 was cut at 10720. Anything at or below the floor installs for most users
and silently fails on the devices that matter most. See the `release-version-bump` memory, and read the
phone before choosing.

**Device-verified 2026-07-31 (OnePlus 12, build `1.5.6-encoder` / 10661):** encoder validation does
*not* divert recording to the scrcpy fallback — output was mono 48 kHz, full duration, −16.9 dB mean.
The mid-call guard's device path is covered by unit tests only. **Known gap:** the new
`CV:EncoderLimits` line runs in the *daemon*, so it never reaches the app's debug log; it does reach
logcat, but logcat's default 256 KiB ring holds barely a minute on this phone (measured 2026-07-31:
123 KiB consumed in 26 s), so it had aged out before it could be read. An earlier note here blamed
ColorOS for filtering third-party logs — that was wrong, and re-tested: our lines are present. The
fix is the ring growth in `2026-07-28-daemon-and-system-logs-design.md`.

**Known limitation, parked, not planned:** a Bluetooth headset or smart glasses can make a carrier
recording silent — right size, right duration, no audio. This was the real cause of issue #18 (Meta
Ray-Ban glasses), found by the reporter after about a week. See
`2026-08-04-bluetooth-headsets-and-silent-recordings.md`. **If a silent-recording report ever arrives
again, ask what the audio was routed to before anything else.**

**Blocked on other people:** nothing.

**Written but unplanned:** `2026-07-28-daemon-and-system-logs-design.md` — a design for getting daemon
diagnostics into a bug report, with no implementation plan yet. Issue #18 is the standing argument for
it: twice, the answer lived in the daemon's process where no bug report can reach.

**Also argued for by issue #18, not yet scheduled:** `SILENT` detection (an all-zeros check on the
daemon's PCM, cheap on the direct path) and a settings snapshot in the log-export header (the export
carries device and version but not the toggles, which is why the VoIP question above needs a manual
test at all).

---

## 🟡 A carrier call could be mistaken for an app call — FIXED, THE ONE ITEM AWAITING RELEASE

**Branch `fix/voip-carrier-collision`, commit `75868ca`. Fold into the next release and device-test it
there.** Not device-tested: the fixed path cannot fire on the OP12 (see below), so the only thing a
device run proves is the absence of a regression — one WhatsApp call and one carrier call, both
recording as before.

App-call detection recognises a VoIP call by one signal, the audio mode being
`MODE_IN_COMMUNICATION`. Nothing enforced that a carrier call could not set the same mode; the only
thing separating the two paths was a comment in `VoipCallDetector` asserting they "cannot collide".
Wi-Fi calling and some VoLTE stacks carry the call over IMS and can present as
`MODE_IN_COMMUNICATION` — and there the app-call path would record a phone call the carrier path is
already recording, holding a plain `MIC` capture that contends with the dialer, made worse by the
1.5.5 microphone-reclaim logic taking it back mid-call.

`VoipTelephonyGate` makes the telephony call state the authority: no start while ringing or off-hook
(the ring matters — the mode moves around during call setup), and a running capture stops once a
carrier call is answered, signalled by the telephony broadcast because on such a ROM the mode never
changes and the mode listener never fires. Fails open on an unrecognised state, like
`CallInProgressGate`.

**Measured on the OP12, 2026-08-05, and worth keeping:** every carrier call went to `MODE_IN_CALL` set
by `com.android.server.telecom`; only WhatsApp used `MODE_IN_COMMUNICATION`. So the collision is real
in principle and absent on this hardware — which is exactly why it survived unnoticed.

**Where this came from:** a user report that "the mic is always on during a cell call", which turned
out not to be a bug at all. Measured on the OP12 mid-call: a live `AudioIn` thread, `Standby: no`,
`AUDIO_SOURCE_VOICE_CALL` on `AUDIO_DEVICE_IN_TELEPHONY_RX`, reading continuously for the duration of
the call and gone afterwards — the carrier recorder doing its job, attributed to `com.android.shell`
because that is the uid the daemon runs as. Android shows the privacy indicator for any active
capture and it cannot be suppressed. The reporter's belief that 1.5.6 did not do this was checked and
dropped: 1.5.6-era recordings exist, so the capture — and the indicator — was running then too.
**If this is reported again: the indicator is the recording. Ask whether recordings exist for the
period they think was quiet.** There is nothing to fix short of not recording, and the gap is
documentation — nothing in onboarding warns that the indicator appears on every call and is
attributed to Shell rather than CallVault.

**Checked against history, 2026-08-07, so nobody re-derives it.** There is no release in which a call
was recorded without a shell-uid capture. At `v1.4.7` the carrier path is `DirectAudioRecorderSession`
opening `AudioRecord` itself, annotated *"shell uid holds `CAPTURE_AUDIO_OUTPUT`; the daemon is not an
app"*. Direct capture only arrived in `v1.4.0` (`7df300a`); before it everything went through
scrcpy-server, which `ScrcpyConfig.kt:21` at **`v1.1.0`** — the earliest tag — describes as running
"with `app_process` … the shell user (UID 2000)". The app has **never** declared `RECORD_AUDIO`, so
shell was always the only possible attribution. Capture code between `v1.4.7` and `v1.5.7` moved only
for `EncoderLimits` bit-rate validation and 1.5.5's mic re-take, neither of which changes the source,
the uid, or how long the capture is held.

**What that check cannot cover:** it proves our capture never changed, not that the OS always
*displayed* it the same way. A ROM update altering how shell-uid captures are surfaced would look
exactly like a CallVault regression and leave no trace in this repo. If a second user reports it and
their recordings also check out, look there.

## 🔵 Our captures do not register with `AudioService`'s record tracking

Noticed while investigating the above, unexplained, and left alone deliberately. During a live carrier
call on 2026-08-05 the HAL-level capture was plainly running (`AudioIn_5C6`, frames read climbing)
while `dumpsys audio`'s record-activity log carried **no matching `rec start`** — and the same log
shows `rec stop` events at 10:22, 12:22 and 13:58 with no starts either. Starts register sometimes
(11:50 that day, and the 08:59 VoIP `MIC` capture) and not others.

It causes no stuck microphone and no lost audio, so it is not urgent. It does mean the OS's view of
who is recording disagrees with reality, which would affect anything keyed off record-configuration
callbacks. Worth understanding before relying on that API for anything.

---

## ✅ Don't install an update while a call is being recorded — SHIPPED in v1.5.6 (`4e46948`, merged `2e5c0dc`)

`CallInProgressGate.mayInstall()` is checked in `UpdateInstaller.installSilentlyViaShell` *before* the
heavy-operation lock; a blocked attempt returns `ShellResult.DEFERRED_CALL_IN_PROGRESS`, which clears
the pending tag and cancels the progress notification **without** falling back to the interactive
installer. It guards VoIP as well as telephony, because a VoIP call never sets the telephony call
state. Six unit tests. Known limit: a call that *starts* mid-install is not covered — aborting the
installer would leave a half-written APK, which is worse.

**Found the hard way, 2026-07-30.** An APK installed over the running app kills the app process, and
any recording in flight dies with it. Observed on the OP12: one call became two files, and the second
was mislabelled `out_` with no contact name, because on restart the app saw the already-running call
as a fresh outgoing one. Nothing was lost — the first file closed cleanly and the second picked up —
but the call is split, and the seam is silent for as long as the app takes to come back.

**Why it is not just an adb mishap.** `UpdateInstaller` installs over ADB exactly the same way. It is
tap-to-install, so a user initiates it, but nothing stops them tapping Install mid-call — and an
update notification is precisely the kind of thing people poke at idly. Sideloads and Obtainium
updates do it too. So the app can truncate the recording it exists to make.

**What.** Before `UpdateInstaller` takes `AdbShell.heavyOperationLock` and starts streaming the APK,
check whether a call is in progress; if it is, defer and say so ("Update will install after your
call"). Checking, downloading and notifying are unaffected — only the install step waits.

**Notes.** Small: a state check plus a deferral. The app already tracks call state in
`CallSessionManager`, so no new permission is needed. Re-trigger the deferred install from the IDLE
transition `PhoneStateReceiver` already sees.

**Known limit, worth stating rather than pretending otherwise.** A call that starts *during* an
install is a few seconds a pre-check cannot close. Closing it properly would mean aborting the
installer mid-stream, which is worse than the problem — a half-written APK is a broken app.

---

## ✅ VoIP near-party drops out on One UI — FIXED by re-taking the mic, SHIPPED in v1.5.5

On a Galaxy S24 FE, One UI **silences** our shell-uid MIC capture intermittently while the VoIP app
holds the mic — logged by the platform itself as `rec update uid:2000 src:MIC silenced
pack:com.android.shell`. The loser of that arbitration keeps receiving buffers full of zeros, so the
recording gets silent holes rather than failing. Measured: ~6 s lost from a 21 s call, matching a flat
-107 dB stretch in the file to within a second. The OnePlus 12 records the same call cleanly.

The platform's own bypass permission (`BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION`) is
`signature|privileged` and `pm grant` refuses it, so shell access cannot open it.

**Minimum viable response: report it.** `VoipCaptureSession` has `farPartyHeard` and no near-party
equivalent, and `substituted` counts only *missing* chunks — a silenced capture delivers present,
zero-filled ones, so the log reads `farPartyHeard=true, 2 silence-filled chunks` on a recording with
a six-second hole. A near-side silence check turns an invisible failure into a visible one.

**HOTWORD was tried and is dead** — it constructs, reports `STATE_INITIALIZED`, then `read()` returns
0 and the platform never registers the capture at all. Reverted.

**Separate bug it exposed, worth fixing on its own:** when the near feeder dies, `captureLoop` is
paced by `CHUNK_WAIT_MS` poll timeouts and encodes slower than real time — an 11 s call became ~6 s of
audio. Losing one source should cost that source, not the recording's length.

Full evidence: `2026-07-30-voip-near-party-silenced-on-one-ui.md`.

---

## 🟡 VoIP-only mode SHIPPED in 1.5.6; Shizuku coexistence still open

Investigated 2026-07-30, no code changed. Full write-up:
`2026-07-30-voip-only-mode-and-shizuku-coexistence.md`.

**✅ Done in 1.5.6.** The naming became a real switch instead: `CARRIER_RECORDING_ENABLED`, off =
phone calls ignored end to end. That turned out to be necessary rather than cosmetic — the
combination this entry described (both auto-record toggles off) is the **Ask me** state, and
`CallSessionManager` still sent `ACTION_STANDBY`, so an app-calls-only user was prompted on every
phone call. One new preference each side (`VOIP_AUTO_START` too), both defaulting to the old
behaviour; no existing state was duplicated.

**Shizuku does not crash; we kill it.** Both apps' helpers are children of an `adbd` shell, and any
`adbd` restart kills every process started over ADB — established here already and quoted from
Shizuku's maintainer in `transport-and-daemon-architecture.md`. CallVault restarts `adbd` in exactly
two places that matter: arming loopback (`tcpip:`, always destructive, once per boot) and enabling
Wireless debugging. The routine WD-off after each daemon launch is harmless *when USB debugging is on*
(pid measured unchanged), which is why users will report it as intermittent.

Cheapest honest response: detect Shizuku and warn before arming offline recording.

**Full Shizuku support researched 2026-07-30** — `2026-07-30-shizuku-support-feasibility.md`. It fits
better than expected (Shizuku's `UserService` *is* our daemon: our code, uid 2000, and the daemon
already runs its own shell commands via `ProcessBuilder`, so it needs no ADB), and the app touches the
daemon through one AIDL from five files, so most of the work is in how it starts.

**But the reliability argument fails.** Shizuku's server is itself an `adbd` child, so a screen-off
`adbd` restart kills it exactly as it kills ours — this repo already quotes Shizuku's maintainer on
that. Shizuku stops CallVault *causing* churn; it does not survive it. And it costs hands-free
operation after reboot, which is a stated differentiator.

Recommendation: warn first, extract a `PrivilegedProvider` abstraction regardless (worth it alone),
and treat full support as a product decision rather than a fix.

**Not chasing the reporters.** A set of diagnostic questions used to live here (USB debugging on?
offline recording on? does Shizuku stop once or repeatedly?). Dropped 2026-07-31: when Shizuku is
actually picked up, it gets tested properly on our own devices rather than reconstructed from
second-hand answers. The analysis above stands as a hypothesis until then.

---

## 🔵 README is out of date after 1.5.5

Screenshots predate the Settings panel and the two new wizard steps; **"Settings ▸ Experimental"** is
referenced throughout but the path is now **Settings ▸ General ▸ Experimental**; and the VoIP
compatibility table does not know Samsung/One UI works as of 1.5.5. Screenshots were already stale
before today — regenerating them needs care about real contacts in a public repo.

---

## ✅ Validate the encoder before recording into it — SHIPPED in v1.5.6 (`1ee77af`, merged `2e5c0dc`)

`EncoderLimits.resolveBitRate()` clamps the requested rate into the encoder's advertised range and
logs `encoder=… bitrate=[min..max] requested=… resolved=… sampleRate=… supported=… channels=…
supported=…`; `supports()` additionally requires `EncoderLimits.supportsFormat()`. Six unit tests.
Verified on the OP12 (`1.5.6-encoder`): the direct path is still chosen — mono output proves it, since
the scrcpy fallback is stereo. **But the log line is written by the daemon**, so it reaches neither the
app debug log nor a shareable report; it reaches logcat, but the default ring ages it out within about
a minute of a busy phone. Until the
daemon-logging design lands, this diagnostic cannot answer the bug reports it was built for.

`DirectAudioRecorderSession.hasEncoder()` checks only that an encoder for the MIME **exists** —
nothing verifies it supports our sample rate, channel count or bitrate, and `KEY_BIT_RATE` is set to
whatever the user picked. `MediaCodec` given an out-of-range combination does not reliably throw: it
can clamp or emit frames that decode to nothing, i.e. a correctly-sized file that plays silent.

Measured on a OnePlus 12: the software AAC encoder allows 8000-960000 bps and ≤6 channels, the
Qualcomm hardware one 4000-192000 and ≤2. The hardware encoder is gated behind
`special-codec required`, so it is not selected here — but a vendor that does not gate it would hand
us an encoder with different limits and nothing would notice.

**What.** Query the selected encoder's `AudioCapabilities`: clamp the bitrate into range, refuse the
direct path when the sample rate or channel count is unsupported (so it falls back to scrcpy rather
than producing silence), and **log the encoder name and its ranges once per recording**.

Raised by issue #18, which closed unexplained — see
`2026-07-28-issue-18-silent-carrier-recordings.md`. That log line would have answered it on day one.

---

## 🔵 Manual "Check for updates" in Settings

**Why.** A release only surfaces two ways: a check when the app opens, throttled to once per 6 hours
(`UpdateScheduler.checkNowIfDue`), and a 24-hour periodic worker. Open the app shortly *before* a
release lands and you cannot see that release until the throttle expires or the daily worker runs —
with no way to ask. This happened for real while testing 1.4.7: the phone checked at 14:21, the release
was published at 14:29, and the only way to force a check was toggling the update switch off and on,
which is not something a user would ever guess.

**What.** A row under Settings ▸ Updates that runs a check immediately and reports the outcome in
place — "You're up to date" / "Version X is available". Bypasses the 6-hour throttle, since the user
asked explicitly; keep the throttle for the automatic path so relaunches cannot hammer the GitHub API.

**Notes.** `UpdateScheduler` already has everything needed — a one-time `UpdateCheckWorker` request is
what `checkNowIfDue` enqueues. The work is the UI row, the in-place result, and not letting repeated
taps stack (unique work + KEEP, as the install path does).

---

## ✅ Keep-alive rewarm latch can permanently stop recording — SHIPPED in v1.5.5

**Found 2026-07-30 on the OP12: the daemon had been dead ~21 hours and the app never retried.**
`DaemonKeepAliveService.maybeRewarm` guards on a `rewarming` flag that only the worker thread clears.
The worker calls `ensureServerRunning` → `AdbShell.ensureConnected`, which is unbounded and blocks
forever on a half-dead (`CLOSE_WAIT`) connection. One hung attempt latches the flag true for the life
of the process, so the 60 s watchdog returns at its first line forever. Recording is dead until the
app is force-stopped — which no user will ever think to do.

Shipped in **v1.4.0** (`a2b248e`); any adbd churn triggers it (cable, screen-off restart, toggling a
debugging switch). Likely behind field reports of "it just stopped recording".

**Fixed 2026-07-30** by `RewarmGate` (expiring latch, unit-tested) plus a bounded relaunch that drops
the half-dead connection so the abandoned thread frees `heavyOperationLock`.

**Shipped in v1.5.5** (`RewarmGate.kt` is present in the v1.5.5, v1.5.6 and v1.5.7 trees), so the
exposure window was v1.4.0 → v1.5.3 and only users still on **v1.5.3 or older** are affected. This
entry read "Still to do: run it on a device, and ship it" until 2026-08-14, three releases after it
shipped — the mistake this file's header warning now exists to prevent. Still true: **no device run
was ever recorded for it**, so it shipped verified by unit tests alone.

Full diagnosis and the fix's shape: `2026-07-30-keepalive-rewarm-latch-wedge.md`.
The `ensureConnected` entry below is the same bug's root and is still open — layers 1-2 contain it
rather than remove it.

---

## ✅ Upload schedule is built but stranded in the wizard — issue #20, SHIPPED in v1.5.5

**Issue:** https://github.com/madkongo/CallVault/issues/20 (CathaEdulis, 2026-07-29)

**The feature already exists and works end to end.** `SyncScheduleMode` is `IMMEDIATE | DAILY |
WEEKLY`, with `SYNC_TIME_HOUR`/`SYNC_TIME_MINUTE`/`SYNC_DAY_OF_WEEK` alongside it.
`StorageRouter.route()` checks the mode and, for DAILY/WEEKLY, skips the immediate copy entirely and
hands off to `SyncScheduler`'s periodic sweep. Verified in code, not assumed.

**The bug is reachability.** The picker is rendered *only* in `WizardScreen`, and there is no way to
re-run the wizard. So the mode is decided once, during onboarding, defaulting to `IMMEDIATE` — and
after that no user can ever change it. That is exactly what the reporter hit: "I couldn't find it
anywhere."

**What.** Surface the existing picker in Settings. Cheap, because nothing new has to be built:

- The composable and its `scheduleModeTitleRes`/`scheduleModeDescRes` helpers exist in `WizardScreen`
  — extract them to a shared component rather than duplicating.
- The strings exist too (`wizard_schedule_*`, `wizard_ui_schedule_*`) and are **already translated in
  all ten locales**, so `checkTranslations` will not bite. Reusing a `wizard_`-prefixed key outside
  the wizard is slightly off; renaming means re-translating, so prefer keeping the keys and noting why.
- `SettingsScreen` already has the time-picker pattern (it borrows those same wizard strings for the
  retention sweep, around `SettingsScreen.kt:687`).
- Call `SyncScheduler.apply(context)` on change — `WizardViewModel:137` is the reference.

**Done 2026-07-30.** Exposed as a sub-section of **Storage**, alongside **Retention**, which moved from
its own top-level accordion into the same section. Zero new strings — `wizard_schedule_title` ("When to
upload to Drive") and `wizard_schedule_mode_label` ("Upload schedule") already existed in all ten
locales, so `checkTranslations` stayed green with no translation round. The mode/day labels were
extracted to `ui/common/SyncScheduleLabels.kt` so the wizard and Settings cannot drift. The old
`SECTION_RETENTION` key now opens Storage, so a user whose saved open-section was Retention lands on
the section that contains it instead of a collapsed screen. The schedule rows are hidden when the
storage target is LOCAL, where there is no upload to schedule.

**Fits with** the General-section restructure below; do them together if that one lands first.

**Note for the reply.** Deferring uploads batches the Drive app's "uploaded" notifications into one
run per day/week rather than one per call. It reduces the noise, it does not remove it — say so
plainly rather than letting him expect silence.

---

## ✅ Settings restructure: a "General" section — SHIPPED in v1.5.5

**Why.** Settings has grown top-level sections that are really peers of each other, so the screen reads
as a flat list of everything rather than a shape.

**What.** A new top-level **General** section, with today's sections becoming sub-sections inside it:

- General
  - Visual settings
  - Experimental *(keeps its own Resilience / VoIP sub-grouping)*
  - Updates

**Done 2026-07-30** on `feat/settings-sidebar`, together with opening Settings as a right-side panel
instead of a destination. Nine top-level sections became six. `SettingsSubHeader` gained a quieter
nested variant, because General ▸ Experimental ▸ Resilience is three levels deep and the inner
grouping otherwise rendered at the same weight as its parent.

**Notes.** `SettingsScreen` already has `SettingsSubHeader`, used for the Resilience/VoIP split inside
Experimental, so the nesting pattern exists. Section expand-state is persisted by key — keep the
existing keys where a section keeps its identity, or the user's expanded/collapsed state resets. That
is why `SECTION_EXPERIMENTAL` is still the string `"reliability"` after that rename.

---

## 🔵 Split `AppPreferences` into per-domain interfaces

**Why.** `AppPreferences` is 686 lines, 60 keys and 107 accessors, and every subsystem in the app
reaches into it: the knowledge-graph build on 2026-07-27 measured 188 edges and a betweenness of
0.190, bridging 33 of 243 communities — from `DaemonKeepAliveService` to `Theme` to
`VoipRecordingCoordinator`. That number is a symptom, not the finding.

The finding is *how* it clustered. Community detection split the class's own members into six groups,
and the dividing line is the **storage primitive, not the subject**: boolean writers in one group,
string writers in another, int accessors in a third, `getStringSet`/`setLong` in a fourth. Only the
storage/sync accessors clustered by meaning. There is no domain structure inside the file for the
algorithm to find, because the file has none below the comment headers.

**What.** Keep one `SharedPreferences` instance and the `Key` enum. Expose them through roughly nine
narrow interfaces — `RecordingPrefs`, `StoragePrefs`, `TransportPrefs`, `UpdatePrefs`,
`AppearancePrefs`, `FilterPrefs`, `DebugPrefs`… — each 5–12 methods, with `AppPreferences` as the
single implementation satisfying all of them. Consumers depend on the slice they actually use.

**The boundaries are already written in the file.** `Key` has 13 comment-delimited groups, and they
map nearly one-to-one onto communities that formed independently elsewhere in the graph: Storage
Routing / Sync Schedule, Retention, ADB, In-app updates, Persistent recorder server, Automation +
Filters, Developer & Debug, Audio quality, UI & Appearance. Those comments are doing an interface's
job. Cut along them and the split needs no new judgement.

**What this does not buy.** It does not decouple anything — `DaemonKeepAliveService` still needs its
settings. The wins are narrower and worth stating honestly: each consumer's dependency becomes
legible, tests can fake a 6-method interface instead of a 107-method class, and the "General" section
restructure above gets a seam to cut along. It is a wide, mechanical diff across most of the app, on
a file that has caused none of the recent failures — real value, no urgency. Do it *with* the Settings
restructure, not on its own.

---

## ⛔ Switching Wireless debugging off — NOT POSSIBLE, and why

Three attempts, all failed, all for the same underlying reason. **Do not try routes 1 or 2 again.**

**`adbd` only runs while USB debugging or Wireless debugging is enabled.** `service.adb.tcp.port` says
*where* `adbd` listens — it is not a reason for `adbd` to exist. With both switches off there is no
`adbd`, so there is nothing to launch the daemon over and nothing to keep it alive.

Measured on a OnePlus 12 (1.5.0-wdoff3, USB debugging off throughout):

```
11:02:15  Dropping Wireless debugging before launch
11:03:18  shell not ready within 60000ms (150 probes)   <- a full minute, never returned
11:03:20  Loopback self-healed after 1500ms             <- 1.5s AFTER WD was switched back on
```

The apparent success later in that log (11:04:25) is confounded — it is the exact moment USB debugging
was enabled, which starts `adbd`. **Every "it worked" observation in this investigation turned out to
have a second debugging switch on somewhere**, which is the single lesson most worth keeping.

- **Route 2 (make the daemon outlive `adbd`)** — impossible without root. Init kills the service's POSIX
  process group AND its cgroup on stop, explicitly so `setsid` cannot escape. Shizuku dies the same way
  ([#311](https://github.com/RikkaApps/Shizuku/issues/311)); its community's workaround is `adb tcpip`
  ([#864](https://github.com/RikkaApps/Shizuku/issues/864)), i.e. exactly our loopback — which does not
  solve it either.
- **Route 1 (launch over the loopback after dropping WD)** — cannot work, per the above. It also cost
  **two minutes of delayed readiness** at boot, since each attempt burns the full timeout. Reverted.

**One device difference:** on a Galaxy S24 FE the listener *did* return ~1.5 s after WD was dropped with
USB debugging off, so its `adbd` behaves differently from the OnePlus. If this is ever revisited, the
only defensible shape is **opportunistic and remembered**: after the daemon is up and idle (never on the
launch path), try the drop once, poll briefly, record the answer for that device — success keeps WD off,
failure re-enables it and never retries. That still leaves OnePlus-class devices with WD on.

**The real escape is to stop needing the daemon at call time** — Track A in
`capture-research-directions.md`. That is the only route that removes the debugging switch entirely.

---

## ✅ Control over what gets recorded — SHIPPED in 1.5.6

Three related asks, all about the user deciding rather than the rules deciding:

- **Decide per call, including app calls.** These were listed as two items ("manual VoIP recording" and
  "choose when to record") until 2026-07-31; the code says they are one. **Carrier already has it:**
  `RecordingNotificationHelper` posts a standby notification whose action is `ACTION_MANUAL_START`,
  plus pause/resume once running. **VoIP has nothing:** `VoipRecordingCoordinator` exposes only
  `onCallStarted`/`onCallEnded`, which are lifecycle callbacks the detector fires — there is no
  user-invocable entry point and no arm-without-starting mode. (An earlier version of this entry named
  the plumbing `VoipRecordingCoordinator.start/stop`. Those symbols do not exist.) The work is the
  arm-without-starting mode plus the carrier control surface extended to VoIP.
- **Undecided, deliberately kept separate:** a *prompt* at the start of a call. A dialog over a live
  call screen is a much more intrusive interaction than a notification you can ignore, it applies to
  both paths equally, and nothing has established it is wanted.
- **~~Turn cellular recording off independently~~ — already possible; see the VoIP-only entry above.**
  Both auto-record switches off with VoIP on *is* an app-calls-only recorder today, and
  `CallGapDetector` respects those flags so the health card will not nag. What is missing is only the
  naming. Careful when naming it: it must not silently disable recording for someone who expected it —
  default on, and state clearly what it does.

---

## ✅ Resilient recording on One UI — ring fix CONFIRMED, crackle fixed, SHIPPED in v1.5.3

**Root cause, confirmed against AOSP source rather than guessed.** The handoff sized the ring from
`AudioRecord.getBufferSizeInFrames()`. That returns `cblk->mBufferSizeInFrames`, a **logical value the
client rewrites on every attach** — it is *not* the field that sizes the ring. The physical ring is
`roundup(frameCount) * frameSize`, allocated immediately after the control block by
`AudioFlinger::TrackBase::TrackBase`. The two agree in stock AOSP only because both are seeded from the
same `frameCount`; nothing enforces it, and on a Galaxy S24 FE they diverged — 8192 reported against a
4096-frame allocation.

So this was never a Samsung quirk: **we were reading the wrong quantity**, and the OnePlus happened to
agree. Refusing the delivery was correct — trusting the report would have read 12 KB past the end.

**Fix:** derive the ring from the mapping, which is ground truth on every device —
`wrapFrames = min(roundup(reported), largest power of two fitting in (ashmemSize - dataOff) / frameSize)`.
Self-correcting, no device table, cannot over-read by construction.

**Also fixed alongside it:** `DATA_OFF` was hardcoded to 232, which is `sizeof(audio_track_cblk_t)` on
Android **13+** only — it is 228 on Android 12 and 224 on Android 11, and `minSdk` is 30. On those two
releases the ring was being read misaligned rather than failing loudly. Now derived from
`Build.VERSION.SDK_INT`.

**Trap for whoever tests this:** the native drain rounds whatever frame count it is given up to a power
of two and masks with it, so it must be passed `wrapFrames`, **not** `frameCount` — otherwise it
recreates the oversized ring and reads out of bounds, with the Java-side check now passing. Both call
sites were updated; a third caller added later would reintroduce the bug silently.

## Tested on a Galaxy S24 FE, 2026-07-30 — two results

**The ring-geometry fix is confirmed.** A 46 s carrier call with Resilient recording ON produced a
full-duration Opus file, mean −39.7 dB / peak −12.1 dB, and **no periodic dropouts**: silence-interval
`stdev/mean = 1.00` (irregular, i.e. conversational pauses), and click phase concentration `R = 0.03`
to `0.18` against every candidate wrap (1024/2048/4096/8192 frames) — a geometry error would cluster
near `R = 1.0`. The v1.5.2 fix works on the device it was written for.

**But the audio crackles, and the handoff is the cause.** Confirmed by A/B on the device: same phone,
same call type, same codec, one toggle.

| | crackle | mean | transients/s (>10x local) |
|---|---|---|---|
| Resilient ON | **yes** | −33.8 dB | 25.2 |
| Resilient OFF | no | −30.7 dB | 17.6 |

### Root cause: GUARD_FRAMES was smaller than one HAL write burst

`GUARD_FRAMES` held back **32 frames = 0.67 ms at 48 kHz**. But AudioFlinger's record thread does not
advance `mRear` sample by sample — it publishes a whole HAL period at a time, and while that copy is
in flight the frames just below `mRear` are **partially written**. HAL periods are typically 4-20 ms
(192-960 frames), so the guard was 6x to 30x too small and the drain read half-written frames.

That explains every property of the symptom: crackle rather than gaps (corrupt samples, not missing
ones), **aperiodic** (it depends where the 5 ms read cycle lands inside the write burst, which is why
the phase-concentration test against every candidate ring wrap came back R = 0.03-0.18), and
**vendor-specific** (a OnePlus 12 was clean throughout while every S24 FE call crackled).

Raised to **960 frames (20 ms)**, covering the largest common period. Cost: 20 ms more latency before
a frame reaches the encoder — irrelevant for call recording.

### Verified on the device, 2026-07-30

| build | transients/s (>10x local) | 3-6 kHz energy |
|---|---|---|
| handoff ON, guard 32 | 25.2 | 2.25% |
| handoff OFF (baseline) | 17.6 | 1.52% |
| **handoff ON, guard 960** | **16.3** | **0.39%** |

The fixed handoff now measures at or below the no-handoff baseline on both.

**A measurement lesson worth keeping.** With only the first two files, transient counts differed by
~40% (both swamped by speech consonants) and the spectra by a single point — the ear separated them
instantly and the metrics nearly did not. What discriminated cleanly was **3-6 kHz band energy**, and
only once a third file gave the scale. Do not read a flat two-way measurement here as "no defect".

### Daemon-kill test passed on One UI, 2026-07-30

The premise of the feature, demonstrated on Samsung for the first time. Mid-call, the daemon that
created the capture was `kill -9`'d:

- app pid **unchanged**, and the original `cv-handoff-drai` / `cv-handoff-enco` threads carried on
- the file grew straight through the kill with no stall (281 KB → 344 KB over the following 12 s)
- the keep-alive relaunched a fresh daemon behind it, as designed
- finalised as a valid 106 s Opus file
- **no seam**: only 7 of 300 windows within ±3 s of the kill dipped near the silence floor, and
  scattered rather than contiguous — ordinary conversational gaps, not a dropout

**A measurement trap, recorded so it is not re-walked.** A first pass compared 3-6 kHz energy before
(1.06%) and after (15.13%) the kill and looked alarming. It is an artefact: that metric is a
*proportion*, so it tracks level, not defects — loud passages read 0.1-1.6%, quiet ones 40-89%. A
per-5s time series showed the spikes at 10-20 s and 25-40 s, long before the kill, with the kill
window itself at 3.9%. Always plot the metric against time before attributing a difference to an
event.

**Follow-up worth doing:** derive the guard from the observed `mRear` step (the burst size the device
actually uses) rather than hardcoding 960.

---

## 🔵 "Test my setup" — prove the whole path works, before it matters

> **Superseded (2026-07-28) by [the setup-health design](2026-07-28-setup-health-status-design.md).**
> The button is gone: a test the user has to remember to press is not read by the people who need it
> most. The status card reports what real calls proved instead, and a call-log sweep catches calls
> CallVault never saw. The reasoning below still holds — only the shape of the answer changed.

**Why.** This app fails *silently*, and the failure is discovered after the call you needed. Every
recovery mechanism shipped so far (screen-lock USB fix, resilient recording, fast daemon recovery)
reduces the chance of a failure without ever telling the user whether their setup works **right now**.
Every naming bug in the VoIP feature was found by making real phone calls, because there is no other
way to exercise the path.

**What.** One action that runs the real pipeline end to end and reports which step failed:

1. ADB connection alive (and how long it took — this is where the ~75 s stall would show).
2. Daemon reachable, binder responsive.
3. Capture starts on the configured audio source.
4. Encoder produces non-silent frames.
5. File is created in the chosen SAF folder, catalogued, and appears in the list.
6. Clean teardown, and the test file removed.

Report per step, with the failing step named in plain language and a link to the setting that fixes it.

**Notes.** Do not fake it end-to-end: a test that stubs any step will pass while the real path is
broken, which is worse than no test. The daemon already exposes what is needed; a `VOICE_CALL` source
cannot be exercised outside a call, so the test should use `MIC` and say so, or capture briefly from
the configured source and report "could not verify without a live call" rather than implying more than
it checked. Reuse `voipFarPartyHeard`'s honesty pattern — report what was actually observed.

---

## 🔵 Per-app VoIP support, checked at runtime

**Why.** Settings currently says VoIP recording is experimental and "some apps block recording, and
this cannot be known until a call is under way". Half of that is now avoidable: whether an app opts out
of capture is readable **before** a call, from the audio flags its playback carries.

**What.** A per-app list under Settings ▸ Experimental ▸ VoIP calls — installed calling apps with a
real status each: verified working, not yet tried, or blocks capture. Turns a vague warning into a
fact, and tells the user *which* of their apps will work rather than leaving them to discover it.

**Notes.** The distinction that matters: `FLAG_NO_MEDIA_PROJECTION` (0x800) is bypassable by this
route and is what WhatsApp, Telegram and Signal all set; `FLAG_NO_SYSTEM_CAPTURE` (0x1000,
`ALLOW_CAPTURE_BY_NONE`) is checked before any permission and is **not** bypassable. Reading the flag
requires an active playback track, so it cannot be sampled at rest for an app that is not in a call —
expect "not yet tried" to be a real state, and record the observed result after each call instead of
promising a prediction. Feeds the README's tested-devices table.

---

## 🔵 `AdbShell.ensureConnected` is unbounded on the recording-start path

**Why.** It can block for ~75 s while a recording is trying to start. 1.4.6 capped one such read at
1.5 s after it caused calls to be missed entirely; this is the same class of problem, not yet fixed.
It was the agreed next priority before VoIP took over.

---

## 🔵 Smaller, known, and worth not forgetting

- **✅ Translations are complete and now enforced (2026-07-29).** Ten locales — pt-BR added, the other
  nine backfilled — at 445 translatable strings each. `./gradlew :app:checkTranslations` fails the
  build on a missing string, an orphaned one, or a placeholder that differs from the base; it gates
  `check` and `assembleRelease`, with `-PallowMissingTranslations=true` as the deliberate override.
  **This entry is the reason the check exists:** it previously read "three VoIP strings are
  untranslated" while eight locales were actually 46 behind and the three keys it named were in zero
  locales. Counting by hand is what failed, twice. Do not replace the check with a note.
- **Stale screenshots** in `docs/screenshots` (21 June) — predate the current UI.
- **`WD_DISABLE_WHEN_IDLE` is a dead preference** — read by nothing.
- **Wrong contact label** on some recordings (reported, not diagnosed).
- **CI release workflow is broken**: its `SIGNING_KEYSTORE` is a *different* key from the release key,
  so it fails at signing. Releases are built locally with `signing/callvault-signing.keystore`
  (cert `c875ffd0…`). Left broken deliberately — fixing it means putting the real key in CI.
  It also still names its artifact `ShizuCallRecorder-<version>.apk` and titles the release the same,
  while the in-app updater only accepts an asset named exactly `CallVault.apk`
  (`GitHubReleases.APK_ASSET_NAME`). A release published by this workflow would therefore be **invisible
  to the updater** — fix the naming at the same time as the signing, or the first CI release silently
  reaches nobody.
- **No instrumentation tests at all** (`app/src/androidTest` does not exist). 16 unit-test files cover
  parsing, version comparison and policy decisions; everything device-shaped is verified by hand on a
  real call. That is the honest state, and it is why regressions here are found by making phone calls.
