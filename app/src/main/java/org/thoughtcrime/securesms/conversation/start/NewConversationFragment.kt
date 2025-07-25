package org.thoughtcrime.securesms.conversation.start

import android.app.Dialog
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import network.loki.messenger.R
import network.loki.messenger.databinding.FragmentNewConversationBinding
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.utilities.Address
import org.thoughtcrime.securesms.conversation.start.home.StartConversationHomeFragment
import org.thoughtcrime.securesms.conversation.start.invitefriend.InviteFriendFragment
import org.thoughtcrime.securesms.conversation.start.newmessage.NewMessageFragment
import org.thoughtcrime.securesms.conversation.v2.ConversationActivityV2
import org.thoughtcrime.securesms.groups.CreateGroupFragment
import org.thoughtcrime.securesms.groups.JoinCommunityFragment
import javax.inject.Inject

@AndroidEntryPoint
class StartConversationFragment : BottomSheetDialogFragment(), StartConversationDelegate {

    companion object{
        const val PEEK_RATIO = 0.94f
    }

    @Inject
    lateinit var deprecationManager: LegacyGroupDeprecationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        replaceFragment(
            fragment = StartConversationHomeFragment().also { it.delegate.value = this },
            fragmentKey = StartConversationHomeFragment::class.java.simpleName
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_new_conversation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val emptySpace = (Resources.getSystem().displayMetrics.heightPixels * (1 - PEEK_RATIO)).toInt()

        val binding = FragmentNewConversationBinding.bind(view)

        binding.newConversationFragmentContainer.verticalSpace = emptySpace

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            // From Android 15, given display metrics now include the window insets so we'll have to account for that space
            binding.newConversationFragmentContainer.verticalSpace = emptySpace + insets.getInsets(WindowInsetsCompat.Type.systemBars()).top

            WindowInsetsCompat.CONSUMED // We don't actually need our view to have any insets as we are in a dialog
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext()).apply {
            behavior.apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }

            // Set transparent navigation bar on older android version otherwise it colors the  navbar
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                window?.apply {
                    navigationBarColor = android.graphics.Color.TRANSPARENT
                }
            }
        }


    override fun onNewMessageSelected() {
        replaceFragment(NewMessageFragment().also { it.delegate = this })
    }

    override fun onCreateGroupSelected() {
        val fragment = CreateGroupFragment()

        replaceFragment(fragment)
    }

    override fun onJoinCommunitySelected() {
        replaceFragment(JoinCommunityFragment().also { it.delegate = this })
    }

    override fun onContactSelected(address: String) {
        val intent = Intent(requireContext(), ConversationActivityV2::class.java)
        intent.putExtra(ConversationActivityV2.ADDRESS, Address.fromSerialized(address))
        requireContext().startActivity(intent)
        requireActivity().overridePendingTransition(R.anim.slide_from_bottom, R.anim.fade_scale_out)
    }

    override fun onDialogBackPressed() {
        childFragmentManager.popBackStack()
    }

    override fun onInviteFriend() {
        replaceFragment(InviteFriendFragment().also { it.delegate = this })
    }

    override fun onDialogClosePressed() {
        dismiss()
    }

    private fun replaceFragment(fragment: Fragment, fragmentKey: String? = null) {
        childFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_from_right,
                R.anim.slide_to_left,
                R.anim.slide_from_left,
                R.anim.slide_to_right
            )
            replace(R.id.new_conversation_fragment_container, fragment)
            addToBackStack(fragmentKey)
        }
    }
}