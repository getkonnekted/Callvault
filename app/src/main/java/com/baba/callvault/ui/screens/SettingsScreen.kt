/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.screens

import android.provider.Settings
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.baba.callvault.services.recording.DaemonKeepAliveService
import com.baba.callvault.services.recording.VoipCaptureController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.baba.callvault.R
import com.baba.callvault.system.PersistentFolderPickerContract
import com.baba.callvault.system.copyToClipboard
import com.baba.callvault.system.openOriginalProjectRepo
import com.baba.callvault.system.diagnostics.SystemLogCollector
import com.baba.callvault.system.shareLogFiles
import com.baba.callvault.system.openKofi
import com.baba.callvault.ui.common.formatByteSize
import com.baba.callvault.ui.common.SupportDialog
import com.baba.callvault.system.shareLogFile
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.integrations.adb.AdbShell
import com.baba.callvault.integrations.adb.UsbDefaultConfig
import com.baba.callvault.integrations.adb.UsbDefaultMode
import com.baba.callvault.data.RetentionPeriod
import com.baba.callvault.integrations.scrcpy.AUDIO_BIT_RATE_OPTIONS
import com.baba.callvault.data.SyncScheduleMode
import com.baba.callvault.ui.common.SyncScheduleLabels
import com.baba.callvault.data.StorageTarget
import com.baba.callvault.integrations.scrcpy.RECOMMENDED_AUDIO_BIT_RATE
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.integrations.scrcpy.ScrcpyConfig
import com.baba.callvault.system.storage.SafHelper
import com.baba.callvault.system.takePersistableFolderPermission
import androidx.lifecycle.viewmodel.compose.viewModel
import com.baba.callvault.ui.common.ContactSelectionDialog
import com.baba.callvault.ui.common.CvCard
import com.baba.callvault.ui.common.CvScaffold
import com.baba.callvault.ui.common.CvSecondaryButton
import com.baba.callvault.ui.common.CvSectionHeader
import com.baba.callvault.ui.common.FileNameFormatDialog
import com.baba.callvault.ui.common.M3DropdownField
import com.baba.callvault.ui.common.OptionItem
import com.baba.callvault.ui.viewmodels.ContactPickerType
import com.baba.callvault.ui.viewmodels.ContactPickerViewModel
import com.baba.callvault.ui.viewmodels.SettingsActions
import com.baba.callvault.ui.viewmodels.SettingsViewModel
import com.baba.callvault.ui.viewmodels.ContactPickerState
import com.baba.callvault.utils.fileNameTemplateExample
import com.baba.callvault.utils.presetForTemplateOrFirst
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WifiOff
import com.baba.callvault.ui.common.OfflineDialogMode
import com.baba.callvault.ui.common.OfflineRecordingDialog
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalResources
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Stateful wrapper for the Settings screen that connects [SettingsViewModel] to [SettingsContent].
 *
 * @param viewModel Handles saving whenever the user changes a setting.
 * @param onBack    Called when the user taps the top-bar back affordance; the router maps this to
 *                  [com.baba.callvault.ui.viewmodels.AppNavViewModel.navigateBack].
 * @param modifier  Optional modifier for the root scaffold.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Trigger recomposition when settings change by viewmodel.refresh()
    val updateTrigger by viewModel.updateTrigger.collectAsState()

    // ContactPickerViewModel owns the contact-loading logic and dialog state.
    val contactPickerViewModel: ContactPickerViewModel = viewModel()
    val contactPickerState by contactPickerViewModel.contactPickerState.collectAsState()

    // Folder picker — PersistentFolderPickerContract keeps access alive after a reboot.
    val folderPickerLauncher = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            if (SafHelper.isCloudFolder(uri)) {
                // Cloud folders (Google Drive, …) reject "rw" and report length asynchronously, which
                // breaks live capture. Refuse it here; the Drive backup option handles cloud copies.
                Toast.makeText(context, context.getString(R.string.folder_cloud_rejected), Toast.LENGTH_LONG).show()
            } else {
                context.takePersistableFolderPermission(uri)
                viewModel.preferences.setRecordingFolderUri(uri)
            }
        }
        viewModel.refresh()
    }

    // Drive folder picker — same contract; persists READ + WRITE access across reboots.
    val driveFolderPickerLauncher = rememberLauncherForActivityResult(PersistentFolderPickerContract()) { uri ->
        if (uri != null) {
            context.takePersistableFolderPermission(uri)
            viewModel.setDriveFolderUri(uri)
        }
        viewModel.refresh()
    }

    SettingsContent(
        preferences = viewModel.preferences,
        updateTrigger = updateTrigger,
        actions = viewModel,
        contactPickerState = contactPickerState,
        onBack = onBack,
        // Seed each picker with its OWN current folder so it opens there, instead of letting
        // Android's DocumentsUI reopen at the last-browsed location (which, after setting Drive,
        // made re-picking the local folder open at the Drive path).
        onSelectFolder = { folderPickerLauncher.launch(viewModel.preferences.getRecordingFolderUri()) },
        onSelectDriveFolder = { driveFolderPickerLauncher.launch(viewModel.preferences.getDriveFolderUri()) },
        onOpenContactsIncoming = { contactPickerViewModel.openContactPicker(ContactPickerType.INCOMING) },
        onOpenContactsOutgoing = { contactPickerViewModel.openContactPicker(ContactPickerType.OUTGOING) },
        onConfirmContacts = { numbers ->
            contactPickerViewModel.confirmContactPicker(numbers)
            // Refresh the screen so the new contact list information is shown immediately after confirming and closing the dialog.
            viewModel.refresh()
        },
        onDismissContacts = { contactPickerViewModel.dismissContactPicker() },
        // Build the report off the main thread, then hand it to the system share-sheet. The Share
        // entry point is only shown when a valid log file exists, so the null branch is a safety net.
        onShareLogs = {
            scope.launch {
                val report = withContext(Dispatchers.IO) { AppLogger.buildShareableReport(context) }
                // The system slice carries the daemon's lines and the platform's — the half no bug
                // report has ever contained. Null when logcat gave nothing usable, in which case the
                // app's own report still goes on its own.
                val systemReport = SystemLogCollector.buildReport(context)
                if (report != null) {
                    context.shareLogFiles(listOfNotNull(report, systemReport))
                } else {
                    Toast.makeText(context, R.string.settings_bugreport_share_empty, Toast.LENGTH_LONG).show()
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Stateless visual layer for the Settings screen, redesigned on the "Signal" design system.
 *
 * Each section is a [CvSectionHeader] followed by a [CvCard] grouping its rows. Every existing
 * setting, action, dialog, and the hidden developer-unlock gesture is preserved; only the layout
 * is restyled.
 *
 * @param preferences            The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force/detect recomposition when settings change.
 * @param actions                Implementation of [SettingsActions] to handle user interaction.
 * @param contactPickerState     Current state of the contact picker dialog.
 * @param onBack                 Called when the user taps the top-bar back affordance.
 * @param onSelectFolder         Called when the user taps the recording-folder row.
 * @param onSelectDriveFolder    Called when the user taps the Drive-folder row; opens the SAF picker.
 * @param onOpenContactsIncoming Called to open picker for incoming contacts.
 * @param onOpenContactsOutgoing Called to open picker for outgoing contacts.
 * @param onConfirmContacts      Called when contacts are confirmed from the dialog.
 * @param onDismissContacts      Called when we want to close the dialog without confirmation/saving.
 * @param onShareLogs            Called to share diagnostic logs via the system share-sheet (Debug section).
 * @param modifier               Optional size/position modifier.
 */
@Composable
fun SettingsContent(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    contactPickerState: ContactPickerState?,
    onBack: () -> Unit,
    onSelectFolder: () -> Unit,
    onSelectDriveFolder: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit,
    onConfirmContacts: (Set<String>) -> Unit,
    onDismissContacts: () -> Unit,
    onShareLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showLicensesDialog by remember { mutableStateOf(false) }

    // Accordion: at most one section open at a time; Recording & storage is open on entry. State is
    // hoisted here (above the LazyColumn) so it is shared across all sections. Tapping the open section
    // closes it (null = none open); tapping any other section opens it and closes the previous one.
    // Everything closed on entry: the panel then opens on a short list of section names, which is
    // navigable at a glance. Auto-opening one buried the other five under a screenful of its rows.
    var openSection by rememberSaveable { mutableStateOf<String?>(null) }
    val onToggleSection: (String) -> Unit = { id -> openSection = if (openSection == id) null else id }

    CvScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.general_settings),
        subtitle = stringResource(R.string.settings_ui_subtitle),
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                RecordingSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    expanded = openSection == SECTION_RECORDING,
                    onToggle = { onToggleSection(SECTION_RECORDING) },
                    onOpenContactsIncoming = onOpenContactsIncoming,
                    onOpenContactsOutgoing = onOpenContactsOutgoing
                )
            }
            item {
                StorageSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    // SECTION_RETENTION also opens Storage: retention used to be its own accordion, and a
                    // user whose saved open-section is the old key should land on the section that now
                    // contains it rather than on a collapsed screen.
                    expanded = openSection == SECTION_STORAGE || openSection == SECTION_RETENTION,
                    onToggle = { onToggleSection(SECTION_STORAGE) },
                    onSelectFolder = onSelectFolder,
                    onSelectDriveFolder = onSelectDriveFolder
                )
            }
            item {
                AudioSection(
                    preferences, updateTrigger, actions,
                    expanded = openSection == SECTION_AUDIO,
                    onToggle = { onToggleSection(SECTION_AUDIO) }
                )
            }
            item {
                GeneralSection(
                    preferences = preferences,
                    updateTrigger = updateTrigger,
                    actions = actions,
                    // Old keys still open General, so a user whose saved section was Visual,
                    // Experimental or Updates lands on the section that now contains it.
                    expanded = openSection in setOf(SECTION_GENERAL, SECTION_VISUAL, SECTION_EXPERIMENTAL, SECTION_UPDATES),
                    onToggle = { onToggleSection(SECTION_GENERAL) }
                )
            }
            // Debug section: always visible so anyone can enable logging and share logs to report an issue.
            item {
                BugReportSection(
                    preferences, updateTrigger, actions, onShareLogs,
                    expanded = openSection == SECTION_BUG_REPORT,
                    onToggle = { onToggleSection(SECTION_BUG_REPORT) }
                )
            }
            // About moved to the bottom; the fork attribution stays visible (GPLv3 §7 requirement).
            item {
                AboutSection(
                    versionString = actions.getAppVersion(),
                    onShowLicenses = { showLicensesDialog = true },
                    expanded = openSection == SECTION_ABOUT,
                    onToggle = { onToggleSection(SECTION_ABOUT) }
                )
            }
        }
    }

    if (showLicensesDialog) {
        Dialog(
            onDismissRequest = { showLicensesDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.general_licenses),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp)
                    )

                    val libraries by produceLibraries(R.raw.aboutlibraries)
                    LibrariesContainer(libraries,Modifier
                        .fillMaxSize()
                        .weight(1f),
                        showAuthor = true, showLicenseBadges = true, showFundingBadges = false, showVersion = true, showDescription = true)
                    TextButton(
                        onClick = { showLicensesDialog = false },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(8.dp)
                    ) {
                        Text(stringResource(R.string.general_close))
                    }
                }
            }
        }
    }

    // The contact-picker dialog sits on top of the settings content.
    contactPickerState?.let { picker ->
        ContactSelectionDialog(
            title = when (picker.type) {
                ContactPickerType.INCOMING -> stringResource(R.string.settings_select_contacts_incoming)
                ContactPickerType.OUTGOING -> stringResource(R.string.settings_select_contacts_outgoing)
            },
            contacts = picker.contacts,
            initialSelection = picker.selectedNumbers,
            onConfirm = onConfirmContacts,
            onDismiss = onDismissContacts
        )
    }
}

