/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import com.baba.callvault.utils.AppLogger
import java.io.File

/**
 * JNI bridge to `libaudiohandoff.so`, the native half of "Resilient recording".
 *
 * Loaded in BOTH processes, by different means (see [ensureLoaded] / [ensureLoadedFromApk]):
 *  • the DAEMON extracts the `IAudioRecord` binder + cblk ashmem fd from a privileged `AudioRecord`;
 *  • the APP mmaps the delivered cblk and drains its ring, which is what lets a recording outlive the
 *    daemon.
 */
object AudioHandoffNative {
    private const val TAG = "CV:HandoffNative"
    private const val LIB_NAME = "audiohandoff"
    private const val SO_NAME = "lib$LIB_NAME.so"

    @Volatile private var loaded = false

    /** Loads the library once via the app classloader (APP process); returns availability. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return runCatching { System.loadLibrary(LIB_NAME); loaded = true; true }.getOrDefault(false)
    }

    /**
     * Loads the library in the DAEMON. app_process has no classloader library-search path, so
     * `System.loadLibrary` cannot find it — it must be loaded by absolute path from the extracted
     * native-lib dir that sits next to the APK ([apkPath] = `applicationInfo.sourceDir`).
     */
    fun ensureLoadedFromApk(apkPath: String): Boolean {
        if (loaded) return true
        val so = findExtractedLib(apkPath)
        if (so == null) {
            AppLogger.e(TAG, "$SO_NAME not found under ${File(apkPath).parentFile}/lib — handoff unavailable")
            return false
        }
        return runCatching { System.load(so.absolutePath); loaded = true; true }
            .onFailure { AppLogger.e(TAG, "System.load($so) failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Locates the extracted `libaudiohandoff.so` next to the APK, by SEARCHING `lib/` rather than
     * assuming the subdirectory's name.
     *
     * That directory is named after the **instruction set** (`arm64`), not the build ABI
     * (`arm64-v8a`) — a difference that once silently disabled the whole feature, because a failed
     * load only makes `startHandoff` return false and the engine then falls back to the daemon
     * recording path, so the call still records and nothing looks broken from outside. Searching
     * costs one directory listing, once per daemon lifetime.
     */
    internal fun findExtractedLib(apkPath: String): File? {
        val libRoot = File(File(apkPath).parentFile, "lib")
        return libRoot.listFiles()
            ?.asSequence()
            ?.map { File(it, SO_NAME) }
            ?.firstOrNull { it.isFile }
    }

    /** True if `ptr` is a native android::AudioRecord (ptr+0x190 -> +0x10 is a BpBinder). */
    external fun nativeValidateArPtr(ptr: Long): Boolean

    /** Extracts the IAudioRecord BpBinder from the native AudioRecord + wraps it as a Java IBinder. */
    external fun nativeExtractBinder(ptr: Long): android.os.IBinder?

    /** Finds + dups the cblk ashmem fd in this process, identified by its frameCount header (-1 if none). */
    external fun nativeFindCblkFd(expectedFrameCount: Int): Int

    /** ioctl ASHMEM_GET_SIZE on `fd` — the app uses this to mmap the cblk at its true (rate-dependent) size. */
    external fun nativeAshmemSize(fd: Int): Int

    /**
     * Drains the cblk ring until STOPPED, streaming ORDERED interleaved PCM-16 to `writeFd` (a pipe
     * write end whose ownership is transferred to native — closed there to signal EOF). Ring geometry:
     * `frameCount` frames of `frameSize` bytes (2 mono / 4 stereo) starting at byte `dataOff`.
     * `guardFrames` holds back the freshest N frames each cycle (avoids torn reads at the write cursor).
     * `stopFlag` is a direct ByteBuffer whose first int the caller flips non-zero to stop; `maxSeconds`
     * is a safety cap in case a stop signal is ever lost.
     */
    external fun nativeDrainToPipe(
        fd: Int, size: Int, frameCount: Int, dataOff: Int, frameSize: Int,
        guardFrames: Int, writeFd: Int, stopFlag: java.nio.ByteBuffer, maxSeconds: Int,
    )
}
