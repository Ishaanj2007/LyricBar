package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.FrostedCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Expandable content for Feedback Centre when placed inside SettingsAccordionCard.
 */
@Composable
internal fun FeedbackSettingsContent(
    onOpenBugReport: () -> Unit,
    onOpenFeatureRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag("settings_feedback_content")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onOpenBugReport,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("settings_feedback_bug_button"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Report a Bug",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FilledTonalButton(
                onClick = onOpenFeatureRequest,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("settings_feedback_feature_button"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Request Feature",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Settings Card displayed inside the main Settings list.
 */
@Composable
internal fun FeedbackSettingsCard(
    onOpenBugReport: () -> Unit,
    onOpenFeatureRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_feedback_card"),
        shape = RoundedCornerShape(18.dp),
        backgroundAlpha = 0.28f,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Feedback,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Feedback Centre",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Report bugs or request new features on GitHub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            FeedbackSettingsContent(
                onOpenBugReport = onOpenBugReport,
                onOpenFeatureRequest = onOpenFeatureRequest
            )
        }
    }
}

/**
 * Full In-App Feedback Centre sub-screen with Bug Report and Feature Request forms.
 */
@Composable
internal fun FeedbackCentreScreen(
    initialTab: FeedbackTab = FeedbackTab.BUG_REPORT,
    hasBottomNavPill: Boolean,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Check connectivity on entry
    var isOnline by remember { mutableStateOf(FeedbackUrlBuilder.isNetworkAvailable(context)) }

    // Form A: Bug Report State (preserved across navigation/recomposition)
    var bugTitle by rememberSaveable { mutableStateOf("") }
    var bugCategory by rememberSaveable { mutableStateOf(FeedbackUrlBuilder.BUG_CATEGORIES.first()) }
    var bugDescription by rememberSaveable { mutableStateOf("") }
    var bugSteps by rememberSaveable { mutableStateOf("") }
    var bugExpected by rememberSaveable { mutableStateOf("") }
    var bugActual by rememberSaveable { mutableStateOf("") }
    var bugIncludeDiagnostics by rememberSaveable { mutableStateOf(true) }

    // Form B: Feature Request State
    var featTitle by rememberSaveable { mutableStateOf("") }
    var featCategory by rememberSaveable { mutableStateOf(FeedbackUrlBuilder.FEATURE_CATEGORIES.first()) }
    var featDescription by rememberSaveable { mutableStateOf("") }
    var featWhyUseful by rememberSaveable { mutableStateOf("") }
    var featExpected by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = !isSubmitting) {
        onNavigateBack()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f))
    ) {
        val isWide = maxWidth >= 660.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (hasBottomNavPill) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isWide) 24.dp else 20.dp, vertical = 12.dp)
                .padding(bottom = if (hasBottomNavPill) 120.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
            ) {
                // Top Header with Back button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = !isSubmitting,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .testTag("feedback_subscreen_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Feedback Centre",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Submit bug reports or suggest features on GitHub",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Offline Notice Banner
                if (!isOnline) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("feedback_offline_banner"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.40f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "No internet connection detected. An internet connection is needed to review and submit your issue on GitHub.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
                            )
                        }
                    }
                }

                // Tab Switcher: Report a Bug vs Request a Feature
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.40f),
                    border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FeedbackTabChip(
                            title = "Report a Bug",
                            icon = Icons.Default.BugReport,
                            isSelected = selectedTab == FeedbackTab.BUG_REPORT,
                            onClick = { selectedTab = FeedbackTab.BUG_REPORT },
                            modifier = Modifier.weight(1f).testTag("feedback_tab_bug")
                        )
                        FeedbackTabChip(
                            title = "Request Feature",
                            icon = Icons.Default.Lightbulb,
                            isSelected = selectedTab == FeedbackTab.FEATURE_REQUEST,
                            onClick = { selectedTab = FeedbackTab.FEATURE_REQUEST },
                            modifier = Modifier.weight(1f).testTag("feedback_tab_feature")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    FeedbackTab.BUG_REPORT -> {
                        BugReportForm(
                            title = bugTitle,
                            onTitleChange = { bugTitle = it },
                            category = bugCategory,
                            onCategoryChange = { bugCategory = it },
                            description = bugDescription,
                            onDescriptionChange = { bugDescription = it },
                            steps = bugSteps,
                            onStepsChange = { bugSteps = it },
                            expected = bugExpected,
                            onExpectedChange = { bugExpected = it },
                            actual = bugActual,
                            onActualChange = { bugActual = it },
                            includeDiagnostics = bugIncludeDiagnostics,
                            onIncludeDiagnosticsChange = { bugIncludeDiagnostics = it },
                            isSubmitting = isSubmitting,
                            onSubmit = {
                                coroutineScope.launch {
                                    isSubmitting = true
                                    isOnline = FeedbackUrlBuilder.isNetworkAvailable(context)
                                    delay(150L) // UI responsive feel
                                    val url = FeedbackUrlBuilder.buildBugReportUrl(
                                        title = bugTitle,
                                        category = bugCategory,
                                        description = bugDescription,
                                        stepsToReproduce = bugSteps,
                                        expectedBehaviour = bugExpected,
                                        actualBehaviour = bugActual,
                                        includeDiagnostics = bugIncludeDiagnostics
                                    )
                                    val opened = FeedbackUrlBuilder.openFeedbackUrl(context, url)
                                    isSubmitting = false
                                    if (!opened) {
                                        Toast.makeText(context, "Could not open browser. Please visit GitHub manually.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                    FeedbackTab.FEATURE_REQUEST -> {
                        FeatureRequestForm(
                            title = featTitle,
                            onTitleChange = { featTitle = it },
                            category = featCategory,
                            onCategoryChange = { featCategory = it },
                            description = featDescription,
                            onDescriptionChange = { featDescription = it },
                            whyUseful = featWhyUseful,
                            onWhyUsefulChange = { featWhyUseful = it },
                            expected = featExpected,
                            onExpectedChange = { featExpected = it },
                            isSubmitting = isSubmitting,
                            onSubmit = {
                                coroutineScope.launch {
                                    isSubmitting = true
                                    isOnline = FeedbackUrlBuilder.isNetworkAvailable(context)
                                    delay(150L)
                                    val url = FeedbackUrlBuilder.buildFeatureRequestUrl(
                                        title = featTitle,
                                        category = featCategory,
                                        description = featDescription,
                                        whyUseful = featWhyUseful,
                                        expectedBehaviour = featExpected
                                    )
                                    val opened = FeedbackUrlBuilder.openFeedbackUrl(context, url)
                                    isSubmitting = false
                                    if (!opened) {
                                        Toast.makeText(context, "Could not open browser. Please visit GitHub manually.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackTabChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)) else null,
        modifier = modifier.height(42.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BugReportForm(
    title: String,
    onTitleChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    steps: String,
    onStepsChange: (String) -> Unit,
    expected: String,
    onExpectedChange: (String) -> Unit,
    actual: String,
    onActualChange: (String) -> Unit,
    includeDiagnostics: Boolean,
    onIncludeDiagnosticsChange: (Boolean) -> Unit,
    isSubmitting: Boolean,
    onSubmit: () -> Unit
) {
    var categoryExpanded by remember { mutableStateOf(false) }
    var showDiagnosticsDetails by remember { mutableStateOf(false) }

    val isFormValid = title.isNotBlank() &&
            description.isNotBlank() &&
            steps.isNotBlank() &&
            expected.isNotBlank() &&
            actual.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Bug Title
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text("Bug Title *") },
            placeholder = { Text("e.g. Floating lyrics freeze during track switch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("feedback_bug_title_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Category Dropdown
        ExposedDropdownMenuBox(
            expanded = categoryExpanded,
            onExpandedChange = { categoryExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category *") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("feedback_bug_category_dropdown"),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(
                expanded = categoryExpanded,
                onDismissRequest = { categoryExpanded = false }
            ) {
                FeedbackUrlBuilder.BUG_CATEGORIES.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onCategoryChange(option)
                            categoryExpanded = false
                        }
                    )
                }
            }
        }

        // Description
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("Description *") },
            placeholder = { Text("Explain what happened and context about the issue...") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("feedback_bug_description_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Steps to reproduce
        OutlinedTextField(
            value = steps,
            onValueChange = onStepsChange,
            label = { Text("Steps to Reproduce *") },
            placeholder = { Text("1. Open LyricBar\n2. Play song in Spotify\n3. Tap HUD button...") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("feedback_bug_steps_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Expected Behaviour
        OutlinedTextField(
            value = expected,
            onValueChange = onExpectedChange,
            label = { Text("Expected Behaviour *") },
            placeholder = { Text("What did you expect to happen?") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("feedback_bug_expected_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Actual Behaviour
        OutlinedTextField(
            value = actual,
            onValueChange = onActualChange,
            label = { Text("Actual Behaviour *") },
            placeholder = { Text("What actually happened instead?") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("feedback_bug_actual_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Screenshot Tip Card
        FrostedCard(
            shape = RoundedCornerShape(14.dp),
            backgroundAlpha = 0.18f,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Screenshot or Screen Recording",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "GitHub supports pasting or dragging screenshots directly into the issue description after opening.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Diagnostics Card & Toggle
        FrostedCard(
            shape = RoundedCornerShape(14.dp),
            backgroundAlpha = 0.18f,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Include safe device diagnostics",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "App version, Android OS, and device model",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = includeDiagnostics,
                        onCheckedChange = onIncludeDiagnosticsChange,
                        modifier = Modifier.testTag("feedback_diagnostics_switch")
                    )
                }

                if (includeDiagnostics) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showDiagnosticsDetails = !showDiagnosticsDetails },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = if (showDiagnosticsDetails) "Hide diagnostic details" else "View information to be shared",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    AnimatedVisibility(visible = showDiagnosticsDetails) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text(
                                text = FeedbackUrlBuilder.getSafeDiagnostics(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Submit Button
        Button(
            onClick = onSubmit,
            enabled = isFormValid && !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("feedback_submit_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Opening GitHub...")
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Review and Submit on GitHub",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        // Disclaimer
        Text(
            text = "Tapping will open GitHub with your issue prefilled. You will review and submit the issue there. No GitHub account is stored in LyricBar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureRequestForm(
    title: String,
    onTitleChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    whyUseful: String,
    onWhyUsefulChange: (String) -> Unit,
    expected: String,
    onExpectedChange: (String) -> Unit,
    isSubmitting: Boolean,
    onSubmit: () -> Unit
) {
    var categoryExpanded by remember { mutableStateOf(false) }

    val isFormValid = title.isNotBlank() &&
            description.isNotBlank() &&
            whyUseful.isNotBlank() &&
            expected.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Feature Title
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            label = { Text("Feature Title *") },
            placeholder = { Text("e.g. Add bilingual dual-language lyrics mode") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("feedback_feature_title_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Category Dropdown
        ExposedDropdownMenuBox(
            expanded = categoryExpanded,
            onExpandedChange = { categoryExpanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = category,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category *") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("feedback_feature_category_dropdown"),
                shape = RoundedCornerShape(12.dp)
            )
            ExposedDropdownMenu(
                expanded = categoryExpanded,
                onDismissRequest = { categoryExpanded = false }
            ) {
                FeedbackUrlBuilder.FEATURE_CATEGORIES.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onCategoryChange(option)
                            categoryExpanded = false
                        }
                    )
                }
            }
        }

        // Description
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("What should LyricBar do? *") },
            placeholder = { Text("Describe the feature or enhancement you would like to see...") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("feedback_feature_description_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Why would this be useful?
        OutlinedTextField(
            value = whyUseful,
            onValueChange = onWhyUsefulChange,
            label = { Text("Why would this feature be useful? *") },
            placeholder = { Text("Explain what problem this solves or how it improves your experience...") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("feedback_feature_why_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Expected behaviour
        OutlinedTextField(
            value = expected,
            onValueChange = onExpectedChange,
            label = { Text("Expected Behaviour *") },
            placeholder = { Text("How should this work in practice? Describe user interactions...") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("feedback_feature_expected_input"),
            shape = RoundedCornerShape(12.dp)
        )

        // Screenshot Tip Card
        FrostedCard(
            shape = RoundedCornerShape(14.dp),
            backgroundAlpha = 0.18f,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Design & Reference Examples",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "You can attach mockups, sketches or reference links directly on GitHub once opened.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Submit Button
        Button(
            onClick = onSubmit,
            enabled = isFormValid && !isSubmitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("feedback_feature_submit_button"),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("Opening GitHub...")
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Review and Submit on GitHub",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        // Disclaimer
        Text(
            text = "Tapping will open GitHub with your feature request prefilled. Final submission takes place on GitHub.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
