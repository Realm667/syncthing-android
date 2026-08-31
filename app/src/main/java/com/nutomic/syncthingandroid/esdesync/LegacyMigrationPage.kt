package com.nutomic.syncthingandroid.esdesync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.onboarding.OnboardingIcon
import com.nutomic.syncthingandroid.onboarding.OnboardingScaffold
import com.nutomic.syncthingandroid.onboarding.OnboardingUiState

@Composable
fun LegacyMigrationPage(
    uiState: OnboardingUiState,
    pageIndex: Int,
    requestTvFocus: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onOpenLegacyApp: () -> Unit,
    onOpenLegacyAppDetails: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val actionFocusRequester = remember { FocusRequester() }

    OnboardingScaffold(
        icon = OnboardingIcon.Vector(Icons.Outlined.Restore),
        title = stringResource(R.string.legacy_migration_title),
        description = stringResource(R.string.legacy_migration_description),
        pageIndex = pageIndex,
        pageCount = uiState.pages.size,
        nextLabel = stringResource(R.string.legacy_migration_skip),
        requestTvFocus = requestTvFocus,
        onBack = onBack,
        onNext = onSkip,
        actionFocusRequester = actionFocusRequester,
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenLegacyApp,
                    modifier = androidx.compose.ui.Modifier.focusRequester(actionFocusRequester),
                ) {
                    Text(stringResource(R.string.legacy_migration_open_original))
                }
                OutlinedButton(onClick = onOpenLegacyAppDetails) {
                    Text(stringResource(R.string.legacy_migration_stop_original))
                }
                Button(onClick = onOpenImport) {
                    Text(stringResource(R.string.legacy_migration_import))
                }
            }
        },
    )
}