// Settings accordion: stable keys identifying each section. At most one section is open at a time;
// [SECTION_RECORDING] is the one open when Settings is first entered.
private const val SECTION_RECORDING = "recording"
private const val SECTION_STORAGE = "storage"
private const val SECTION_RETENTION = "retention"
private const val SECTION_AUDIO = "audio"
private const val SECTION_GENERAL = "general"

// Sub-section keys. Scoped to their parent section, so the same value can never collide across two.
private const val SUB_FILE_NAME = "sub_file_name"
private const val SUB_PHONE_CALLS = "sub_phone_calls"
private const val SUB_INCOMING = "sub_incoming"
private const val SUB_OUTGOING = "sub_outgoing"
private const val SUB_WHERE = "sub_where"
private const val SUB_UPLOAD = "sub_upload"
private const val SUB_RETENTION = "sub_retention"
private const val SUB_VISUAL = "sub_visual"
private const val SUB_EXPERIMENTAL = "sub_experimental"
private const val SUB_UPDATES = "sub_updates"
private const val SECTION_VISUAL = "visual"
private const val SECTION_EXPERIMENTAL = "reliability"   // key kept so a user's open-section state survives the rename
private const val SECTION_BUG_REPORT = "bug_report"
private const val SECTION_UPDATES = "updates"
private const val SECTION_ABOUT = "about"

// ── Settings sections ──────────────────────────────────────────────────────────────────────

/** Recording behaviour: filename template, plus auto-record incoming/outgoing with their
 * per-direction ignore filters. (Where files are saved lives in [StorageSection].)
 *
 * @param preferences            The [AppPreferences] instance to read data from.
 * @param updateTrigger          Trigger value to force recomposition when settings change.
 * @param actions                Implementation of [SettingsActions] to handle user interaction.
 * @param expanded               Whether this accordion section is open.
 * @param onToggle               Invoked when the section header is tapped.
 * @param onOpenContactsIncoming Called when the user wants to pick incoming contacts to ignore.
 * @param onOpenContactsOutgoing Called when the user wants to pick outgoing contacts to ignore.
 */
