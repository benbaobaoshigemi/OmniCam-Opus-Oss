package local.jiege.ui

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import local.jiege.settings.LaunchModes
import local.jiege.settings.ModuleSettings

/** Sub-page: choose which mode the hardware quick launch key opens. */
@Composable
internal fun KeyLaunchModeScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    store: SettingsStore,
    listState: LazyListState = rememberLazyListState(),
) {
    var selected by remember { mutableStateOf(store.getString(ModuleSettings.KEY_LAUNCH_MODE)) }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "modes") {
            UiGroup(title = "快启键打开的模式") {
                LaunchModes.NAMES.forEachIndexed { index, name ->
                    CouiRadioRow(
                        title = LaunchModes.LABELS[index],
                        selected = name == selected,
                        onClick = {
                            selected = name
                            store.setString(ModuleSettings.KEY_LAUNCH_MODE, name)
                        },
                    )
                    if (index < LaunchModes.NAMES.lastIndex) UiDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
