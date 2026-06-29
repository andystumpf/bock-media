package com.bockmedia.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.ui.navigation.BockRoute
import com.bockmedia.console.ui.testing.BockTestTags
import com.bockmedia.console.ui.theme.BockGreen
import com.bockmedia.console.ui.theme.BockMuted
import com.bockmedia.console.ui.theme.SpotifyElevated

private data class AccountMenuSection(
    val title: String,
    val routes: List<BockRoute>,
)

private val accountMenuSections = listOf(
    AccountMenuSection("Library", listOf(BockRoute.Settings, BockRoute.Downloads, BockRoute.Analytics)),
    AccountMenuSection("Alexa & home", listOf(
        BockRoute.RecentRequests,
        BockRoute.Rooms,
        BockRoute.Devices,
        BockRoute.Family,
        BockRoute.Driving,
    )),
    AccountMenuSection("App", listOf(BockRoute.About)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenuButton(onNavigate: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    IconButton(
        onClick = { open = true },
        modifier = Modifier.testTag(BockTestTags.ACCOUNT_MENU_BUTTON),
    ) {
        Surface(
            shape = CircleShape,
            color = SpotifyElevated,
            tonalElevation = 0.dp,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Account menu",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 12.dp, bottom = 4.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BockMuted.copy(alpha = 0.45f)),
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(BockTestTags.ACCOUNT_MENU),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.padding(bottom = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = BockGreen.copy(alpha = 0.18f),
                            modifier = Modifier.size(52.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = BockGreen,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Bock Media",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Settings & household",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BockMuted,
                            )
                        }
                    }
                }

                accountMenuSections.forEach { section ->
                    item {
                        Text(
                            section.title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = BockMuted,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp),
                        )
                    }
                    items(section.routes, key = { it.route }) { route ->
                        AccountMenuRow(
                            route = route,
                            onClick = {
                                open = false
                                onNavigate(route.route)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AccountMenuRow(
    route: BockRoute,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = SpotifyElevated,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        route.icon,
                        contentDescription = null,
                        tint = BockGreen,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                route.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = BockMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