@Composable
private fun RecordingSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenContactsIncoming: () -> Unit,
    onOpenContactsOutgoing: () -> Unit
) {
    // Evaluate these here so they are fetched on every recomposition.
    val fileNameFormat = remember(updateTrigger) { preferences.getFileNameTemplate() }
    val carrierRecording = remember(updateTrigger) { preferences.isCarrierRecordingEnabled() }
    val autoRecordIncoming = remember(updateTrigger) { preferences.isAutoRecordIncomingEnabled() }
    val autoRecordOutgoing = remember(updateTrigger) { preferences.isAutoRecordOutgoingEnabled() }
    val ignoreAnonymousIncoming = remember(updateTrigger) { preferences.isIgnoreAnonymousIncomingEnabled() }
    val ignoreCrossCountryIncoming = remember(updateTrigger) { preferences.isIgnoreCrossCountryIncomingEnabled() }
    val ignoreContactsModeIncoming = remember(updateTrigger) { preferences.getIgnoreContactsModeIncoming() }
    val ignoreContactsModeOutgoing = remember(updateTrigger) { preferences.getIgnoreContactsModeOutgoing() }
    val ignoreCrossCountryOutgoing = remember(updateTrigger) { preferences.isIgnoreCrossCountryOutgoingEnabled() }
    val ignoredContactsIncomingCount = remember(updateTrigger) { preferences.getIgnoredContactsIncoming().size }
    val ignoredContactsOutgoingCount = remember(updateTrigger) { preferences.getIgnoredContactsOutgoing().size }

    var showFileNameFormatDialog by remember { mutableStateOf(false) }

    // One open sub-section at a time, within this section only, and nothing open on entry.
    var openSub by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsSection(
        title = stringResource(R.string.settings_section_recording),
        expanded = expanded,
        onToggle = onToggle,
        wrapInCard = false,
    ) {
      SettingsSubSection(
        title = stringResource(R.string.settings_file_name_template),
        expanded = openSub == SUB_FILE_NAME,
        onToggle = { openSub = if (openSub == SUB_FILE_NAME) null else SUB_FILE_NAME },
      ) {
        NavigationRow(
            icon = Icons.Filled.DriveFileRenameOutline,
            label = stringResource(R.string.settings_file_name_template),
            value = stringResource(presetForTemplateOrFirst(fileNameFormat).labelRes),
            supporting = stringResource(
                R.string.settings_file_name_template_example,
                fileNameTemplateExample(fileNameFormat)
            ),
            onClick = { showFileNameFormatDialog = true }
        )
      }

      // The master switch for phone calls, in its own sub-section above the per-direction ones it
      // governs. Turning the two direction switches off is NOT the same thing: that is "ask me", and
      // it still puts a Record prompt on every call. This is the "app calls only" mode.
      SettingsSubSection(
        title = stringResource(R.string.settings_subsection_phone_calls),
        expanded = openSub == SUB_PHONE_CALLS,
        onToggle = { openSub = if (openSub == SUB_PHONE_CALLS) null else SUB_PHONE_CALLS },
      ) {
        SettingsToggleRow(
            icon = Icons.Filled.Smartphone,
            label = stringResource(R.string.settings_carrier_recording),
            description = stringResource(R.string.settings_carrier_recording_description),
            checked = carrierRecording,
            onCheckedChange = { actions.setCarrierRecording(it) }
        )
      }

      AnimatedVisibility(
          visible = carrierRecording,
          enter   = fadeIn() +  expandVertically(),
          exit    = fadeOut() + shrinkVertically()
      ) {
        Column {
      SettingsSubSection(
        title = stringResource(R.string.settings_subsection_incoming),
        expanded = openSub == SUB_INCOMING,
        onToggle = { openSub = if (openSub == SUB_INCOMING) null else SUB_INCOMING },
      ) {
        SettingsToggleRow(
            icon = Icons.AutoMirrored.Filled.CallReceived,
            label = stringResource(R.string.settings_auto_record_incoming),
            checked = autoRecordIncoming,
            onCheckedChange = { actions.setAutoRecordIncoming(it) }
        )
        AnimatedVisibility(
            visible = autoRecordIncoming,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            NestedGroup {
                SettingsToggleRow(
                    label           = stringResource(R.string.settings_ignore_anonymous_incoming),
                    checked         = ignoreAnonymousIncoming,
                    onCheckedChange = { actions.setIgnoreAnonymousIncoming(it) }
                )
                SettingsToggleRow(
                    label           = stringResource(R.string.settings_ignore_cross_country_incoming),
                    checked         = ignoreCrossCountryIncoming,
                    onCheckedChange = { actions.setIgnoreCrossCountryIncoming(it) },
                    enabled         = ignoreAnonymousIncoming
                )
                IgnoreContactsOptions(
                    label           = stringResource(R.string.settings_ignore_contacts_incoming),
                    selectedEnum     = ignoreContactsModeIncoming,
                    selectedCount    = ignoredContactsIncomingCount,
                    onSelected      = { actions.setIgnoreContactsModeIncoming(it) },
                    onSelectContacts = onOpenContactsIncoming
                )
            }
        }
      }

      SettingsSubSection(
        title = stringResource(R.string.settings_subsection_outgoing),
        expanded = openSub == SUB_OUTGOING,
        onToggle = { openSub = if (openSub == SUB_OUTGOING) null else SUB_OUTGOING },
      ) {
        SettingsToggleRow(
            icon = Icons.AutoMirrored.Filled.CallMade,
            label = stringResource(R.string.settings_auto_record_outgoing),
            checked = autoRecordOutgoing,
            onCheckedChange = { actions.setAutoRecordOutgoing(it) }
        )
        AnimatedVisibility(
            visible = autoRecordOutgoing,
            enter   = fadeIn() +  expandVertically(),
            exit    = fadeOut() + shrinkVertically()
        ) {
            NestedGroup {
                SettingsToggleRow(
                    label           = stringResource(R.string.settings_ignore_cross_country_outgoing),
                    checked         = ignoreCrossCountryOutgoing,
                    onCheckedChange = { actions.setIgnoreCrossCountryOutgoing(it) }
                )
                IgnoreContactsOptions(
                    label           = stringResource(R.string.settings_ignore_contacts_outgoing),
                    selectedEnum     = ignoreContactsModeOutgoing,
                    selectedCount    = ignoredContactsOutgoingCount,
                    onSelected      = { actions.setIgnoreContactsModeOutgoing(it) },
                    onSelectContacts = onOpenContactsOutgoing
                )
            }
        }
      }
        }
      }
    }

    if (showFileNameFormatDialog) {
        FileNameFormatDialog(
            initialFormat = fileNameFormat,
            onConfirm = { format ->
                actions.setFileNameTemplate(format)
                showFileNameFormatDialog = false
            },
            onDismiss = { showFileNameFormatDialog = false }
        )
    }
}

/** Storage destinations: where recordings are saved — the storage target (device / Drive / both),
 * the on-device folder, and the Drive folder. (Recording behaviour lives in [RecordingSection].)
 *
 * @param preferences         The [AppPreferences] instance to read data from.
 * @param updateTrigger       Trigger value to force recomposition when settings change.
 * @param actions             Implementation of [SettingsActions] to handle user interaction.
 * @param expanded            Whether this accordion section is open.
 * @param onToggle            Invoked when the section header is tapped.
 * @param onSelectFolder      Called when the user taps the recording-folder row; opens the SAF picker.
 * @param onSelectDriveFolder Called when the user taps the Drive-folder row; opens the SAF picker.
 */
@Composable
private fun StorageSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectFolder: () -> Unit,
    onSelectDriveFolder: () -> Unit
) {
    val context = LocalContext.current

    val recordingFolderLabel = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getRecordingFolderUri()) }
    val recordingFolderIsCloud = remember(updateTrigger) { SafHelper.isCloudFolder(preferences.getRecordingFolderUri()) }
    val storageTarget = remember(updateTrigger) { preferences.getStorageTarget() }
    val driveFolderLabel = remember(updateTrigger) { SafHelper.getFolderDisplayNameOrNull(context, preferences.getDriveFolderUri()) }

    // One open sub-section at a time, within this section only, and nothing open on entry.
    var openSub by rememberSaveable { mutableStateOf<String?>(null) }

    val storageTargetOptions = StorageTarget.entries.map { target ->
        val labelRes = when (target) {
            StorageTarget.LOCAL -> R.string.storage_target_local
            StorageTarget.DRIVE -> R.string.storage_target_drive
            StorageTarget.BOTH  -> R.string.storage_target_both
        }
        OptionItem(target.key, stringResource(labelRes))
    }

    SettingsSection(
        title = stringResource(R.string.settings_section_storage),
        expanded = expanded,
        onToggle = onToggle,
        wrapInCard = false,
    ) {
      SettingsSubSection(
        title = stringResource(R.string.settings_storage_target_label),
        expanded = openSub == SUB_WHERE,
        onToggle = { openSub = if (openSub == SUB_WHERE) null else SUB_WHERE },
      ) {
        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_storage_target_label),
                selected = storageTargetOptions.find { it.key == storageTarget.key } ?: storageTargetOptions.first(),
                options  = storageTargetOptions,
                onOptionSelected = { actions.setStorageTarget(StorageTarget.fromKey(it.key)) }
            )
        }

        SettingsDivider()

        NavigationRow(
            icon = Icons.Filled.Folder,
            label = stringResource(R.string.settings_recording_folder_label),
            value = recordingFolderLabel ?: stringResource(R.string.settings_tap_to_select_folder),
            // Surface a cloud folder (e.g. Google Drive) that was set before we started rejecting them —
            // otherwise it's indistinguishable from a local folder by name alone.
            supporting = if (recordingFolderIsCloud) stringResource(R.string.folder_cloud_warning) else null,
            onClick = onSelectFolder
        )

        NavigationRow(
            icon = Icons.Filled.Cloud,
            label = stringResource(R.string.settings_drive_folder_label),
            value = driveFolderLabel ?: stringResource(R.string.general_not_set),
            supporting = stringResource(R.string.settings_drive_folder_desc),
            onClick = onSelectDriveFolder
        )

      }

        // Only meaningful when something is actually uploaded — with LOCAL there is no upload to schedule.
        if (storageTarget != StorageTarget.LOCAL) {
            SettingsSubSection(
                title = stringResource(R.string.wizard_schedule_title),
                expanded = openSub == SUB_UPLOAD,
                onToggle = { openSub = if (openSub == SUB_UPLOAD) null else SUB_UPLOAD },
            ) { UploadScheduleSubSection(preferences, updateTrigger, actions) }
        }

        SettingsSubSection(
            title = stringResource(R.string.settings_section_retention),
            expanded = openSub == SUB_RETENTION,
            onToggle = { openSub = if (openSub == SUB_RETENTION) null else SUB_RETENTION },
        ) { RetentionSubSection(preferences, updateTrigger, actions) }
    }
}

/**
 * When finished recordings are uploaded to the Drive folder.
 *
 * The choice already drove `StorageRouter.route` and [SyncScheduler] from the day it shipped, but the
 * picker existed ONLY in the setup wizard — and the wizard cannot be re-run, so whatever was chosen
 * during onboarding (defaulting to "immediately") was permanent. Issue #20 is a user who went looking
 * for it and reasonably concluded it did not exist.
 *
 * Deferring batches the Drive app's "upload finished" notifications into one run per day or week
 * instead of one per call, which is what that reporter actually wanted; it does not silence them.
 */
