package com.bockmedia.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeCard
import com.bockmedia.console.domain.model.HomeFilter
import com.bockmedia.console.domain.model.HomeSection
import com.bockmedia.console.domain.model.HomeSectionKind
import com.bockmedia.console.ui.theme.*

private val PillShape = RoundedCornerShape(50)

@Composable
fun HomeHeader(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    onAccountNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.verticalGradient(
        colors = listOf(HomeGradientTop, HomeGradientBottom),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(gradient),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(bottom = 4.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeGreeting(modifier = Modifier.weight(1f))
                AccountMenuButton(onAccountNavigate)
            }
            Spacer(Modifier.height(4.dp))
            HomePillFilters(
                selected = selected,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
fun HomePillFilters(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inactive = HomePillInactive
    val active = HomePillActive
    val inactiveText = MaterialTheme.colorScheme.onSurface
    val activeText = Color(0xFF0F1419)

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(HomeFilter.entries.toList()) { filter ->
            val isActive = filter == selected
            Surface(
                onClick = { onSelect(filter) },
                shape = PillShape,
                color = if (isActive) active else inactive,
                shadowElevation = if (isActive) 1.dp else 0.dp,
                tonalElevation = 0.dp,
            ) {
                Text(
                    filter.label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isActive) activeText else inactiveText,
                )
            }
        }
    }
}

@Composable
fun SpotifyHomeSection(
    section: HomeSection,
    repository: BockMediaRepository,
    onPlay: (HomeCard) -> Unit,
    modifier: Modifier = Modifier,
    compactTop: Boolean = false,
    artLoadKey: Any = Unit,
) {
    Column(modifier = modifier.padding(top = if (compactTop) 6.dp else 12.dp)) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(section.cards, key = { "${artLoadKey}-${it.id}" }) { card ->
                when (section.kind) {
                    HomeSectionKind.TopMixes, HomeSectionKind.DailyMixes ->
                        GenreMixTile(card, repository, onPlay, artLoadKey)
                    else -> PlaylistArtTile(card, repository, onPlay, artLoadKey)
                }
            }
        }
    }
}

@Composable
private fun HomeCardArt(
    card: HomeCard,
    repository: BockMediaRepository,
    artLoadKey: Any,
    modifier: Modifier = Modifier,
) {
    val artUrl by produceState<String?>(initialValue = null, card.id, card.artPath, card.playlistId, artLoadKey) {
        value = repository.resolveHomeCardArtUrl(card.id, card.artPath, card.playlistId, card.playTarget)
    }
    SubcomposeAsyncImage(
        model = artUrl,
        contentDescription = card.title,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentScale = ContentScale.Crop,
        loading = { HomeCardArtPlaceholder(Modifier.fillMaxSize()) },
        error = { HomeCardArtPlaceholder(Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
private fun HomeCardArtPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Album,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun PlaylistArtTile(
    card: HomeCard,
    repository: BockMediaRepository,
    onPlay: (HomeCard) -> Unit,
    artLoadKey: Any,
) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .clickable { onPlay(card) },
    ) {
        HomeCardArt(
            card = card,
            repository = repository,
            artLoadKey = artLoadKey,
            modifier = Modifier
                .size(124.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
        )
        card.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GenreMixTile(
    card: HomeCard,
    repository: BockMediaRepository,
    onPlay: (HomeCard) -> Unit,
    artLoadKey: Any,
) {
    Box(
        modifier = Modifier
            .size(width = 140.dp, height = 140.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onPlay(card) },
    ) {
        HomeCardArt(
            card = card,
            repository = repository,
            artLoadKey = artLoadKey,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.72f),
                        ),
                        startY = 40f,
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                card.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            card.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun HomeGreeting(modifier: Modifier = Modifier) {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Text(
        greeting,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
