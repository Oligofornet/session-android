package org.thoughtcrime.securesms.onboarding.loadaccount

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.flow.Flow
import network.loki.messenger.R
import org.thoughtcrime.securesms.onboarding.ui.ContinuePrimaryOutlineButton
import org.thoughtcrime.securesms.ui.components.QRScannerScreen
import org.thoughtcrime.securesms.ui.components.SessionOutlinedTextField
import org.thoughtcrime.securesms.ui.components.SessionTabRow
import org.thoughtcrime.securesms.ui.contentDescription
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme

private val TITLES = listOf(R.string.sessionRecoveryPassword, R.string.qrScan)

@Composable
internal fun LoadAccountScreen(
    state: State,
    qrErrors: Flow<String>,
    onChange: (String) -> Unit = {},
    onContinue: () -> Unit = {},
    onScan: (String) -> Unit = {}
) {
    val pagerState = rememberPagerState { TITLES.size }

    Scaffold { paddingValues ->
        Column {
            SessionTabRow(pagerState, TITLES)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (TITLES[page]) {
                    R.string.sessionRecoveryPassword -> RecoveryPassword(
                        modifier = Modifier.padding(bottom = paddingValues.calculateBottomPadding())
                            .consumeWindowInsets(paddingValues),
                        state = state,
                        onChange = onChange,
                        onContinue = onContinue
                    )

                    R.string.qrScan -> QRScannerScreen(qrErrors, onScan = onScan)
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreviewRecoveryPassword() {
    PreviewTheme {
        RecoveryPassword(state = State())
    }
}

@Composable
private fun RecoveryPassword(
    state: State,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit = {},
    onContinue: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.weight(1f))
        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))

        Column(
            modifier = Modifier.padding(horizontal = LocalDimensions.current.mediumSpacing)
        ) {
            Row {
                Text(
                    text = stringResource(R.string.sessionRecoveryPassword),
                    style = LocalType.current.h4
                )
                Spacer(Modifier.width(LocalDimensions.current.xxsSpacing))
                Icon(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    painter = painterResource(id = R.drawable.ic_recovery_password_custom),
                    contentDescription = null,
                )
            }
            Spacer(Modifier.height(LocalDimensions.current.smallSpacing))
            Text(
                stringResource(R.string.recoveryPasswordRestoreDescription),
                style = LocalType.current.base
            )
            Spacer(Modifier.height(LocalDimensions.current.spacing))

            SessionOutlinedTextField(
                text = state.recoveryPhrase,
                modifier = Modifier.fillMaxWidth()
                    .qaTag(R.string.AccessibilityId_recoveryPasswordEnter),
                placeholder = stringResource(R.string.recoveryPasswordEnter),
                onChange = onChange,
                onContinue = onContinue,
                error = state.error,
                isTextErrorColor = state.isTextErrorColor
            )
        }

        Spacer(modifier = Modifier.height(LocalDimensions.current.smallSpacing))
        Spacer(Modifier.weight(2f))

        ContinuePrimaryOutlineButton(modifier = Modifier.align(Alignment.CenterHorizontally), onContinue)
    }
}