@Composable
private fun UploadScheduleSubSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions
) {
    val mode = remember(updateTrigger) { preferences.getSyncScheduleMode() }
    val hour = remember(updateTrigger) { preferences.getSyncTimeHour() }
    val minute = remember(updateTrigger) { preferences.getSyncTimeMinute() }
    val dayOfWeek = remember(updateTrigger) { preferences.getSyncDayOfWeek() }

    val modeOptions = SyncScheduleMode.entries.map {
        OptionItem(it.key, stringResource(SyncScheduleLabels.titleOf(it)))
    }

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.wizard_schedule_mode_label),
            selected = modeOptions.find { it.key == mode.key } ?: modeOptions.first(),
            options = modeOptions,
            onOptionSelected = { actions.setSyncScheduleMode(SyncScheduleMode.fromKey(it.key)) }
        )
    }

    if (mode == SyncScheduleMode.WEEKLY) {
        val dayOptions = SyncScheduleLabels.DAY_OF_WEEK_OPTIONS.map { day ->
            OptionItem(day.toString(), stringResource(SyncScheduleLabels.dayOfWeekOf(day)))
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_day_label),
                selected = dayOptions.find { it.key == dayOfWeek.toString() } ?: dayOptions.first(),
                options = dayOptions,
                onOptionSelected = { actions.setSyncDayOfWeek(it.key.toIntOrNull() ?: 2) }
            )
        }
    }

    if (mode != SyncScheduleMode.IMMEDIATE) {
        val hourOptions = (0..23).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
        val minuteOptions = SyncScheduleLabels.MINUTE_OPTIONS.map {
            OptionItem(it.toString(), it.toString().padStart(2, '0'))
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_hour_label),
                selected = hourOptions.find { it.key == hour.toString() } ?: hourOptions.first(),
                options = hourOptions,
                onOptionSelected = { actions.setSyncTimeHour(it.key.toIntOrNull() ?: 2) }
            )
        }
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.wizard_schedule_minute_label),
                selected = minuteOptions.find { it.key == minute.toString() } ?: minuteOptions.first(),
                options = minuteOptions,
                onOptionSelected = { actions.setSyncTimeMinute(it.key.toIntOrNull() ?: 0) }
            )
        }
    }

    Text(
        text = stringResource(
            if (mode == SyncScheduleMode.IMMEDIATE) R.string.wizard_schedule_immediate_note
            else R.string.wizard_schedule_subtitle
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Retention: auto-delete recordings older than a chosen period. One shared period for device & Drive,
 * or a separate period for each. Destructive, so it defaults to "Keep forever" and a confirmation is
 * shown the first time a non-forever period is chosen.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 * @param expanded      Whether this accordion section is open.
 * @param onToggle      Invoked when the section header is tapped.
 */
@Composable
private fun RetentionSubSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions
) {
    val linked = remember(updateTrigger) { preferences.isRetentionLinked() }
    val localDays = remember(updateTrigger) { preferences.getRetentionLocalDays() }
    val driveDays = remember(updateTrigger) { preferences.getRetentionDriveDays() }

    val options = RetentionPeriod.entries.map { OptionItem(it.days.toString(), stringResource(it.labelRes)) }
    fun optionFor(days: Int) =
        options.find { it.key == RetentionPeriod.fromDays(days).days.toString() } ?: options.first()

    // Enabling retention from OFF is destructive, so stash the apply-action and confirm first.
    var pendingConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun applyOrConfirm(wasOff: Boolean, newDays: Int, apply: () -> Unit) {
        if (wasOff && newDays > 0) pendingConfirm = apply else apply()
    }

    Column {
        SettingsToggleRow(
            label = stringResource(R.string.retention_linked_label),
            checked = linked,
            onCheckedChange = { nowLinked ->
                actions.setRetentionLinked(nowLinked)
                // When linking, unify the Drive period to the device one so they match.
                if (nowLinked) actions.setRetentionDriveDays(localDays)
            }
        )

        SettingsDivider()

        if (linked) {
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.retention_period_label),
                    selected = optionFor(localDays),
                    options = options,
                    onOptionSelected = { opt ->
                        val days = opt.key.toIntOrNull() ?: 0
                        applyOrConfirm(wasOff = localDays == 0 && driveDays == 0, newDays = days) {
                            actions.setRetentionLocalDays(days)
                            actions.setRetentionDriveDays(days)
                        }
                    }
                )
            }
        } else {
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.retention_local_label),
                    selected = optionFor(localDays),
                    options = options,
                    onOptionSelected = { opt ->
                        val days = opt.key.toIntOrNull() ?: 0
                        applyOrConfirm(wasOff = localDays == 0, newDays = days) { actions.setRetentionLocalDays(days) }
                    }
                )
            }
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.retention_drive_label),
                    selected = optionFor(driveDays),
                    options = options,
                    onOptionSelected = { opt ->
                        val days = opt.key.toIntOrNull() ?: 0
                        applyOrConfirm(wasOff = driveDays == 0, newDays = days) { actions.setRetentionDriveDays(days) }
                    }
                )
            }
        }

        // Sweep time — only relevant once retention is enabled. Two dropdowns (Hour/Minute) in the
        // device's LOCAL time zone, mirroring the sync-schedule picker.
        if (localDays > 0 || driveDays > 0) {
            SettingsDivider()
            val hour = remember(updateTrigger) { preferences.getRetentionTimeHour() }
            val minute = remember(updateTrigger) { preferences.getRetentionTimeMinute() }
            val hourOptions = (0..23).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
            val minuteOptions = listOf(0, 15, 30, 45).map { OptionItem(it.toString(), it.toString().padStart(2, '0')) }
            Text(
                text = stringResource(R.string.retention_time_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp)
            )
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.wizard_schedule_hour_label),
                    selected = hourOptions.find { it.key == hour.toString() } ?: hourOptions.first(),
                    options = hourOptions,
                    onOptionSelected = { actions.setRetentionTimeHour(it.key.toIntOrNull() ?: 0) }
                )
            }
            DropdownRow {
                M3DropdownField(
                    label = stringResource(R.string.wizard_schedule_minute_label),
                    selected = minuteOptions.find { it.key == minute.toString() } ?: minuteOptions.first(),
                    options = minuteOptions,
                    onOptionSelected = { actions.setRetentionTimeMinute(it.key.toIntOrNull() ?: 0) }
                )
            }
        }

        Text(
            text = stringResource(R.string.retention_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }

    pendingConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text(stringResource(R.string.retention_confirm_title)) },
            text = { Text(stringResource(R.string.retention_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { confirm(); pendingConfirm = null }) {
                    Text(stringResource(R.string.retention_confirm_enable))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) {
                    Text(stringResource(R.string.general_cancel))
                }
            }
        )
    }
}

/** Shows the audio source, codec, and bit-rate dropdowns.
 *
 * The audio-source list is generated from [ScrcpyAudioSource.entries] with debug-only entries
 * ([ScrcpyAudioSource.isDebugOnly]) always hidden. Items whose
 * [ScrcpyAudioSource.minApi]/[ScrcpyAudioSource.maxApi] range does not include the current
 * device's API level are shown grayed out and cannot be selected.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 */
