package com.bockmedia.console.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.launch

private val PlexampSheetBg = Color(0xFF121212)

fun albumRatingId(album: String, artist: String?): String =
    "${album.trim()}|${artist?.trim().orEmpty()}"

enum class RatingKind(val apiValue: String) {
    Song("song"),
    Album("album"),
    Playlist("playlist"),
    Artist("artist"),
}

data class RatingTarget(
    val kind: RatingKind,
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
)

data class PlexampSheetAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlexampEntitySheet(
    title: String,
    rating: RatingTarget?,
    repository: BockMediaRepository,
    actions: List<PlexampSheetAction>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stars by remember(rating) { mutableIntStateOf(0) }
    var loadingRating by remember(rating) { mutableStateOf(rating != null) }

    LaunchedEffect(rating) {
        if (rating == null) {
            stars = 0
            loadingRating = false
            return@LaunchedEffect
        }
        loadingRating = true
        stars = runCatching { repository.ratingStars(rating.kind, rating.id) }.getOrDefault(0)
        loadingRating = false
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PlexampSheetBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            actions.forEach { action ->
                if (!action.enabled) return@forEach
                ListItem(
                    modifier = Modifier.clickable {
                        action.onClick()
                        onDismiss()
                    },
                    leadingContent = {
                        Icon(action.icon, contentDescription = null, tint = Color.White)
                    },
                    headlineContent = {
                        Text(action.label, color = Color.White, fontWeight = FontWeight.Medium)
                    },
                    colors = ListItemDefaults.colors(containerColor = PlexampSheetBg),
                )
            }
            if (rating != null) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                StarRatingBar(
                    stars = stars,
                    loading = loadingRating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    onStarsChange = { value ->
                        stars = value
                        scope.launch {
                            runCatching {
                                repository.setRating(
                                    kind = rating.kind,
                                    id = rating.id,
                                    stars = value,
                                    title = rating.title,
                                    artist = rating.artist,
                                    album = rating.album,
                                )
                            }
                        }
                    },
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CompactStarRatingBar(
    stars: Int,
    onStarsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    maxStars: Int = 5,
    tint: Color = Color.White,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = tint.copy(alpha = 0.7f),
                strokeWidth = 2.dp,
            )
        } else {
            for (i in 1..maxStars) {
                IconButton(
                    onClick = { onStarsChange(if (stars == i) 0 else i) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (i <= stars) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "$i stars",
                        tint = if (i <= stars) tint else tint.copy(alpha = 0.35f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun StarRatingBar(
    stars: Int,
    onStarsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    maxStars: Int = 5,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color.White.copy(alpha = 0.7f),
                strokeWidth = 2.dp,
            )
        } else {
            for (i in 1..maxStars) {
                IconButton(
                    onClick = { onStarsChange(if (stars == i) 0 else i) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (i <= stars) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "$i stars",
                        tint = if (i <= stars) Color.White else Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
    }
}
