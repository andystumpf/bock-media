package com.bockmedia.console.ui.components

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun bockFlingBehavior() = ScrollableDefaults.flingBehavior()

@Composable
fun rememberBockScrollState() = rememberScrollState()

@Composable
fun Modifier.bockVerticalScroll(state: androidx.compose.foundation.ScrollState = rememberBockScrollState()): Modifier =
    verticalScroll(state, flingBehavior = bockFlingBehavior())

@Composable
fun BockLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    reverseLayout: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        reverseLayout = reverseLayout,
        flingBehavior = bockFlingBehavior(),
        content = content,
    )
}