@Composable
private fun AudioSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions, expanded: Boolean, onToggle: () -> Unit) {

    val audioSource = remember(updateTrigger) { preferences.getAudioSource() }
    val audioCodec = remember(updateTrigger) { preferences.getAudioCodec() }
    val savedBitRate = remember(updateTrigger) { preferences.getAudioBitRate() }

    SettingsSection(title = stringResource(R.string.settings_section_audio), expanded = expanded, onToggle = onToggle) {
        val currentSdk = Build.VERSION.SDK_INT

        // Build the source list from the enum, always hiding debug-only entries.
        // Items that require an API level not available on this device are shown as disabled.
        val audioSourceOptions = ScrcpyAudioSource.entries
            .filter { !it.isDebugOnly }
            .map { source ->
                OptionItem(
                    key         = source.cliKey,
                    label       = stringResource(source.titleResId),
                    description = stringResource(source.descriptionResId),
                    // Enabled only when the current SDK is within the source's API range.
                    enabled     = currentSdk >= source.minApi &&
                                  (source.maxApi == null || currentSdk <= source.maxApi)
                )
            }

        val selectedAudio = audioSourceOptions.find { it.key == audioSource }
            ?: audioSourceOptions.first()

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_audio_source),
                selected = selectedAudio,
                options  = audioSourceOptions,
                onOptionSelected = { actions.setAudioSource(it.key) }
            )
            // Show the description of the currently selected audio source below the dropdown.
            selectedAudio.description?.let { desc ->
                HintText(desc)
            }
        }

        val codecOptions = ScrcpyAudioCodec.entries
            .map { OptionItem(it.cliKey, stringResource(it.titleResId)) }

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_audio_codec),
                selected = codecOptions.find { it.key == audioCodec }
                    ?: codecOptions.first(),
                options  = codecOptions,
                onOptionSelected = { actions.setAudioCodec(it.key) },
            )
            // Show the AAC recommendation if the user has issues.
            // LocalInspectionMode.current is true in Android Preview, it prevents a preview compilation error.
            if (!LocalInspectionMode.current && audioCodec != ScrcpyAudioCodec.AAC.cliKey) {
                HintText(stringResource(R.string.settings_audio_bitrate_recommendation))
            }
        }

        val recommendedLabel = stringResource(R.string.general_recommended)
        val bitrateOptions = AUDIO_BIT_RATE_OPTIONS
            .map { bps ->
                val kbpsLabel = stringResource(R.string.audio_bitrate_kbps, bps / 1000)
                // 24 kbps is the recommended sweet spot for voice — flag it right in the dropdown.
                val label = if (bps == RECOMMENDED_AUDIO_BIT_RATE) "$kbpsLabel ($recommendedLabel)" else kbpsLabel
                OptionItem(bps.toString(), label)
            }

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_audio_bitrate),
                selected = bitrateOptions.find { it.key == savedBitRate.toString() }
                    ?: bitrateOptions.first(), // fallback gracefully if bitrate was removed from expected options
                options  = bitrateOptions,
                onOptionSelected = { actions.setAudioBitRate(it.key.toInt()) }
            )
        }
    }
}

/** Shows the theme and dynamic colour settings.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 */
/**
 * **General** — the settings that are about the app itself rather than about a recording: how it
 * looks, what is switched on experimentally, and how it updates.
 *
 * These were three top-level accordions, which made Settings read as a flat list of everything
 * instead of having a shape. They keep their own section keys as sub-headers so nothing about the
 * user's stored state changes.
 */
@Composable
private fun GeneralSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    // One open sub-section at a time, within this section only, and nothing open on entry.
    var openSub by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsSection(
        title = stringResource(R.string.settings_section_general),
        expanded = expanded,
        onToggle = onToggle,
        wrapInCard = false,
    ) {
        SettingsSubSection(
            title = stringResource(R.string.settings_section_visual),
            expanded = openSub == SUB_VISUAL,
            onToggle = { openSub = if (openSub == SUB_VISUAL) null else SUB_VISUAL },
        ) { VisualSubSection(preferences, updateTrigger, actions) }
        SettingsSubSection(
            title = stringResource(R.string.settings_section_experimental),
            expanded = openSub == SUB_EXPERIMENTAL,
            onToggle = { openSub = if (openSub == SUB_EXPERIMENTAL) null else SUB_EXPERIMENTAL },
        ) { ExperimentalSubSection() }
        SettingsSubSection(
            title = stringResource(R.string.settings_section_updates),
            expanded = openSub == SUB_UPDATES,
            onToggle = { openSub = if (openSub == SUB_UPDATES) null else SUB_UPDATES },
        ) { UpdatesSubSection(preferences, updateTrigger, actions) }
    }
}

@Composable
private fun VisualSubSection(preferences: AppPreferences, updateTrigger: Int, actions: SettingsActions) {
    val currentThemeMode = remember(updateTrigger) { preferences.getThemeMode() }
    val isDynamicColorEnabled = remember(updateTrigger) { preferences.isDynamicColorEnabled() }
    val isShowToastsEnabled = remember(updateTrigger) { preferences.isShowToastsEnabled() }
    val isVibrationEnabled = remember(updateTrigger) { preferences.isVibrationEnabled() }
    val context = LocalContext.current
    val resources = LocalResources.current

    // Read the current applied language without warnings
    val currentLanguage = remember {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) "" else currentLocales[0]?.toLanguageTag() ?: ""
    }

    // Fetch available languages from dynamically generated XML resource file.
    val languageOptions = remember(context) {
        val options = mutableListOf(OptionItem("", resources.getString(R.string.settings_language_system)))

        // Suppress the warning right here since AGP create this file dynamically at compile time
        @SuppressLint("DiscouragedApi")
        val resId = resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)

        try {
            val parser = resources.getXml(resId)
            var eventType = parser.eventType

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    val localeName = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                    if (localeName != null) {
                        val locale = Locale.forLanguageTag(localeName)
                        val displayName = locale.getDisplayName(locale).replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                        }
                        options.add(OptionItem(localeName, displayName))
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            options.add(OptionItem("en", "English (Provided as fallback)"))
        }
        options.distinctBy { it.key }
    }

    Column {
        DropdownRow {
            M3DropdownField(
                label = stringResource(R.string.settings_language),
                selected = languageOptions.find { it.key == currentLanguage } ?: languageOptions.first(),
                options = languageOptions,
                onOptionSelected = { actions.setAppLanguage(it.key) }
            )
        }

        val themeOptions = AppPreferences.ThemeMode.entries.map { mode ->
            val labelRes = when (mode) {
                AppPreferences.ThemeMode.SYSTEM -> R.string.settings_theme_mode_system
                AppPreferences.ThemeMode.LIGHT -> R.string.settings_theme_mode_light
                AppPreferences.ThemeMode.DARK -> R.string.settings_theme_mode_dark
            }
            OptionItem(mode.key, stringResource(labelRes))
        }
        val defaultThemeMode = AppPreferences.DefaultsValue.THEME_MODE.key

        DropdownRow {
            M3DropdownField(
                label    = stringResource(R.string.settings_theme_mode),
                selected = themeOptions.find { it.key == currentThemeMode.key }
                    ?: themeOptions.find { it.key == defaultThemeMode }
                    ?: themeOptions.first(),
                options  = themeOptions,
                onOptionSelected = { actions.setThemeMode(AppPreferences.ThemeMode.fromKey(it.key)) }
            )
        }

        SettingsDivider()

        SettingsToggleRow(
            icon            = Icons.Filled.ColorLens,
            label           = stringResource(R.string.settings_dynamic_color),
            checked         = isDynamicColorEnabled,
            onCheckedChange = { actions.setDynamicColorEnabled(it) }
        )
        SettingsToggleRow(
            icon            = Icons.Filled.NotificationsActive,
            label           = stringResource(R.string.settings_show_toasts),
            checked         = isShowToastsEnabled,
            onCheckedChange = { actions.setShowToastsEnabled(it) }
        )
        SettingsToggleRow(
            icon            = Icons.Filled.Vibration,
            label           = stringResource(R.string.settings_vibration_enabled),
            checked         = isVibrationEnabled,
            onCheckedChange = { actions.setVibrationEnabled(it) }
        )
    }
}

/**
 * Debug section (always visible). The flow is: turn logging on, reproduce the issue, turn logging
 * off, then share the captured log.
 *
 * - While logging is **on**, we show a red reminder to turn it back off; sharing is intentionally
 *   hidden so the user isn't sharing a log that is still being written to.
 * - While logging is **off**, the Share button appears only if a valid (non-empty) log file from a
 *   previous session still exists — there is nothing to share otherwise.
 *
 * Logs stay redacted (phone numbers masked). Each time logging is turned on the previous capture is
 * cleared (see [SettingsViewModel.setLoggingEnabled]) so every report is a fresh, focused log.
 *
 * @param preferences   The [AppPreferences] instance to read data from.
 * @param updateTrigger Trigger value to force recomposition when settings change.
 * @param actions       Implementation of [SettingsActions] to handle user interaction.
 * @param onShareLogs   Called to share the diagnostic log report via the system share-sheet.
 */
