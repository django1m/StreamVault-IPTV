package com.streamvault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.components.osd.OsdRow
import com.streamvault.app.ui.components.osd.OsdRowCount
import com.streamvault.app.ui.components.osd.OsdTile
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.test.assertAgainstGolden
import com.streamvault.app.ui.theme.StreamVaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The contract the whole restyle rests on: what a row and a tile look like at rest,
 * focused, and active.
 *
 * None of the nine pre-existing goldens capture a focused state, which is the one thing
 * that matters here — the entire language lives in the rest → focus transition. These five
 * are the regression net for lots B, C and D.
 *
 * `osd_row_active` and `osd_tile_focused` are the two that catch real breakage:
 * the first fails if a background creeps back onto the ACTIVE state (making it
 * indistinguishable from focus), the second fails if the tile outline ends up drawn
 * beneath the artwork instead of over it.
 */
@RunWith(AndroidJUnit4::class)
class OsdFocusGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun captureRow(
        goldenName: String,
        focused: Boolean,
        active: Boolean
    ) {
        composeRule.setContent {
            StreamVaultTheme {
                val requester = remember { FocusRequester() }
                Box(
                    modifier = Modifier
                        .background(AppColors.Canvas)
                        .width(520.dp)
                        .padding(12.dp)
                        .testTag("golden")
                ) {
                    OsdRow(
                        title = "CA| ABC EAST HD",
                        subtitle = "General Hospital",
                        number = "105",
                        active = active,
                        onClick = {},
                        modifier = Modifier.focusRequester(requester),
                        trailing = { OsdRowCount(value = "42", focused = focused) }
                    )
                }
                if (focused) {
                    LaunchedEffect(Unit) { requester.requestFocus() }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("golden").assertAgainstGolden(goldenName)
    }

    @Test
    fun osdRow_rest_matchesGolden() = captureRow("osd_row_rest", focused = false, active = false)

    @Test
    fun osdRow_focused_matchesGolden() = captureRow("osd_row_focused", focused = true, active = false)

    /** Active must tint the TEXT and leave the background alone. */
    @Test
    fun osdRow_active_matchesGolden() = captureRow("osd_row_active", focused = false, active = true)

    private fun captureTile(goldenName: String, focused: Boolean) {
        composeRule.setContent {
            StreamVaultTheme {
                val requester = remember { FocusRequester() }
                Box(
                    modifier = Modifier
                        .background(AppColors.Canvas)
                        .padding(16.dp)
                        .testTag("golden")
                ) {
                    OsdTile(
                        onClick = {},
                        modifier = Modifier
                            .width(136.dp)
                            .height(204.dp)
                            .focusRequester(requester)
                    ) {
                        // A deliberately bright fill: the outline has to survive artwork
                        // that is lighter than it is.
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFF7A18)))
                    }
                }
                if (focused) {
                    LaunchedEffect(Unit) { requester.requestFocus() }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("golden").assertAgainstGolden(goldenName)
    }

    @Test
    fun osdTile_rest_matchesGolden() = captureTile("osd_tile_rest", focused = false)

    @Test
    fun osdTile_focused_matchesGolden() = captureTile("osd_tile_focused", focused = true)
}
