package org.thoughtcrime.securesms.media

import android.content.Context
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.FullComposeScreenLockActivity
import javax.inject.Inject

@AndroidEntryPoint
class MediaOverviewActivity : FullComposeScreenLockActivity() {
    @Composable
    override fun ComposeContent() {
        val viewModel = hiltViewModel<MediaOverviewViewModel, MediaOverviewViewModel.Factory> { factory ->
            factory.create(
                IntentCompat.getParcelableExtra(intent, EXTRA_ADDRESS, Address::class.java)!!
            )
        }

        MediaOverviewScreen(viewModel, onClose = this::finish)
    }

    companion object {
        private const val EXTRA_ADDRESS = "address"

        @JvmStatic
        fun createIntent(context: Context, address: Address): Intent {
            return Intent(context, MediaOverviewActivity::class.java).apply {
                putExtra(EXTRA_ADDRESS, address)
            }
        }
    }
}