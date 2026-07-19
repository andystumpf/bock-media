package com.bockmedia.console.ui.about

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.BuildConfig
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.bockVerticalScroll
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun AboutScreen(repository: BockMediaRepository) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var downloadUrl by remember { mutableStateOf<String?>(null) }
    var githubPublic by remember { mutableStateOf(AboutLinks.GITHUB_PUBLIC) }
    var githubPrivate by remember { mutableStateOf(AboutLinks.GITHUB_PRIVATE) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.loadConfigJson() }
            .onSuccess { config -> applyAboutConfig(config, onDownload = { downloadUrl = it }, onRepos = { pub, priv ->
                githubPublic = pub
                githubPrivate = priv
            }) }
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .testTag(BockTestTags.ABOUT_BODY)
            .bockVerticalScroll()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(28.dp))
            Column {
                Text("Bock Media Console", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Android app", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (loading) {
            LoadingBox(Modifier.height(120.dp))
        } else {
            AboutCard(title = "Version") {
                Text(
                    BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            AboutCard(title = "Download apps") {
                Text(
                    "Install the Android or iPhone app from your server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AboutLinkRow(
                    label = "Mobile app downloads",
                    icon = Icons.Default.Download,
                    enabled = downloadUrl != null,
                    onClick = {
                        downloadUrl?.let { url ->
                            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                        }
                    },
                )
            }

            AboutCard(title = "Source code") {
                AboutLinkRow(
                    label = "Public repository",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(githubPublic))
                    },
                )
                AboutLinkRow(
                    label = "Private repository",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = {
                        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(githubPrivate))
                    },
                )
            }
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun AboutLinkRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun applyAboutConfig(
    config: JsonObject,
    onDownload: (String) -> Unit,
    onRepos: (String, String) -> Unit,
) {
    val publicUrl = config["publicUrl"]?.jsonPrimitive?.content?.trim()?.trimEnd('/').orEmpty()
    if (publicUrl.isNotBlank()) {
        onDownload("$publicUrl/app")
    }
    val about = config["appAbout"] as? JsonObject
    val pub = about?.get("githubPublic")?.jsonPrimitive?.content?.trim().orEmpty()
    val priv = about?.get("githubPrivate")?.jsonPrimitive?.content?.trim().orEmpty()
    onRepos(
        pub.ifBlank { AboutLinks.GITHUB_PUBLIC },
        priv.ifBlank { AboutLinks.GITHUB_PRIVATE },
    )
}

object AboutLinks {
    const val GITHUB_PUBLIC = "https://github.com/andystumpf/bock-media"
    const val GITHUB_PRIVATE = "https://github.com/andystumpf/bock-media"
}
