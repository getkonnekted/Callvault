/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers reading the contact's name off an app's ongoing-call notification.
 *
 * Three real on-device failures drive these. A Telegram call was labelled "WhatsApp", then "Google",
 * because the lookup searched ALL notifications for one tagged `category=call` — and Telegram's call
 * notification sets no category at all, so an unrelated app's notification won. And the contact was
 * read only from `android.title`, which for Telegram holds the status line "Ongoing Telegram call"
 * while the person's name sits in `android.text`.
 *
 * The overriding rule is the failure mode: anything unexpected yields null so the recording is named
 * by time alone, never with a wrong name.
 */
class VoipCallerNameTest {

    /** Shaped after a real `dumpsys notification --noredact` record. */
    private fun record(
        pkg: String,
        title: String,
        text: String = "tap to return to the call",
        ongoing: Boolean = true,
        category: String? = null,
    ) = """
        NotificationRecord(0x01: pkg=$pkg user=UserHandle{0} id=201 tag=null
          Notification(channel=Other flags=${if (ongoing) "ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE" else "0"})
          ${category?.let { "category=$it" } ?: ""}
          extras={
                android.title=String ($title)
                android.text=String ($text)
          }
    """.trimIndent()

    @Test
    fun `reads the caller from the title when the app puts it there`() {
        // WhatsApp's shape: the person is the title.
        val name = VoipCallerName.extractFromDump(record("com.whatsapp", "Feroza"), "com.whatsapp")
        assertEquals("Feroza", name)
    }

    @Test
    fun `falls back to the text when the title only restates the app`() {
        // Telegram's shape, verified on-device: title is a status line, android.text is the person.
        val dump = record("org.telegram.messenger", "Ongoing Telegram call", text = "Feroza")
        assertEquals("Feroza", VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `finds a call notification that carries no category at all`() {
        // The regression: Telegram sets no category, so a category=call filter never matched it.
        val dump = record("org.telegram.messenger", "Ongoing Telegram call", text = "Alex")
        assertEquals("Alex", VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `ignores other apps' notifications entirely`() {
        // The regression that produced "_voip_Google.ogg": another app's notification won the race.
        val dump = record("com.google.android.googlequicksearchbox", "Weather", category = "call") +
            "\n" + record("org.telegram.messenger", "Ongoing Telegram call", text = "Alex")

        assertEquals("Alex", VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
        assertNull(VoipCallerName.extractFromDump(dump, "com.whatsapp"))
    }

    @Test
    fun `ignores the app's own non-ongoing notifications`() {
        // A chat message from the same app must not be mistaken for the call.
        val dump = record("org.telegram.messenger", "Bob", text = "see you at 5", ongoing = false)
        assertNull(VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `returns null when neither field names a person`() {
        val dump = record("org.telegram.messenger", "Ongoing Telegram call", text = "Telegram")
        assertNull(VoipCallerName.extractFromDump(dump, "org.telegram.messenger"))
    }

    @Test
    fun `strips characters that would break a filename`() {
        assertEquals("AaBb", VoipCallerName.extractFromDump(record("com.whatsapp", "A/a:B*b"), "com.whatsapp"))
    }

    @Test
    fun `keeps non-latin names intact`() {
        assertEquals("גבריאל", VoipCallerName.extractFromDump(record("com.whatsapp", "גבריאל"), "com.whatsapp"))
    }

    @Test
    fun `truncates an absurdly long name`() {
        val name = VoipCallerName.extractFromDump(record("com.whatsapp", "N".repeat(200)), "com.whatsapp")
        assertEquals(40, name?.length)
    }

    @Test
    fun `falls through to the text when the title is only unusable characters`() {
        assertEquals("Dana", VoipCallerName.extractFromDump(record("com.whatsapp", "///", text = "Dana"), "com.whatsapp"))
    }

    @Test
    fun `returns null for empty or junk input rather than throwing`() {
        assertNull(VoipCallerName.extractFromDump("", "com.whatsapp"))
        assertNull(VoipCallerName.extractFromDump("not a dump at all", "com.whatsapp"))
    }

    @Test
    fun `returns null when the package is unknown`() {
        assertNull(VoipCallerName.extractFromDump(record("com.whatsapp", "Feroza"), ""))
    }
}