@Composable
private fun BugReportSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions,
    onShareLogs: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val isLoggingEnabled = remember(updateTrigger) { preferences.isLoggingEnabled() }
    // Re-checked on every settings change (e.g. right after the toggle flips off) so the Share
    // button appears as soon as a capture is frozen on disk.
    val hasLogs = remember(updateTrigger) { AppLogger.hasLogs() }
    val logSize = remember(updateTrigger) { AppLogger.logSizeBytes() }
    var showLogViewer by remember { mutableStateOf(false) }
    var confirmClearLog by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(R.string.settings_section_debug), expanded = expanded, onToggle = onToggle) {
        SettingsToggleRow(
            icon            = Icons.Filled.BugReport,
            label           = stringResource(R.string.settings_debug_logging_enabled),
            checked         = isLoggingEnabled,
            onCheckedChange = { actions.setLoggingEnabled(it) },
            description     = stringResource(R.string.settings_debug_logging_enabled_description)
        )

        if (isLoggingEnabled) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_bugreport_active_warning),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        } else if (hasLogs) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_bugreport_share_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onShareLogs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_bugreport_share))
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        // The log file itself — visible whether or not logging is currently running. Until now the
        // file was invisible and the only way to clear it was to toggle logging off and on again,
        // which is a side effect nobody would guess at.
        if (hasLogs) {
            SettingsDivider()
            NavigationRow(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                label = stringResource(R.string.settings_debug_log_file),
                value = formatByteSize(logSize),
                supporting = stringResource(R.string.settings_debug_log_view_hint),
                onClick = { showLogViewer = true }
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                CvSecondaryButton(
                    text = stringResource(R.string.settings_debug_log_delete),
                    onClick = { confirmClearLog = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }

    if (showLogViewer) {
        DebugLogViewer(onDismiss = { showLogViewer = false })
    }

    if (confirmClearLog) {
        AlertDialog(
            onDismissRequest = { confirmClearLog = false },
            title = { Text(stringResource(R.string.settings_debug_log_delete_title)) },
            text = { Text(stringResource(R.string.settings_debug_log_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearLog = false
                    AppLogger.clearLogs()
                    // Logging may still be running, so the file is recreated immediately and empty.
                    // Refreshing re-reads hasLogs/size rather than leaving a stale figure on screen.
                    actions.refreshSettings()
                }) { Text(stringResource(R.string.settings_debug_log_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearLog = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            },
        )
    }
}

/** How much of the log the in-app viewer shows. The file self-trims at 1000 lines. */
private const val LOG_VIEW_LINES = 500

/**
 * Reads the tail of the debug log into a scrollable dialog.
 *
 * Reading happens off the main thread and only while the dialog is open, so opening Settings never
 * pays for a file read the user did not ask for.
 */
@Composable
private fun DebugLogViewer(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        text = AppLogger.readTail(LOG_VIEW_LINES)
        loaded = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_debug_log_file)) },
        text = {
            when {
                !loaded -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                text.isNullOrBlank() -> Text(stringResource(R.string.settings_debug_log_empty))
                else -> Text(
                    text = text!!,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .heightIn(max = LOG_VIEW_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    softWrap = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.general_close)) }
        },
    )
}

/** Caps the viewer so a long log cannot push the dialog's buttons off the screen. */
private val LOG_VIEW_MAX_HEIGHT = 420.dp

/**
 * "Reliability" — controls that keep recording working in tricky conditions: recording without Wi-Fi
 * (loopback opt-in) and the Default USB Configuration that stops a screen-lock from killing the recorder
 * mid-call. Grouped together so users have one place to make recording robust.
 */
@Composable
private fun ExperimentalSubSection() {
    Column {
        SettingsSubHeader(stringResource(R.string.settings_subsection_resilience), nested = true)
        HandoffPersistToggle()
        SettingsDivider()
        OfflineRecordingToggle()
        SettingsDivider()
        UsbDebuggingToggle()
        SettingsDivider()
        UsbDefaultConfigRow()

        SettingsSubHeader(stringResource(R.string.settings_subsection_voip), nested = true)
        VoipRecordingToggle()
    }
}

/** One-line status under a settings row (arming progress, or why something is unavailable). */
@Composable
private fun SettingsHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
    )
}

/** Small label separating groups of related rows inside one collapsible section. */
@Composable
private fun SettingsSubHeader(text: String, nested: Boolean = false, modifier: Modifier = Modifier) {
    // General ▸ Experimental ▸ Resilience is three levels deep. Rendering the inner grouping at the
    // same weight as its parent makes the two read as siblings, so a nested header is quieter and
    // indented instead of introducing a second component.
    Text(
        text = text,
        style = if (nested) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
        color = if (nested) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(
            start = if (nested) 20.dp else 12.dp,
            top = if (nested) 10.dp else 12.dp,
            bottom = if (nested) 2.dp else 8.dp,
        )
    )
}

/**
 * Opt-in "Record VoIP calls" toggle (experimental).
 *
 * Unlike the other opt-ins this one carries a confirmation, for two reasons that are not the app's to
 * decide for the user: consent law for VoIP recording is stricter than for carrier calls in many
 * places, and support is per-app — an app that opts out of capture cannot be recorded at all, and we
 * can only tell once a call is under way.
 */
@Composable
internal fun VoipRecordingToggle() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isVoipRecordingEnabled()) }
    var autoStart by remember { mutableStateOf(prefs.isVoipAutoStartEnabled()) }
    var showWarning by remember { mutableStateOf(false) }
    var arming by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }

    // Arming touches the daemon (and may launch it), so never on the main thread. The policy has to be
    // registered before any VoIP call starts, which is why this happens on the toggle rather than when
    // a call begins.
    fun applyPreference(turnOn: Boolean) {
        enabled = turnOn
        prefs.setVoipRecordingEnabled(turnOn)
        unavailable = false
        scope.launch {
            arming = turnOn
            val ok = withContext(Dispatchers.IO) { VoipCaptureController.sync(context) }
            // Restart the keep-alive service so its detector picks up the new preference (it hosts
            // VoIP detection, so the feature is only live once it has re-synced).
            runCatching { DaemonKeepAliveService.start(context) }
            arming = false
            if (turnOn && !ok) unavailable = true
        }
    }

    SettingsToggleRow(
        icon = Icons.Filled.Groups,
        label = stringResource(R.string.settings_voip_recording_label),
        description = stringResource(R.string.settings_voip_recording_description),
        checked = enabled,
        onCheckedChange = { turnOn ->
            if (turnOn) showWarning = true  // confirm before enabling; never before turning OFF
            else applyPreference(false)
        },
    )
    if (arming) {
        SettingsHint(stringResource(R.string.voip_recording_arming))
    } else if (unavailable) {
        SettingsHint(stringResource(R.string.voip_recording_unavailable))
    } else if (enabled) {
        // A calling app's ongoing-call notification is the only place the system holds the contact's
        // name, so an app denied notification permission produces correct but nameless recordings.
        // Worth saying out loud: it looks like a bug in CallVault and is not one.
        SettingsHint(stringResource(R.string.voip_recording_names_hint))
    }

    // Auto-start vs "ask me", nested under the feature it qualifies: with VoIP recording off there is
    // no detection running, so there would be nothing to ask about.
    AnimatedVisibility(
        visible = enabled,
        enter   = fadeIn() +  expandVertically(),
        exit    = fadeOut() + shrinkVertically()
    ) {
        NestedGroup {
            SettingsToggleRow(
                label = stringResource(R.string.settings_voip_auto_start_label),
                description = stringResource(R.string.settings_voip_auto_start_description),
                checked = autoStart,
                onCheckedChange = { on ->
                    autoStart = on
                    prefs.setVoipAutoStartEnabled(on)
                }
            )
        }
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.voip_recording_warning_title)) },
            text = { Text(stringResource(R.string.voip_recording_warning_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    applyPreference(true)
                }) { Text(stringResource(R.string.voip_recording_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(stringResource(R.string.general_cancel))
                }
            },
        )
    }
}

/**
 * Opt-in "Resilient recording" toggle (audio-capture handoff, Option B). Default OFF — the recording
 * path is byte-identical to daemon mode. When ON (and the source is handoff-compatible) the daemon hands
 * its live capture to the always-alive app, so a recording keeps going and finalises even if the
 * background helper (daemon) is killed mid-call (e.g. screen-lock restarts adbd). A plain preference with
 * no arming side effects, so — unlike the offline toggle — it writes straight from the switch.
 */
@Composable
internal fun HandoffPersistToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isHandoffPersistEnabled()) }

    SettingsToggleRow(
        icon = Icons.Filled.Shield,
        label = stringResource(R.string.settings_handoff_persist_label),
        description = stringResource(R.string.settings_handoff_persist_description),
        checked = enabled,
        onCheckedChange = { turnOn ->
            enabled = turnOn
            prefs.setHandoffPersistEnabled(turnOn)
        },
    )
}


