package org.thoughtcrime.securesms.home

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import network.loki.messenger.R
import network.loki.messenger.databinding.ViewConversationBinding
import org.session.libsession.utilities.ThemeUtil
import org.session.libsession.utilities.recipients.Recipient
import org.thoughtcrime.securesms.conversation.v2.utilities.MentionUtilities.highlightMentions
import org.thoughtcrime.securesms.database.RecipientDatabase.NOTIFY_TYPE_ALL
import org.thoughtcrime.securesms.database.RecipientDatabase.NOTIFY_TYPE_NONE
import org.thoughtcrime.securesms.database.model.ThreadRecord
import org.thoughtcrime.securesms.dependencies.ConfigFactory
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.getAccentColor
import org.thoughtcrime.securesms.util.getConversationUnread

@AndroidEntryPoint
class ConversationView : LinearLayout {

    @Inject lateinit var configFactory: ConfigFactory
    @Inject lateinit var dateUtils: DateUtils

    private val binding: ViewConversationBinding by lazy { ViewConversationBinding.bind(this) }
    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    var thread: ThreadRecord? = null

    // region Lifecycle
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onFinishInflate() {
        super.onFinishInflate()
        layoutParams = RecyclerView.LayoutParams(screenWidth, RecyclerView.LayoutParams.WRAP_CONTENT)
    }
    // endregion

    // region Updating
    fun bind(thread: ThreadRecord, isTyping: Boolean) {
        this.thread = thread
        if (thread.isPinned) {
            binding.iconPinned.isVisible = true
        } else {
            binding.iconPinned.isVisible = false
        }

        binding.root.background = if (thread.unreadCount > 0) {
            ContextCompat.getDrawable(context, R.drawable.conversation_unread_background)
        } else {
            ContextCompat.getDrawable(context, R.drawable.conversation_view_background)
        }

        val unreadCount = thread.unreadCount
        if (thread.recipient.isBlocked) {
            binding.accentView.setBackgroundColor(ThemeUtil.getThemedColor(context, R.attr.danger))
            binding.accentView.visibility = View.VISIBLE
        } else {
            val accentColor = context.getAccentColor()
            val background = ColorDrawable(accentColor)
            binding.accentView.background = background
            // Using thread.isRead we can determine if the last message was our own, and display it as 'read' even though previous messages may not be
            // This would also not trigger the disappearing message timer which may or may not be desirable
            binding.accentView.visibility = if (unreadCount > 0 && !thread.isRead) View.VISIBLE else View.INVISIBLE
        }

        val formattedUnreadCount = if (unreadCount == 0) {
            null
        } else {
            if (unreadCount < 10000) unreadCount.toString() else "9999+"
        }

        binding.unreadCountTextView.text = formattedUnreadCount
        val textSize = if (unreadCount < 1000) 12.0f else 10.0f
        binding.unreadCountTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize)
        binding.unreadCountIndicator.isVisible = (unreadCount != 0 && !thread.isRead) || (configFactory.withUserConfigs { it.convoInfoVolatile.getConversationUnread(thread) })
        binding.unreadMentionTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSize)
        binding.unreadMentionIndicator.isVisible = (thread.unreadMentionCount != 0 && thread.recipient.address.isGroupOrCommunity)

        val senderDisplayName = getTitle(thread.recipient)
        binding.conversationViewDisplayNameTextView.text = senderDisplayName
        binding.timestampTextView.text = thread.date.takeIf { it != 0L }?.let { dateUtils.getDisplayFormattedTimeSpanString(
            Locale.getDefault(),
            it
        ) }

        val recipient = thread.recipient
        binding.muteIndicatorImageView.isVisible = recipient.isMuted || recipient.notifyType != NOTIFY_TYPE_ALL

        val drawableRes = if (recipient.isMuted || recipient.notifyType == NOTIFY_TYPE_NONE) {
            R.drawable.ic_volume_off
        } else {
            R.drawable.ic_at_sign
        }

        binding.muteIndicatorImageView.setImageResource(drawableRes)

        binding.snippetTextView.text = highlightMentions(
            text = thread.getDisplayBody(context),
            formatOnly = true, // no styling here, only text formatting
            threadID = thread.threadId,
            context = context
        )

        binding.snippetTextView.typeface = if (unreadCount > 0 && !thread.isRead) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        binding.snippetTextView.visibility = if (isTyping) View.GONE else View.VISIBLE
        if (isTyping) {
            binding.typingIndicatorView.root.startAnimation()
        } else {
            binding.typingIndicatorView.root.stopAnimation()
        }

        binding.typingIndicatorView.root.visibility = if (isTyping) View.VISIBLE else View.GONE
        binding.statusIndicatorImageView.visibility = View.VISIBLE
        binding.statusIndicatorImageView.imageTintList = ColorStateList.valueOf(ThemeUtil.getThemedColor(context, android.R.attr.textColorTertiary)) // tertiary in the current xml styling is actually what figma uses as secondary text color...

        when {
            !thread.isOutgoing || thread.lastMessage == null -> binding.statusIndicatorImageView.visibility = View.GONE

            thread.isFailed -> {
                val drawable = ContextCompat.getDrawable(context, R.drawable.ic_triangle_alert)?.mutate()
                binding.statusIndicatorImageView.setImageDrawable(drawable)
                binding.statusIndicatorImageView.imageTintList = ColorStateList.valueOf(ThemeUtil.getThemedColor(context, R.attr.danger))
            }
            thread.isPending -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_circle_dots_custom)
            thread.isRead -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_eye)
            else -> binding.statusIndicatorImageView.setImageResource(R.drawable.ic_circle_check)
        }

        binding.profilePictureView.update(thread.recipient)
    }

    fun recycle() { binding.profilePictureView.recycle() }

    private fun getTitle(recipient: Recipient): String = when {
        recipient.isLocalNumber -> context.getString(R.string.noteToSelf)
        else -> recipient.name // Internally uses the Contact API
    }
    // endregion
}
