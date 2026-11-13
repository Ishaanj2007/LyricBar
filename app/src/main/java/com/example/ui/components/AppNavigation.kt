package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AppTab

/**
 * Floating frosted-glass bottom navigation pill for compact windows.
 * Employs concentric mathematical geometry (28dp outer capsule, 6dp inner padding, 22dp tab shape)
 * and translucent frosted glass to display blurred ambient artwork behind.
 */
@Composable
fun FloatingBottomNavPill(
    currentTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    FrostedCard(
        hazeState = hazeState,
        shape = RoundedCornerShape(28.dp),
        backgroundAlpha = 0.16f,
        blurRadius = 24.dp,
        borderWidth = 0.75.dp,
        borderAlpha = 0.22f,
        shadowElevation = 6.dp,
        modifier = modifier
            .testTag("main_navigation_bar")
            .widthIn(max = 440.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
                val selected = currentTab == tab
                NavPillItem(
                    tab = tab,
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("nav_tab_${tab.name.lowercase()}")
                )
            }
        }
    }
}

@Composable
private fun NavPillItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.02f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "nav_item_scale"
    )

    val iconColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFB8B8C2),
        label = "nav_icon_color"
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color.Transparent,
        label = "nav_indicator_color"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFB8B8C2),
        label = "nav_text_color"
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = MaterialTheme.colorScheme.primary),
                role = Role.Tab,
                onClick = onClick
            )
            .background(indicatorColor)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.title,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tab.title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Adaptive navigation rail for expanded screens (tablets / landscape / wide foldables).
 */
@Composable
fun AdaptiveNavRail(
    currentTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    BoxWithConstraints(
        modifier = modifier
            .testTag("main_navigation_bar")
            .fillMaxHeight()
    ) {
        val availableHeight = maxHeight
        val isCompactHeight = availableHeight < 440.dp
        val railMinWidth = if (isCompactHeight) 82.dp else 96.dp
        val railMaxWidth = if (isCompactHeight) 96.dp else 112.dp

        FrostedCard(
            hazeState = hazeState,
            shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            backgroundAlpha = 0.60f,
            blurRadius = 20.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = railMinWidth, max = railMaxWidth)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        vertical = if (isCompactHeight) 10.dp else 24.dp,
                        horizontal = if (isCompactHeight) 6.dp else 8.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isCompactHeight) Arrangement.SpaceEvenly else Arrangement.SpaceBetween
            ) {
                // App Branding Icon at top (hidden on ultra-short viewports < 320dp so all 4 tabs fit effortlessly)
                if (availableHeight >= 310.dp) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = if (isCompactHeight) 6.dp else 16.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(if (isCompactHeight) 32.dp else 44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = AppTab.NOW_PLAYING.icon,
                                    contentDescription = "LyricBar",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(if (isCompactHeight) 19.dp else 26.dp)
                                )
                            }
                        }
                        if (!isCompactHeight) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "LyricBar",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Tab navigation items
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    AppTab.entries.forEach { tab ->
                        val selected = currentTab == tab
                        NavRailItem(
                            tab = tab,
                            selected = selected,
                            isCompact = isCompactHeight,
                            onClick = { onTabSelected(tab) },
                            modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                        )
                    }
                }

                if (!isCompactHeight) {
                    // Bottom spacer for balance on taller tablets/desktops
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NavRailItem(
    tab: AppTab,
    selected: Boolean,
    isCompact: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val iconColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        label = "rail_icon_color"
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        label = "rail_indicator_color"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        label = "rail_text_color"
    )

    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = MaterialTheme.colorScheme.primary),
                role = Role.Tab,
                onClick = onClick
            )
            .background(indicatorColor)
            .padding(
                horizontal = if (isCompact) 6.dp else 12.dp,
                vertical = if (isCompact) 6.dp else 10.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.title,
                tint = iconColor,
                modifier = Modifier.size(if (isCompact) 21.dp else 26.dp)
            )
            Spacer(modifier = Modifier.height(if (isCompact) 2.dp else 4.dp))
            Text(
                text = tab.title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (isCompact) 10.sp else 11.sp
                ),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = textColor,
                maxLines = 1
            )
        }
    }
}