/**
 * Turns on **USB debugging**, which is what lets CallVault switch Wireless debugging off.
 *
 * `adbd` runs only while USB debugging or Wireless debugging is enabled, and CallVault's helper lives
 * inside it — so with neither, there is no helper (measured on a OnePlus 12 and a Galaxy S24 FE; see the
 * README). USB debugging is the better of the two to leave on: no cable is needed, and unlike Wireless
 * debugging it opens **no network port**. Shizuku makes the same trade, its manager just does it
 * silently; asking is better.
 *
 * Turning it back off is allowed and honest about the consequence — Wireless debugging comes back on,
 * because otherwise the helper would have no way in at all.
 */
@Composable
private fun UsbDebuggingToggle() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AdbShell.isUsbDebuggingEnabled(context)) }
    var failed by remember { mutableStateOf(false) }

    SettingsToggleRow(
        icon = Icons.Filled.Usb,
        label = stringResource(R.string.settings_usb_debugging_label),
        description = stringResource(R.string.settings_usb_debugging_description),
        checked = enabled,
        onCheckedChange = { turnOn ->
            val ok = runCatching {
                Settings.Global.putInt(context.contentResolver, "adb_enabled", if (turnOn) 1 else 0)
            }.isSuccess
            failed = !ok
            if (ok) enabled = turnOn
        },
    )
    when {
        failed -> SettingsHint(stringResource(R.string.settings_usb_debugging_failed))
        enabled -> SettingsHint(stringResource(R.string.settings_usb_debugging_on_hint))
        else -> SettingsHint(stringResource(R.string.settings_usb_debugging_recommended))
    }
}

/**
 * Lets the user pick the device's **Default USB Configuration** (what USB does when the screen unlocks)
 * from inside the app, applied over the embedded ADB shell. **"Charging only" is recommended**: on many
 * OEMs a data default (File transfer, etc.) makes the USB gadget renegotiate on every screen on/off,
 * restarting adbd and killing the recorder daemon — which stops a recording if you lock the phone
 * mid-call. The other modes are offered for people who rely on them (e.g. USB tethering); picking one
 * just trades that reliability. Reads the live value on open (falls back to the cached value), and shows
 * a spinner-style "applying" hint while the shell command runs.
 */
@Composable
private fun UsbDefaultConfigRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(UsbDefaultConfig.cached(context)) }
    var applying by remember { mutableStateOf(false) }

    // Refresh the live value once when shown (readViaShell connects+retries internally); falls back to
    // the shown cached value on failure.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UsbDefaultConfig.readViaShell(context) }?.let { mode = it }
    }

    val recommendedLabel = stringResource(R.string.general_recommended)
    val options = UsbDefaultConfig.SELECTABLE.map { m ->
        val base = stringResource(usbModeLabelRes(m))
        OptionItem(m.name, if (m == UsbDefaultConfig.RECOMMENDED) "$base ($recommendedLabel)" else base)
    }
    // When the current value is UNKNOWN (never read), don't force a wrong selection — show recommended.
    val selected = options.find { it.key == mode.name } ?: options.first()

    DropdownRow {
        M3DropdownField(
            label = stringResource(R.string.settings_usb_default_label),
            selected = selected,
            options = options,
            enabled = !applying,
            onOptionSelected = { opt ->
                val target = runCatching { UsbDefaultMode.valueOf(opt.key) }.getOrNull() ?: return@M3DropdownField
                if (target == mode || applying) return@M3DropdownField
                applying = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { UsbDefaultConfig.setViaShell(context, target) }
                    if (ok) mode = target
                    applying = false
                }
            },
        )
        // While the change is being applied + verified, grey the field (above) and show a live spinner,
        // so the (few-second) delay before the value updates can't be mistaken for "nothing happened".
        if (applying) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_usb_default_applying),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            HintText(stringResource(R.string.settings_usb_default_hint))
        }
    }
}

/** Maps a [UsbDefaultMode] to its user-facing label string resource. */
@StringRes
private fun usbModeLabelRes(mode: UsbDefaultMode): Int = when (mode) {
    UsbDefaultMode.CHARGING -> R.string.usb_mode_charging
    UsbDefaultMode.DEBUGGING_ONLY -> R.string.usb_mode_debugging_only
    UsbDefaultMode.FILE_TRANSFER -> R.string.usb_mode_file_transfer
    UsbDefaultMode.PTP -> R.string.usb_mode_ptp
    UsbDefaultMode.TETHERING -> R.string.usb_mode_tethering
    UsbDefaultMode.MIDI -> R.string.usb_mode_midi
    UsbDefaultMode.UNKNOWN -> R.string.usb_mode_charging
}

/**
 * Opt-in "Offline recording (no Wi-Fi)" toggle. Turning it ON pops a security-warning modal
 * (Cancel / Continue anyway) because it arms a local `adb tcpip` debugging port; only on "Continue"
 * do we persist the opt-in, arm the loopback listener, and re-warm the daemon. Turning it OFF clears
 * the opt-in and best-effort closes the port (reverts adbd to USB mode).
 */
@Composable
private fun OfflineRecordingToggle() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.isOfflineRecordingEnabled()) }
    // Non-null while the enable/disable dialog is walking the user through the ADB work with live feedback.
    var dialogMode by remember { mutableStateOf<OfflineDialogMode?>(null) }

    SettingsToggleRow(
        icon = Icons.Filled.WifiOff,
        label = stringResource(R.string.settings_offline_recording_label),
        description = stringResource(R.string.settings_offline_recording_desc),
        checked = enabled,
        enabled = dialogMode == null,
        onCheckedChange = { turnOn ->
            dialogMode = if (turnOn) OfflineDialogMode.ENABLE else OfflineDialogMode.DISABLE
        },
    )

    dialogMode?.let { mode ->
        OfflineRecordingDialog(
            mode = mode,
            onResult = { nowEnabled -> enabled = nowEnabled },
            onClose = { dialogMode = null },
        )
    }
}

/** Shows the app version, server version, clipboard buttons, and a GitHub link.
 *
 * @param versionString         The formatted app-version string to display.
 * @param onShowLicenses        Called when the user taps "View Licenses".
 */
