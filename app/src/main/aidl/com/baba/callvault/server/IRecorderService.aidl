/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server;

/**
 * CallVault Plan 5 — PRODUCTION recorder command channel.
 *
 * Implemented by the detached, privileged shell-uid recorder daemon
 * ([com.baba.callvault.server.RecorderServer]) and called BY THE APP over a raw binder
 * (no ADB), even while Wireless debugging is OFF. The daemon runs scrcpy-server + the muxer; the APP
 * owns metadata (SAF filename, call-log lookups) and merely hands the daemon a writable output fd.
 *
 * KISS simplification vs the Plan 5 draft signature: recording metadata stays APP-side, so the
 * interface only carries what the daemon needs (source/codec/bitRate + the output fd). The app names
 * the SAF file and performs call-log lookups itself, exactly as
 * [com.baba.callvault.services.recording.AudioRecordingEngine] does today.
 *
 * Mirrors Shizuku's IShizukuService command-channel pattern.
 */
interface IRecorderService {

    /**
     * Starts a recording session. The daemon launches scrcpy-server with the given [source]/[codec]/
     * [bitRate] and muxes the captured audio into [outFd] (a writable fd the APP opened from its SAF
     * file — the privileged daemon writes through it). No-op + rejected if already recording.
     *
     * @param source  scrcpy `audio_source` cliKey (ScrcpyAudioSource.cliKey, e.g. "voice-call").
     * @param codec   scrcpy `audio_codec` cliKey (ScrcpyAudioCodec.cliKey, e.g. "opus").
     * @param bitRate Encoder bit rate in bps.
     * @param outFd   Writable output file descriptor opened by the APP from its SAF recording file.
     */
    void startRecording(String source, String codec, int bitRate, in ParcelFileDescriptor outFd);

    /** Stops the active recording, finalising the container trailer. Idempotent. */
    void stopRecording();

    /** Returns true while a recording session is active. */
    boolean isRecording();

    /** Stops any active recording and terminates the daemon process. */
    void destroy();

    /**
     * "Resilient recording" (audio-capture handoff, Option B). The daemon creates a privileged
     * AudioRecord for [source] at [sampleRate], extracts the IAudioRecord binder + cblk ashmem fd, and
     * DELIVERS them to the app's provider ("sendHandoff"). The app then holds its own ref (keep-alive)
     * and reads the ring + encodes into ITS OWN output fd — so the recording SURVIVES the daemon being
     * killed mid-call. The push delivery runs synchronously inside this call, so on a true return the
     * app has USUALLY started capturing — but the caller must confirm via the app-side capture state
     * (this return only reflects that the daemon delivered, not that the app-side encode actually began).
     *
     * @param source     scrcpy `audio_source` cliKey (must map to a real MediaRecorder.AudioSource;
     *                   output/playback are not supported and must use startRecording instead).
     * @param sampleRate Capture sample rate in Hz (e.g. 48000).
     * @param channels   Preferred channel count (2 for voice-call to capture both directions, 1 for
     *                   mono sources). The daemon may fall back to mono and reports the ACTUAL count in
     *                   the delivery.
     * @return true if the daemon created the track and delivered the handoff to the app (delivery only;
     *         the app confirms live capture separately).
     */
    boolean startHandoff(String source, int sampleRate, int channels);

    /**
     * Arms the VoIP capture policy (a loopback-render AudioMix on USAGE_VOICE_COMMUNICATION playback).
     *
     * MUST be called BEFORE a VoIP call's audio track is created — Android fixes a track's routing at
     * creation, so arming mid-call leaves the call permanently unattached and the recording silent.
     * Arm when the feature is switched on, not when a call starts. Idempotent, and free while idle:
     * an armed policy holds no wakelock and no active record track.
     *
     * @return true if the policy is registered (false when the device does not permit it, most often
     *         a missing CAPTURE_VOICE_COMMUNICATION_OUTPUT on the shell package).
     */
    boolean armVoipCapture();

    /** Unregisters the VoIP capture policy. Idempotent. */
    void disarmVoipCapture();

    /**
     * Records a VoIP call, both directions, into [outFd]: the far party from the armed policy's
     * loopback sink and the near party from the microphone, mixed to mono with the chosen codec.
     * Requires [armVoipCapture] to have been called before the call began. Stop with stopRecording().
     *
     * @return true if capture started.
     */
    boolean startVoipRecording(String codec, int bitRate, in ParcelFileDescriptor outFd);

    /**
     * Whether the far party was ever audible in the last VoIP recording.
     *
     * False means the app opted out of capture, or this OEM build did not attach the call to our mix —
     * indistinguishable from here, and in both cases the user gets a one-sided recording. Queried after
     * stopping so the app can say so rather than leaving them to discover it later.
     */
    boolean voipFarPartyHeard();

    /**
     * The uid of the app whose VoIP call is in progress, or -1 if it cannot be determined.
     *
     * Read from the audio system — the owner of the `USAGE_VOICE_COMMUNICATION` playback track, which
     * is the very stream being recorded — so it cannot name the wrong app. Only the daemon can ask:
     * playback configurations are anonymised for callers without MODIFY_AUDIO_ROUTING.
     *
     * A uid rather than a package because the daemon holds no Context; the app maps it with
     * PackageManager, which handles shared uids and work profiles correctly.
     */
    int voipCallAppUid();

    /**
     * Best-effort name of the person on the call, from the given package's ongoing notification.
     *
     * Scoped to the package resolved from {@link #voipCallAppUid} so a name can never be paired with
     * the wrong app. Null whenever the app does not publish one.
     */
    String voipCallerName(String packageName);

    /**
     * Creates a capture track and hands it over **stopped**, for the app to start itself.
     *
     * Track A: capture permission is checked when the track is created, not when it is started, so a
     * track created once here may be run per call by the app with no daemon alive. Whether AudioFlinger
     * accepts a start from the app's uid is the open question this exists to answer.
     */
    boolean startHandoffHeld(String source, int sampleRate, int channels);

    /** Releases the daemon's held handoff AudioRecord (frees the capture input). Idempotent. */
    void stopHandoff();
}
