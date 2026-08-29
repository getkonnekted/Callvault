/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import com.baba.callvault.utils.AppLogger

/**
 * Best-effort "who was on the call?" for naming a VoIP recording.
 *
 * Notifications are used here for a reason, and only here: a VoIP call has no number and no call-log
 * entry, the app's own call history sits in a private data directory unreadable even to shell, and
 * Telecom holds nothing because these apps do not register connections (verified on-device — a live
 * Telegram call produced no Telecom entry whatsoever). The ongoing-call notification is the only place
 * the system holds the contact's name. Short of root, that is the boundary.
 *
 * Which *app* is on the call is NOT read from here — see [VoipAppIdentity], which takes it from the
 * audio stream being recorded. This lookup is always scoped to that already-known package, so a name
 * can no longer be paired with the wrong app.
 *
 * Text scraping, so formats vary by app, locale and version: every failure path returns null and the
 * recording is named by time alone. Nothing depends on it.
 */
internal object VoipCallerName {
    /** The shell, by absolute path — see [VoipAppIdentity]. */
    private const val SHELL = "/system/bin/sh"

    private const val TAG = "CV:VoipName"

    private const val DUMP_TIMEOUT_MS = 1_500L
    private const val MAX_NAME_LENGTH = 40

    /** Characters unsafe or annoying in a filename, plus anything non-printable. */
    private val UNSAFE = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

    private val TITLE_REGEX = Regex("""android\.title=String \((.+?)\)""")
    private val TEXT_REGEX = Regex("""android\.text=String \((.+?)\)""")

    /**
     * The name shown on [packageName]'s ongoing-call notification, or null.
     *
     * Blocking (spawns `dumpsys`), so keep it off the critical path — it runs once per call.
     */
    fun resolve(packageName: String): String? = runCatching {
        val dump = readNotificationDump() ?: return null
        extractFromDump(dump, packageName)
    }.onFailure { AppLogger.d(TAG, "Caller lookup failed: ${it.message}") }.getOrNull()

    /**
     * Finds [packageName]'s ongoing notification and reads the contact from it.
     *
     * Two hard-won details. Records are split on `NotificationRecord(` so every field stays with its
     * owner. And the contact is NOT always in the title: WhatsApp titles the notification with the
     * person, while Telegram titles it "Ongoing Telegram call" and puts the person in `android.text`.
     * So both fields are candidates, in that order, and one that merely restates the app is skipped.
     *
     * Matching does not filter on `category=call`: Telegram's call notification sets no category at
     * all. Scoping to the package makes that filter unnecessary anyway — an ongoing notification from
     * the app whose call audio we are recording is the call.
     */
    internal fun extractFromDump(dump: String, packageName: String): String? {
        if (packageName.isBlank()) return null

        for (record in dump.split("NotificationRecord(")) {
            if (!record.contains("pkg=$packageName ")) continue
            // Call notifications are ongoing; this skips the app's chat and message notifications.
            if (!record.contains("ONGOING_EVENT")) continue

            val candidates = listOfNotNull(
                TITLE_REGEX.find(record)?.groupValues?.get(1),
                TEXT_REGEX.find(record)?.groupValues?.get(1),
            )
            val name = candidates.firstNotNullOfOrNull { sanitize(it, packageName) }
            if (name != null) {
                AppLogger.i(TAG, "Caller name resolved for $packageName")   // the name is never logged
                return name
            }
        }
        return null
    }

    /**
     * Trims a candidate to something usable as a filename, or null.
     *
     * Rejects anything that merely restates the app: Telegram's title is "Ongoing Telegram call", a
     * status line rather than a person, and that string in a filename is worse than no name at all.
     */
    private fun sanitize(raw: String, packageName: String): String? {
        val cleaned = UNSAFE.replace(raw, "").trim().trimEnd('.')
        if (cleaned.isEmpty()) return null

        // "org.telegram.messenger" -> "telegram"; a candidate containing it describes the app, not a person.
        val appToken = packageName.split('.')
            .filter { it.length > 3 && it !in GENERIC_PACKAGE_PARTS }
            .maxByOrNull { it.length }
        if (appToken != null && cleaned.contains(appToken, ignoreCase = true)) {
            AppLogger.d(TAG, "Ignoring notification text that just names the app")
            return null
        }
        return if (cleaned.length > MAX_NAME_LENGTH) cleaned.take(MAX_NAME_LENGTH).trim() else cleaned
    }

    private val GENERIC_PACKAGE_PARTS = setOf("com", "org", "net", "android", "messenger", "app", "mobile")

    private fun readNotificationDump(): String? {
        // Absolute path — see VoipAppIdentity. resolve() already returns null on any failure, so a
        // missing shell costs the caller name and nothing else.
        val proc = ProcessBuilder(SHELL, "-c", "dumpsys notification --noredact")
            .redirectErrorStream(true).start()
        return try {
            val text = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(DUMP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) proc.destroy()
            text.takeIf { it.isNotBlank() }
        } finally {
            runCatching { proc.destroy() }
        }
    }
}