@Composable
private fun AboutSection(
    versionString: String,
    onShowLicenses: () -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val serverVersion = ScrcpyConfig.SCRCPY_VERSION
    var showSupportDialog by remember { mutableStateOf(false) }

    if (showSupportDialog) {
        SupportDialog(onDismiss = { showSupportDialog = false })
    }

    SettingsSection(title = stringResource(R.string.settings_section_about), expanded = expanded, onToggle = onToggle) {
        NavigationRow(
            icon = Icons.Filled.Save,
            label = stringResource(R.string.settings_ui_about_app),
            value = versionString,
            supporting = stringResource(R.string.settings_scrcpy_server, serverVersion),
            showChevron = false
        )

        SettingsDivider()

        // Required fork attribution under the upstream license (GPLv3 §7). Opens the upstream repo.
        NavigationRow(
            icon = Icons.Filled.Gavel,
            label = stringResource(R.string.settings_fork_attribution),
            value = stringResource(R.string.settings_fork_attribution_supporting),
            supporting = stringResource(R.string.settings_ui_open_repo_hint),
            onClick = { context.openOriginalProjectRepo() }
        )

        SettingsDivider()

        // Optional "support development" link. Offers Ko-fi and PayPal; both open in the browser.
        NavigationRow(
            icon = Icons.Filled.Favorite,
            label = stringResource(R.string.settings_support_label),
            value = stringResource(R.string.settings_support_value),
            supporting = stringResource(R.string.settings_support_supporting),
            onClick = { showSupportDialog = true }
        )

        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CvSecondaryButton(
                text = stringResource(R.string.settings_copy_version),
                onClick = { context.copyToClipboard("Scrcpy-Server Version", ScrcpyConfig.SCRCPY_VERSION) },
                leadingIcon = Icons.Filled.ContentCopy,
                modifier = Modifier.weight(1f)
            )
            CvSecondaryButton(
                text = stringResource(R.string.settings_view_licenses),
                onClick = onShowLicenses,
                leadingIcon = Icons.Filled.Gavel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Internal helper composables ────────────────────────────────────────────────────────────

/** A branded, collapsible section: a tappable [CvSectionHeader] (with a rotating chevron) above a
 * [CvCard] grouping its rows. Tapping the header invokes [onToggle]; the body animates in and out.
 * The expand/collapse state is HOISTED to the parent so the Settings screen can run an accordion
 * (at most one section open at a time); this composable is stateless about expansion.
 *
 * @param title    Section heading shown above the card; the whole header row toggles the section.
 * @param expanded Whether this section's body is currently shown.
 * @param onToggle Invoked when the header is tapped (the parent decides the new open-section).
 * @param content  The slot for child rows rendered inside the [CvCard] when expanded.
 */
/**
 * In-app updates: a daily release check (on by default). A new release surfaces as a notification +
 * Home banner, and installs only on an explicit tap.
 */
@Composable
private fun UpdatesSubSection(
    preferences: AppPreferences,
    updateTrigger: Int,
    actions: SettingsActions
) {
    val isCheckEnabled = remember(updateTrigger) { preferences.isUpdateCheckEnabled() }

    Column {
        SettingsToggleRow(
            icon = Icons.Filled.SystemUpdate,
            label = stringResource(R.string.settings_update_check_label),
            checked = isCheckEnabled,
            onCheckedChange = { actions.setUpdateCheckEnabled(it) },
            description = stringResource(R.string.settings_update_check_description)
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    // A section made of sub-sections passes false: the card then belongs around each OPEN
    // sub-section's rows, not around a list of labels that do nothing until tapped. Wrapping the
    // labels made a closed section read as a floating menu of empty items.
    wrapInCard: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSectionChevron"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CvSectionHeader(text = title, modifier = Modifier.weight(1f))
            // A section takes the accent, matching its teal dash; a sub-section gets a small muted
            // triangle instead (see [SettingsSubSection]). Differing in BOTH shape and colour is what
            // makes the two levels tellable apart at a glance — size alone did not.
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(22.dp)
                    .rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (wrapInCard) {
                CvCard(contentPadding = PaddingValues(vertical = 8.dp)) { content() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
    }
}

/**
 * A collapsible group INSIDE a section — Storage ▸ Retention, General ▸ Updates, and so on.
 *
 * Same interaction as [SettingsSection] so the two levels behave alike, and the same shape: a bare
 * header row, with the card around the CONTENT once it opens. The header is quieter than a section
 * title — ordinary text rather than the accent colour — so it reads as a level down rather than as a
 * competing heading.
 *
 * Callers hold the open key themselves, one per parent section, which makes the sub-sections an
 * accordion within their own section only — opening Retention cannot close something in General.
 */
@Composable
private fun SettingsSubSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsSubSectionChevron"
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSubHeader(title, modifier = Modifier.weight(1f))
            // Triangle, not a chevron, and muted rather than accented — the opposite of the section
            // marker above it on both counts.
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).size(24.dp).rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CvCard(contentPadding = PaddingValues(vertical = 8.dp)) { content() }
        }
    }
}

/** A thin inset divider used to separate row clusters inside a [CvCard]. */
@Composable
private fun SettingsDivider() {
    // outlineVariant is Material's subtlest rule, and against this app's very dark surface it all but
    // disappears — reported as "barely visible in dark theme". Deriving the colour from
    // onSurfaceVariant instead keeps it theme-aware (it inverts with the scheme) while guaranteeing
    // contrast against whatever surface it lands on, in light and dark alike.
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
    )
}

/** Wraps a [M3DropdownField] (and optional hint) so it slots cleanly inside a [CvCard]. */
@Composable
private fun DropdownRow(content: @Composable ColumnScope.() -> Unit) {
    Column(content = content)
}

/** Indents and tints a nested option cluster revealed under an auto-record toggle. */
@Composable
private fun NestedGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        content = content
    )
}

/** A small muted helper line shown beneath a dropdown/field. */
@Composable
private fun HintText(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

/** Circular tinted leading-icon badge used by settings rows. */
@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * A tappable navigation/dialog row: leading icon, label + value (+ optional supporting hint), and a
 * trailing chevron. Used for folder pickers, the filename template, and the About rows.
 */
@Composable
private fun NavigationRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    showChevron: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * A switch row styled for the Signal cards: optional leading icon, label + supporting text, and a
 * teal [Switch]. Tapping anywhere on the row toggles it. Mirrors the behavior of the shared
 * ToggleListItem while matching the redesigned row anatomy.
 */
@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    description: String? = null,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            RowIcon(icon)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A radio-button group for choosing which contacts to ignore.
 * When "selected" is active, shows a text field and a "Pick Contacts" button.
 *
 * @param label           Label shown above the radio buttons.
 * @param selectedEnum     The currently active mode ("none", "all", or "selected").
 * @param selectedCount    The number of contacts currently selected
 * @param onSelected      Called with the new active mode when the user taps a radio button.
 * @param onSelectContacts Called when the user taps the "Select Contacts" button; opens the
 *                        [ContactSelectionDialog] via [ContactPickerViewModel].
 */
@Composable
private fun IgnoreContactsOptions(
    label: String,
    selectedEnum: AppPreferences.IgnoreContactsMode,
    selectedCount: Int,
    onSelected: (AppPreferences.IgnoreContactsMode) -> Unit,
    onSelectContacts: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        val enumEntries = AppPreferences.IgnoreContactsMode.entries
        enumEntries.forEach { ignoreContactMode ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // This make the box/text next to the radio button clickable, not just the button itself, which is more user-friendly.
                    .clickable { onSelected(ignoreContactMode) }
                    .padding(vertical = 4.dp)
            ) {
                // Make the actual radio button (circle) clickable (it's quite small)
                RadioButton(selected = selectedEnum == ignoreContactMode, onClick = { onSelected(ignoreContactMode) })
                Text(
                    text = when (ignoreContactMode) {
                        AppPreferences.IgnoreContactsMode.NONE -> stringResource(R.string.settings_ignore_contacts_none)
                        AppPreferences.IgnoreContactsMode.ALL -> stringResource(R.string.settings_ignore_contacts_all)
                        AppPreferences.IgnoreContactsMode.SELECTED   -> stringResource(R.string.settings_ignore_contacts_selected)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (selectedEnum == AppPreferences.IgnoreContactsMode.SELECTED) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick  = onSelectContacts,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.small
            ) { Text(stringResource(R.string.settings_select_contacts, selectedCount)) }
        }
    }
}

/**
 * Safe Compose Preview for Settings.
 */
@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MaterialTheme {
        val mockContext = LocalContext.current
        val dummyPreferences = AppPreferences(mockContext)
        val dummyActions = object : SettingsActions {
            override fun refreshSettings() {}
            override fun setCarrierRecording(enabled: Boolean) {}
            override fun setAutoRecordIncoming(enabled: Boolean) {}
            override fun setAutoRecordOutgoing(enabled: Boolean) {}
            override fun setVibrationEnabled(enabled: Boolean) {}
            override fun setIgnoreAnonymousIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryIncoming(enabled: Boolean) {}
            override fun setIgnoreCrossCountryOutgoing(enabled: Boolean) {}
            override fun setIgnoreContactsModeIncoming(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setIgnoreContactsModeOutgoing(modeEnum: AppPreferences.IgnoreContactsMode) {}
            override fun setAudioSource(source: String) {}
            override fun setAudioCodec(codec: String) {}
            override fun setAudioBitRate(bitRate: Int) {}
            override fun setThemeMode(mode: AppPreferences.ThemeMode) {}
            override fun setDynamicColorEnabled(enabled: Boolean) {}
            override fun setShowToastsEnabled(enabled: Boolean) {}
            override fun setAppLanguage(languageCode: String) {}
            override fun setLoggingEnabled(enabled: Boolean) {}
            override fun getAppVersion(): String = "Version 1.0.0 (Mock)"
            override fun setFileNameTemplate(template: String) {}
            override fun setStorageTarget(target: StorageTarget) {}
            override fun setDriveFolderUri(uri: android.net.Uri?) {}
            override fun setRetentionLinked(linked: Boolean) {}
            override fun setRetentionLocalDays(days: Int) {}
            override fun setRetentionDriveDays(days: Int) {}
            override fun setRetentionTimeHour(hour: Int) {}
            override fun setRetentionTimeMinute(minute: Int) {}
            override fun setSyncScheduleMode(mode: SyncScheduleMode) {}
            override fun setSyncTimeHour(hour: Int) {}
            override fun setSyncTimeMinute(minute: Int) {}
            override fun setSyncDayOfWeek(day: Int) {}
            override fun setUpdateCheckEnabled(enabled: Boolean) {}
        }

        SettingsContent(
            preferences = dummyPreferences,
            updateTrigger = 0,
            actions = dummyActions,
            contactPickerState = null,
            onBack = {},
            onSelectFolder = {},
            onSelectDriveFolder = {},
            onOpenContactsIncoming = {},
            onOpenContactsOutgoing = {},
            onConfirmContacts = {},
            onDismissContacts = {},
            onShareLogs = {}
        )
    }
}
