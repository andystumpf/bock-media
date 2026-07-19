package com.bockmedia.console.ui.nowplaying

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bockmedia.console.ui.components.BockProgressIndicator

private const val YT_EMBED_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

/** YouTube requires a valid Referer / origin in WebViews (errors 150/152/153 otherwise). */
private fun youtubeEmbedOrigin(packageName: String): String = "https://$packageName"

private fun youtubeEmbedUrl(videoId: String, origin: String): String =
    "https://www.youtube.com/embed/$videoId?" +
        "autoplay=1&mute=1&controls=0&disablekb=1&fs=0&iv_load_policy=3" +
        "&loop=1&playlist=$videoId&modestbranding=1&playsinline=1&rel=0" +
        "&origin=${java.net.URLEncoder.encode(origin, Charsets.UTF_8.name())}"

/** Cover-style iframe HTML — fills the screen at 16:9, cropping edges like ExoPlayer zoom. */
private fun youtubeCoverEmbedHtml(videoId: String, origin: String): String {
    val src = youtubeEmbedUrl(videoId, origin)
    return """
        <!DOCTYPE html><html><head>
        <meta name="referrer" content="strict-origin-when-cross-origin">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body{margin:0;padding:0;background:#000;height:100%;overflow:hidden}
          .frame{position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);
                 height:100vh;width:177.78vh;min-width:100vw;min-height:56.25vw}
          iframe{width:100%;height:100%;border:0;pointer-events:none}
        </style></head><body>
        <div class="frame">
          <iframe src="$src" referrerpolicy="strict-origin-when-cross-origin"
            allow="autoplay; encrypted-media; picture-in-picture"></iframe>
        </div></body></html>
        """.trimIndent()
}

/**
 * Muted YouTube embed — fallback when the NAS cannot extract a direct stream.
 * Cover-scales to fill the screen while keeping 16:9 (same visual as ExoPlayer zoom).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MusicVideoEmbedPlayer(
    videoId: String,
    artUrl: String?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val embedOrigin = remember { youtubeEmbedOrigin(context.packageName) }
    val embedHtml = remember(videoId, embedOrigin) { youtubeCoverEmbedHtml(videoId, embedOrigin) }

    val fallbackThumb = remember(videoId) { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
    val backdropUrl = artUrl?.takeIf { it.isNotBlank() } ?: fallbackThumb
    var pageReady by remember(videoId) { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Box(modifier.fillMaxSize().clipToBounds()) {
        MusicVideoViewport(backdropUrl = backdropUrl) { frameModifier ->
            key(videoId) {
                AndroidView(
                    modifier = frameModifier.alpha(if (pageReady) 1f else 0f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.userAgentString = YT_EMBED_UA
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            setOnTouchListener { _, _ -> true }
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    pageReady = true
                                }
                            }
                            loadDataWithBaseURL(
                                "$embedOrigin/",
                                embedHtml,
                                "text/html",
                                "utf-8",
                                null,
                            )
                            webViewRef = this
                        }
                    },
                    update = { webView ->
                        webViewRef = webView
                        if (playing) {
                            webView.onResume()
                            webView.resumeTimers()
                        } else {
                            webView.onPause()
                            webView.pauseTimers()
                        }
                    },
                    onRelease = {
                        webViewRef = null
                        it.onPause()
                        it.loadUrl("about:blank")
                        it.destroy()
                    },
                )
            }
        }

        LaunchedEffect(playing, webViewRef) {
            val webView = webViewRef ?: return@LaunchedEffect
            if (playing) {
                webView.onResume()
                webView.resumeTimers()
            } else {
                webView.onPause()
                webView.pauseTimers()
            }
        }

        if (!pageReady) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BockProgressIndicator(size = 32.dp)
            }
        }
    }
}
