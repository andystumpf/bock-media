package com.bockmedia.console.ui.recent

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.launch

@Composable
fun RecentRequestsScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(1) }
    var items by remember { mutableStateOf<List<com.bockmedia.console.data.api.dto.RecentPlayRequestItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(page) {
        loading = true
        runCatching {
            val resp = repository.recent(page, 25)
            items = resp.items
            total = resp.total
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Text(
            "Recent Alexa voice requests — what Alexa heard vs what played.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) LoadingBox(Modifier.weight(1f)) else {
            BockLazyColumn(Modifier.weight(1f)) {
                items(items) { row ->
                    ListItem(
                        headlineContent = { Text(row.heard ?: "—") },
                        supportingContent = {
                            Text("Found: ${row.found ?: "—"} · ${row.timestamp ?: ""}")
                        },
                        trailingContent = {
                            Text(
                                if (row.success) "OK" else "Miss",
                                color = if (row.success) Color(0xFF1DB954) else MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
            PaginationBar(page, ((total + 24) / 25).coerceAtLeast(1)) { page = it }
        }
    }
}
