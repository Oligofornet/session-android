package org.session.libsession.messaging.sending_receiving

import android.text.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import network.loki.messenger.R
import network.loki.messenger.libsession_util.ED25519
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import network.loki.messenger.libsession_util.util.ExpiryMode
import org.session.libsession.avatars.AvatarHelper
import org.session.libsession.database.userAuth
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.groups.LegacyGroupDeprecationManager
import org.session.libsession.messaging.jobs.AttachmentDownloadJob
import org.session.libsession.messaging.jobs.BackgroundGroupAddJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.ExpirationConfiguration
import org.session.libsession.messaging.messages.ExpirationConfiguration.Companion.isNewConfigEnabled
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.messages.control.ConfigurationMessage
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.GroupUpdated
import org.session.libsession.messaging.messages.control.LegacyGroupControlMessage
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.messages.control.TypingIndicator
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.Attachment
import org.session.libsession.messaging.messages.visible.Reaction
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.attachments.PointerAttachment
import org.session.libsession.messaging.sending_receiving.data_extraction.DataExtractionNotificationInfoMessage
import org.session.libsession.messaging.sending_receiving.link_preview.LinkPreview
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel
import org.session.libsession.messaging.utilities.MessageAuthentication.buildDeleteMemberContentSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildGroupInviteSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildInfoChangeSignature
import org.session.libsession.messaging.utilities.MessageAuthentication.buildMemberChangeSignature
import org.session.libsession.messaging.utilities.WebRtcUtils
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.GroupRecord
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.GroupUtil.doubleEncodeGroupID
import org.session.libsession.utilities.ProfileKeyUtil
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsession.utilities.recipients.MessageType
import org.session.libsession.utilities.recipients.Recipient
import org.session.libsession.utilities.recipients.getType
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.protos.SignalServiceProtos.SharedConfigMessage
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Base64
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.utilities.removingIdPrefixIfNeeded
import org.session.libsignal.utilities.toHexString
import org.thoughtcrime.securesms.database.model.MessageId
import java.security.MessageDigest
import java.security.SignatureException
import java.util.LinkedList
import kotlin.math.min

internal fun MessageReceiver.isBlocked(publicKey: String): Boolean {
    val context = MessagingModuleConfiguration.shared.context
    val recipient = Recipient.from(context, Address.fromSerialized(publicKey), false)
    return recipient.isBlocked
}

fun MessageReceiver.handle(message: Message, proto: SignalServiceProtos.Content, threadId: Long, openGroupID: String?, groupv2Id: AccountId?) {
    // Do nothing if the message was outdated
    if (MessageReceiver.messageIsOutdated(message, threadId, openGroupID)) { return }

    when (message) {
        is ReadReceipt -> handleReadReceipt(message)
        is TypingIndicator -> handleTypingIndicator(message)
        is LegacyGroupControlMessage -> handleLegacyGroupControlMessage(message)
        is GroupUpdated -> handleGroupUpdated(message, groupv2Id)
        is ExpirationTimerUpdate -> {
            // For groupsv2, there are dedicated mechanisms for handling expiration timers, and
            // we want to avoid the 1-to-1 message format which is unauthenticated in a group settings.
            if (groupv2Id != null) {
                Log.d("MessageReceiver", "Ignoring expiration timer update for closed group")
            } // also ignore it for communities since they do not support disappearing messages
            else if (openGroupID != null) {
                Log.d("MessageReceiver", "Ignoring expiration timer update for communities")
            } else {
                handleExpirationTimerUpdate(message)
            }
        }
        is DataExtractionNotification -> handleDataExtractionNotification(message)
        is ConfigurationMessage -> handleConfigurationMessage(message)
        is UnsendRequest -> handleUnsendRequest(message)
        is MessageRequestResponse -> handleMessageRequestResponse(message)
        is VisibleMessage -> handleVisibleMessage(
            message, proto, openGroupID, threadId,
            runThreadUpdate = true,
            runProfileUpdate = true
        )
        is CallMessage -> handleCallMessage(message)
    }
}

fun MessageReceiver.messageIsOutdated(message: Message, threadId: Long, openGroupID: String?): Boolean {
    when (message) {
        is ReadReceipt -> return false // No visible artifact created so better to keep for more reliable read states
        is UnsendRequest -> return false // We should always process the removal of messages just in case
    }

    // Determine the state of the conversation and the validity of the message
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val threadRecipient = storage.getRecipientForThread(threadId)
    val conversationVisibleInConfig = storage.conversationInConfig(
        if (message.groupPublicKey == null) threadRecipient?.address?.toString() else null,
        message.groupPublicKey,
        openGroupID,
        true
    )
    val canPerformChange = storage.canPerformConfigChange(
        if (threadRecipient?.address?.toString() == userPublicKey) SharedConfigMessage.Kind.USER_PROFILE.name else SharedConfigMessage.Kind.CONTACTS.name,
        userPublicKey,
        message.sentTimestamp!!
    )

    // If the thread is visible or the message was sent more recently than the last config message (minus
    // buffer period) then we should process the message, if not then the message is outdated
    return (!conversationVisibleInConfig && !canPerformChange)
}

// region Control Messages
private fun MessageReceiver.handleReadReceipt(message: ReadReceipt) {
    val context = MessagingModuleConfiguration.shared.context
    SSKEnvironment.shared.readReceiptManager.processReadReceipts(context, message.sender!!, message.timestamps!!, message.receivedTimestamp!!)
}

private fun MessageReceiver.handleCallMessage(message: CallMessage) {
    // TODO: refactor this out to persistence, just to help debug the flow and send/receive in synchronous testing
    WebRtcUtils.SIGNAL_QUEUE.trySend(message)
}

private fun MessageReceiver.handleTypingIndicator(message: TypingIndicator) {
    when (message.kind!!) {
        TypingIndicator.Kind.STARTED -> showTypingIndicatorIfNeeded(message.sender!!)
        TypingIndicator.Kind.STOPPED -> hideTypingIndicatorIfNeeded(message.sender!!)
    }
}

fun MessageReceiver.showTypingIndicatorIfNeeded(senderPublicKey: String) {
    val context = MessagingModuleConfiguration.shared.context
    val address = Address.fromSerialized(senderPublicKey)
    val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address) ?: return
    SSKEnvironment.shared.typingIndicators.didReceiveTypingStartedMessage(context, threadID, address, 1)
}

fun MessageReceiver.hideTypingIndicatorIfNeeded(senderPublicKey: String) {
    val context = MessagingModuleConfiguration.shared.context
    val address = Address.fromSerialized(senderPublicKey)
    val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address) ?: return
    SSKEnvironment.shared.typingIndicators.didReceiveTypingStoppedMessage(context, threadID, address, 1, false)
}

fun MessageReceiver.cancelTypingIndicatorsIfNeeded(senderPublicKey: String) {
    val context = MessagingModuleConfiguration.shared.context
    val address = Address.fromSerialized(senderPublicKey)
    val threadID = MessagingModuleConfiguration.shared.storage.getThreadId(address) ?: return
    SSKEnvironment.shared.typingIndicators.didReceiveIncomingMessage(context, threadID, address, 1)
}

private fun MessageReceiver.handleExpirationTimerUpdate(message: ExpirationTimerUpdate) {
    SSKEnvironment.shared.messageExpirationManager.insertExpirationTimerMessage(message)

    val isLegacyGroup = message.groupPublicKey != null && message.groupPublicKey?.startsWith(IdPrefix.GROUP.value) == false

    if (isNewConfigEnabled && !isLegacyGroup) return

    val module = MessagingModuleConfiguration.shared
    try {
        val threadId = Address.fromSerialized(message.groupPublicKey?.let(::doubleEncodeGroupID) ?: message.sender!!)
            .let(module.storage::getOrCreateThreadIdFor)

        module.storage.setExpirationConfiguration(
            ExpirationConfiguration(
                threadId,
                message.expiryMode,
                message.sentTimestamp!!
            )
        )
    } catch (e: Exception) {
        Log.e("Loki", "Failed to update expiration configuration.")
    }
}

private fun MessageReceiver.handleDataExtractionNotification(message: DataExtractionNotification) {
    // We don't handle data extraction messages for groups (they shouldn't be sent, but just in case we filter them here too)
    if (message.groupPublicKey != null) return
    val storage = MessagingModuleConfiguration.shared.storage
    val senderPublicKey = message.sender!!

    val notification: DataExtractionNotificationInfoMessage = when(message.kind) {
        is DataExtractionNotification.Kind.Screenshot -> DataExtractionNotificationInfoMessage(DataExtractionNotificationInfoMessage.Kind.SCREENSHOT)
        is DataExtractionNotification.Kind.MediaSaved -> DataExtractionNotificationInfoMessage(DataExtractionNotificationInfoMessage.Kind.MEDIA_SAVED)
        else -> return
    }
    storage.insertDataExtractionNotificationMessage(senderPublicKey, notification, message.sentTimestamp!!)
}

private fun handleConfigurationMessage(message: ConfigurationMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    if (TextSecurePreferences.getConfigurationMessageSynced(context)
        && !TextSecurePreferences.shouldUpdateProfile(context, message.sentTimestamp!!)) return
    val userPublicKey = storage.getUserPublicKey()
    if (userPublicKey == null || message.sender != storage.getUserPublicKey()) return

    val firstTimeSync = !TextSecurePreferences.getConfigurationMessageSynced(context)

    TextSecurePreferences.setConfigurationMessageSynced(context, true)
    TextSecurePreferences.setLastProfileUpdateTime(context, message.sentTimestamp!!)

    TextSecurePreferences.setHasLegacyConfig(context, true)
    if (!firstTimeSync) return

    val allClosedGroupPublicKeys = storage.getAllLegacyGroupPublicKeys()
    for (closedGroup in message.closedGroups) {
        if (allClosedGroupPublicKeys.contains(closedGroup.publicKey)) {
            // just handle the closed group encryption key pairs to avoid sync'd devices getting out of sync
            storage.addClosedGroupEncryptionKeyPair(closedGroup.encryptionKeyPair!!, closedGroup.publicKey, message.sentTimestamp!!)
        } else {
            // only handle new closed group if it's first time sync
            handleNewLegacyGroup(message.sender!!, message.sentTimestamp!!, closedGroup.publicKey, closedGroup.name,
                    closedGroup.encryptionKeyPair!!, closedGroup.members, closedGroup.admins, message.sentTimestamp!!, -1)
        }
    }
    val allV2OpenGroups = storage.getAllOpenGroups().map { it.value.joinURL }
    for (openGroup in message.openGroups.map {
        it.replace(OpenGroupApi.legacyDefaultServer, OpenGroupApi.defaultServer)
            .replace(OpenGroupApi.httpDefaultServer, OpenGroupApi.defaultServer)
    }) {
        if (allV2OpenGroups.contains(openGroup)) continue
        Log.d("OpenGroup", "All open groups doesn't contain open group")
        if (!storage.hasBackgroundGroupAddJob(openGroup)) {
            Log.d("OpenGroup", "Doesn't contain background job for open group, adding")
            JobQueue.shared.add(BackgroundGroupAddJob(openGroup))
        }
    }
    val profileManager = SSKEnvironment.shared.profileManager
    val recipient = Recipient.from(context, Address.fromSerialized(userPublicKey), false)
    if (message.displayName.isNotEmpty()) {
        TextSecurePreferences.setProfileName(context, message.displayName)
        profileManager.setName(context, recipient, message.displayName)
    }
    if (message.profileKey.isNotEmpty() && !message.profilePicture.isNullOrEmpty()
        && TextSecurePreferences.getProfilePictureURL(context) != message.profilePicture) {
        val profileKey = Base64.encodeBytes(message.profileKey)
        ProfileKeyUtil.setEncodedProfileKey(context, profileKey)
        profileManager.setProfilePicture(context, recipient, message.profilePicture, message.profileKey)
    }
    storage.addContacts(message.contacts)
}

fun MessageReceiver.handleUnsendRequest(message: UnsendRequest): MessageId? {
    val userPublicKey = MessagingModuleConfiguration.shared.storage.getUserPublicKey()
    val storage = MessagingModuleConfiguration.shared.storage
    val userAuth = storage.userAuth ?: return null
    val isLegacyGroupAdmin: Boolean = message.groupPublicKey?.let { key ->
        var admin = false
        val groupID = doubleEncodeGroupID(key)
        val group = storage.getGroup(groupID)
        if(group != null) {
            admin = group.admins.map { it.toString() }.contains(message.sender)
        }
        admin
    } ?: false

    // First we need to determine the validity of the UnsendRequest
    // It is valid if:
    val requestIsValid = message.sender == message.author || //  the sender is the author of the message
            message.author == userPublicKey || //  the sender is the current user
            isLegacyGroupAdmin // sender is an admin of legacy group

    if (!requestIsValid) { return null }

    val context = MessagingModuleConfiguration.shared.context
    val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
    val timestamp = message.timestamp ?: return null
    val author = message.author ?: return null
    val messageToDelete = storage.getMessageBy(timestamp, author) ?: return null
    val messageIdToDelete = messageToDelete.messageId
    val messageType = messageToDelete.individualRecipient?.getType()

    // send a /delete rquest for 1on1 messages
    if (messageType == MessageType.ONE_ON_ONE) {
        messageDataProvider.getServerHashForMessage(messageIdToDelete)?.let { serverHash ->
            GlobalScope.launch(Dispatchers.IO) { // using GlobalScope as we are slowly migrating to coroutines but we can't migrate everything at once
                try {
                    SnodeAPI.deleteMessage(author, userAuth, listOf(serverHash))
                } catch (e: Exception) {
                    Log.e("Loki", "Failed to delete message", e)
                }
            }
        }
    }

    // the message is marked as deleted locally
    // except for 'note to self' where the message is completely deleted
    if (messageType == MessageType.NOTE_TO_SELF){
        messageDataProvider.deleteMessage(messageIdToDelete)
    } else {
        messageDataProvider.markMessageAsDeleted(
            messageIdToDelete,
            displayedMessage = context.getString(R.string.deleteMessageDeletedGlobally)
        )
    }

    // delete reactions
    storage.deleteReactions(messageToDelete.messageId)

    // update notification
    if (!messageToDelete.isOutgoing) {
        SSKEnvironment.shared.notificationManager.updateNotification(context)
    }

    return messageIdToDelete
}

fun handleMessageRequestResponse(message: MessageRequestResponse) {
    MessagingModuleConfiguration.shared.storage.insertMessageRequestResponseFromContact(message)
}
//endregion

private fun SignalServiceProtos.Content.ExpirationType.expiryMode(durationSeconds: Long) = takeIf { durationSeconds > 0 }?.let {
    when (it) {
        SignalServiceProtos.Content.ExpirationType.DELETE_AFTER_READ -> ExpiryMode.AfterRead(durationSeconds)
        SignalServiceProtos.Content.ExpirationType.DELETE_AFTER_SEND, SignalServiceProtos.Content.ExpirationType.UNKNOWN -> ExpiryMode.AfterSend(durationSeconds)
        else -> ExpiryMode.NONE
    }
} ?: ExpiryMode.NONE

fun MessageReceiver.handleVisibleMessage(
    message: VisibleMessage,
    proto: SignalServiceProtos.Content,
    openGroupID: String?,
    threadId: Long,
    runThreadUpdate: Boolean,
    runProfileUpdate: Boolean
): MessageId? {
    val storage = MessagingModuleConfiguration.shared.storage
    val context = MessagingModuleConfiguration.shared.context
    val userPublicKey = storage.getUserPublicKey()
    val messageSender: String? = message.sender

    // Do nothing if the message was outdated
    if (MessageReceiver.messageIsOutdated(message, threadId, openGroupID)) { return null }

    // Get or create thread
    // FIXME: In case this is an open group this actually * doesn't * create the thread if it doesn't yet
    //        exist. This is intentional, but it's very non-obvious.
    val threadID = storage.getThreadIdFor(message.syncTarget ?: messageSender!!, message.groupPublicKey, openGroupID, createThread = true)
        // Thread doesn't exist; should only be reached in a case where we are processing open group messages for a no longer existent thread
        ?: throw MessageReceiver.Error.NoThread
    val threadRecipient = storage.getRecipientForThread(threadID)
    val userBlindedKey = openGroupID?.let {
        val openGroup = storage.getOpenGroup(threadID) ?: return@let null
        val blindedKey = BlindKeyAPI.blind15KeyPairOrNull(
            ed25519SecretKey = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair()!!.secretKey.data,
            serverPubKey = Hex.fromStringCondensed(openGroup.publicKey),
        ) ?: return@let null
        AccountId(
            IdPrefix.BLINDED, blindedKey.pubKey.data
        ).hexString
    }
    // Update profile if needed
    val recipient = Recipient.from(context, Address.fromSerialized(messageSender!!), false)
    if (runProfileUpdate) {
        val profile = message.profile
        val isUserBlindedSender = messageSender == userBlindedKey
        if (profile != null && userPublicKey != messageSender && !isUserBlindedSender) {
            val profileManager = SSKEnvironment.shared.profileManager
            val name = profile.displayName!!
            if (name.isNotEmpty()) {
                profileManager.setName(context, recipient, name)
            }
            val newProfileKey = profile.profileKey

            val needsProfilePicture = !AvatarHelper.avatarFileExists(context, Address.fromSerialized(messageSender))
            val profileKeyValid = newProfileKey?.isNotEmpty() == true && (newProfileKey.size == 16 || newProfileKey.size == 32) && profile.profilePictureURL?.isNotEmpty() == true
            val profileKeyChanged = (recipient.profileKey == null || !MessageDigest.isEqual(recipient.profileKey, newProfileKey))

            if ((profileKeyValid && profileKeyChanged) || (profileKeyValid && needsProfilePicture)) {
                profileManager.setProfilePicture(context, recipient, profile.profilePictureURL, newProfileKey)
                profileManager.setUnidentifiedAccessMode(context, recipient, Recipient.UnidentifiedAccessMode.UNKNOWN)
            } else if (newProfileKey == null || newProfileKey.isEmpty() || profile.profilePictureURL.isNullOrEmpty()) {
                profileManager.setProfilePicture(context, recipient, null, null)
            }
        }

        if (userPublicKey != messageSender && !isUserBlindedSender) {
            storage.setBlocksCommunityMessageRequests(recipient, message.blocksMessageRequests)
        }

        // update the disappearing / legacy banner for the sender
        val disappearingState = when {
            proto.dataMessage.expireTimer > 0 && !proto.hasExpirationType() -> Recipient.DisappearingState.LEGACY
            else -> Recipient.DisappearingState.UPDATED
        }
        storage.updateDisappearingState(
            messageSender,
            threadID,
            disappearingState
        )
    }
    // Handle group invite response if new closed group
    if (threadRecipient?.isGroupV2Recipient == true) {
        GlobalScope.launch {
            try {
                MessagingModuleConfiguration.shared.groupManagerV2
                    .handleInviteResponse(
                        AccountId(threadRecipient.address.toString()),
                        AccountId(messageSender),
                        approved = true
                    )
            } catch (e: Exception) {
                Log.e("Loki", "Failed to handle invite response", e)
            }
        }
    }
    // Parse quote if needed
    var quoteModel: QuoteModel? = null
    var quoteMessageBody: String? = null
    if (message.quote != null && proto.dataMessage.hasQuote()) {
        val quote = proto.dataMessage.quote

        val author = if (quote.author == userBlindedKey) {
            Address.fromSerialized(userPublicKey!!)
        } else {
            Address.fromSerialized(quote.author)
        }

        val messageDataProvider = MessagingModuleConfiguration.shared.messageDataProvider
        val messageInfo = messageDataProvider.getMessageForQuote(quote.id, author)
        quoteMessageBody = messageInfo?.third
        quoteModel = if (messageInfo != null) {
            val attachments = if (messageInfo.second) messageDataProvider.getAttachmentsAndLinkPreviewFor(messageInfo.first) else ArrayList()
            QuoteModel(quote.id, author,null,false, attachments)
        } else {
            QuoteModel(quote.id, author,null, true, PointerAttachment.forPointers(proto.dataMessage.quote.attachmentsList))
        }
    }
    // Parse link preview if needed
    val linkPreviews: MutableList<LinkPreview?> = mutableListOf()
    if (message.linkPreview != null && proto.dataMessage.previewCount > 0) {
        for (preview in proto.dataMessage.previewList) {
            val thumbnail = PointerAttachment.forPointer(preview.image)
            val url = Optional.fromNullable(preview.url)
            val title = Optional.fromNullable(preview.title)
            val hasContent = !TextUtils.isEmpty(title.or("")) || thumbnail.isPresent
            if (hasContent) {
                val linkPreview = LinkPreview(url.get(), title.or(""), thumbnail)
                linkPreviews.add(linkPreview)
            } else {
                Log.w("Loki", "Discarding an invalid link preview. hasContent: $hasContent")
            }
        }
    }
    // Parse attachments if needed
    val attachments = proto.dataMessage.attachmentsList.map(Attachment::fromProto).filter { it.isValid() }

    // Cancel any typing indicators if needed
    cancelTypingIndicatorsIfNeeded(message.sender!!)

    // Parse reaction if needed
    val threadIsGroup = threadRecipient?.isGroupOrCommunityRecipient == true
    message.reaction?.let { reaction ->
        if (reaction.react == true) {
            reaction.serverId = message.openGroupServerMessageID?.toString() ?: message.serverHash.orEmpty()
            reaction.dateSent = message.sentTimestamp ?: 0
            reaction.dateReceived = message.receivedTimestamp ?: 0
            storage.addReaction(
                threadId = threadId,
                reaction = reaction,
                messageSender = messageSender,
                notifyUnread = !threadIsGroup
            )
        } else {
            storage.removeReaction(
                emoji = reaction.emoji!!,
                messageTimestamp = reaction.timestamp!!,
                threadId = threadId,
                author = reaction.publicKey!!,
                notifyUnread = threadIsGroup
            )
        }
    } ?: run {
        // A user is mentioned if their public key is in the body of a message or one of their messages
        // was quoted
        val messageText = message.text
        message.hasMention = listOf(userPublicKey, userBlindedKey)
            .filterNotNull()
            .any { key ->
                messageText?.contains("@$key") == true || key == (quoteModel?.author?.toString() ?: "")
            }

        // Persist the message
        message.threadID = threadID

        // clean up the message - For example we do not want any expiration data on messages for communities
       if(message.openGroupServerMessageID != null){
           message.expiryMode = ExpiryMode.NONE
       }

        val messageID = storage.persist(message, quoteModel, linkPreviews, message.groupPublicKey, openGroupID, attachments, runThreadUpdate) ?: return null
        // Parse & persist attachments
        // Start attachment downloads if needed
        if (messageID.mms && (threadRecipient?.autoDownloadAttachments == true || messageSender == userPublicKey)) {
            storage.getAttachmentsForMessage(messageID.id).iterator().forEach { attachment ->
                attachment.attachmentId?.let { id ->
                    val downloadJob = AttachmentDownloadJob(id.rowId, messageID.id)
                    JobQueue.shared.add(downloadJob)
                }
            }
        }
        message.openGroupServerMessageID?.let {
            storage.setOpenGroupServerMessageID(
                messageID = messageID,
                serverID = it,
                threadID = threadID
            )
        }
        SSKEnvironment.shared.messageExpirationManager.maybeStartExpiration(message)
        return messageID
    }
    return null
}

fun MessageReceiver.handleOpenGroupReactions(
    threadId: Long,
    openGroupMessageServerID: Long,
    reactions: Map<String, OpenGroupApi.Reaction>?
) {
    if (reactions.isNullOrEmpty()) return
    val storage = MessagingModuleConfiguration.shared.storage
    val messageId = MessagingModuleConfiguration.shared.messageDataProvider.getMessageID(openGroupMessageServerID, threadId) ?: return
    storage.deleteReactions(messageId)
    val userPublicKey = storage.getUserPublicKey()!!
    val openGroup = storage.getOpenGroup(threadId)
    val blindedPublicKey = openGroup?.publicKey?.let { serverPublicKey ->
        BlindKeyAPI.blind15KeyPairOrNull(
            ed25519SecretKey = MessagingModuleConfiguration.shared.storage.getUserED25519KeyPair()!!.secretKey.data,
            serverPubKey = Hex.fromStringCondensed(serverPublicKey),
        )
            ?.let { AccountId(IdPrefix.BLINDED, it.pubKey.data).hexString }
    }
    for ((emoji, reaction) in reactions) {
        val pendingUserReaction = OpenGroupApi.pendingReactions
            .filter { it.server == openGroup?.server && it.room == openGroup.room && it.messageId == openGroupMessageServerID && it.add }
            .sortedByDescending { it.seqNo }
            .any { it.emoji == emoji }
        val shouldAddUserReaction = pendingUserReaction || reaction.you || reaction.reactors.contains(userPublicKey)
        val reactorIds = reaction.reactors.filter { it != blindedPublicKey && it != userPublicKey }
        val count = if (reaction.you) reaction.count - 1 else reaction.count
        // Add the first reaction (with the count)
        reactorIds.firstOrNull()?.let { reactor ->
            storage.addReaction(
                messageId = messageId,
                reaction = Reaction(
                    publicKey = reactor,
                    emoji = emoji,
                    react = true,
                    serverId = "$openGroupMessageServerID",
                    count = count,
                    index = reaction.index
                ),
                messageSender = reactor,
                notifyUnread = false
            )
        }

        // Add all other reactions
        val maxAllowed = if (shouldAddUserReaction) 4 else 5
        val lastIndex = min(maxAllowed, reactorIds.size)
        reactorIds.slice(1 until lastIndex).map { reactor ->
            storage.addReaction(
                messageId = messageId,
                reaction = Reaction(
                    publicKey = reactor,
                    emoji = emoji,
                    react = true,
                    serverId = "$openGroupMessageServerID",
                    count = 0,  // Only want this on the first reaction
                    index = reaction.index
                ),
                messageSender = reactor,
                notifyUnread = false
            )
        }

        // Add the current user reaction (if applicable and not already included)
        if (shouldAddUserReaction) {
            storage.addReaction(
                messageId = messageId,
                reaction = Reaction(
                    publicKey = userPublicKey,
                    emoji = emoji,
                    react = true,
                    serverId = "$openGroupMessageServerID",
                    count = 1,
                    index = reaction.index
                ),
                messageSender = userPublicKey,
                notifyUnread = false
            )
        }
    }
}

//endregion

// region Closed Groups
private fun MessageReceiver.handleLegacyGroupControlMessage(message: LegacyGroupControlMessage) {
    if (MessagingModuleConfiguration.shared.deprecationManager.deprecationState.value ==
        LegacyGroupDeprecationManager.DeprecationState.DEPRECATED) {
        Log.d("ClosedGroupControlMessage", "Ignoring closed group control message post deprecation")
        return
    }

    when (message.kind!!) {
        is LegacyGroupControlMessage.Kind.New -> handleNewLegacyGroup(message)
        is LegacyGroupControlMessage.Kind.EncryptionKeyPair -> handleClosedGroupEncryptionKeyPair(message)
        is LegacyGroupControlMessage.Kind.NameChange -> handleClosedGroupNameChanged(message)
        is LegacyGroupControlMessage.Kind.MembersAdded -> handleClosedGroupMembersAdded(message)
        is LegacyGroupControlMessage.Kind.MembersRemoved -> handleClosedGroupMembersRemoved(message)
        is LegacyGroupControlMessage.Kind.MemberLeft -> handleClosedGroupMemberLeft(message)
    }
    if (
            message.kind !is LegacyGroupControlMessage.Kind.New &&
            MessagingModuleConfiguration.shared.storage.canPerformConfigChange(
                    SharedConfigMessage.Kind.GROUPS.name,
                    MessagingModuleConfiguration.shared.storage.getUserPublicKey()!!,
                    message.sentTimestamp!!
            )
    ) {
        // update the config
        val closedGroupPublicKey = message.getPublicKey()
        val storage = MessagingModuleConfiguration.shared.storage
        storage.updateGroupConfig(closedGroupPublicKey)
    }
}

private fun LegacyGroupControlMessage.getPublicKey(): String = kind!!.let { when (it) {
    is LegacyGroupControlMessage.Kind.New -> it.publicKey.toByteArray().toHexString()
    is LegacyGroupControlMessage.Kind.EncryptionKeyPair -> it.publicKey?.toByteArray()?.toHexString() ?: groupPublicKey!!
    is LegacyGroupControlMessage.Kind.MemberLeft -> groupPublicKey!!
    is LegacyGroupControlMessage.Kind.MembersAdded -> groupPublicKey!!
    is LegacyGroupControlMessage.Kind.MembersRemoved -> groupPublicKey!!
    is LegacyGroupControlMessage.Kind.NameChange -> groupPublicKey!!
}}

private fun MessageReceiver.handleGroupUpdated(message: GroupUpdated, closedGroup: AccountId?) {
    val inner = message.inner
    if (closedGroup == null &&
        !inner.hasInviteMessage() && !inner.hasPromoteMessage()) {
        throw NullPointerException("Message wasn't polled from a closed group!")
    }

    // Update profile if needed
    if (message.profile != null && !message.isSenderSelf) {
        val profile = message.profile
        val recipient = Recipient.from(MessagingModuleConfiguration.shared.context, Address.fromSerialized(message.sender!!), false)
        val profileManager = SSKEnvironment.shared.profileManager
        if (profile.displayName?.isNotEmpty() == true) {
            profileManager.setName(MessagingModuleConfiguration.shared.context, recipient, profile.displayName)
        }
        if (profile.profileKey?.isNotEmpty() == true && !profile.profilePictureURL.isNullOrEmpty()) {
            profileManager.setProfilePicture(MessagingModuleConfiguration.shared.context, recipient, profile.profilePictureURL, profile.profileKey)
        }
    }

    when {
        inner.hasInviteMessage() -> handleNewLibSessionClosedGroupMessage(message)
        inner.hasInviteResponse() -> handleInviteResponse(message, closedGroup!!)
        inner.hasPromoteMessage() -> handlePromotionMessage(message)
        inner.hasInfoChangeMessage() -> handleGroupInfoChange(message, closedGroup!!)
        inner.hasMemberChangeMessage() -> handleMemberChange(message, closedGroup!!)
        inner.hasMemberLeftMessage() -> handleMemberLeft(message, closedGroup!!)
        inner.hasMemberLeftNotificationMessage() -> handleMemberLeftNotification(message, closedGroup!!)
        inner.hasDeleteMemberContent() -> handleDeleteMemberContent(message, closedGroup!!)
    }
}

private fun handleDeleteMemberContent(message: GroupUpdated, closedGroup: AccountId) {
    val deleteMemberContent = message.inner.deleteMemberContent
    val adminSig = if (deleteMemberContent.hasAdminSignature()) deleteMemberContent.adminSignature.toByteArray()!! else byteArrayOf()

    val hasValidAdminSignature = adminSig.isNotEmpty() && runCatching {
        verifyAdminSignature(
            closedGroup,
            adminSig,
            buildDeleteMemberContentSignature(
                memberIds = deleteMemberContent.memberSessionIdsList.asSequence().map(::AccountId).asIterable(),
                messageHashes = deleteMemberContent.messageHashesList,
                timestamp = message.sentTimestamp!!,
            )
        )
    }.isSuccess

    GlobalScope.launch {
        try {
            MessagingModuleConfiguration.shared.groupManagerV2.handleDeleteMemberContent(
                groupId = closedGroup,
                deleteMemberContent = deleteMemberContent,
                timestamp = message.sentTimestamp!!,
                sender = AccountId(message.sender!!),
                senderIsVerifiedAdmin = hasValidAdminSignature
            )
        } catch (e: Exception) {
            Log.e("GroupUpdated", "Failed to handle delete member content", e)
        }
    }
}

private fun handleMemberChange(message: GroupUpdated, closedGroup: AccountId) {
    val storage = MessagingModuleConfiguration.shared.storage
    val memberChange = message.inner.memberChangeMessage
    val type = memberChange.type
    val timestamp = message.sentTimestamp!!
    verifyAdminSignature(closedGroup,
        memberChange.adminSignature.toByteArray(),
        buildMemberChangeSignature(type, timestamp)
    )
    storage.insertGroupInfoChange(message, closedGroup)
}

private fun handleMemberLeft(message: GroupUpdated, closedGroup: AccountId) {
    GlobalScope.launch(Dispatchers.Default) {
        try {
            MessagingModuleConfiguration.shared.groupManagerV2.handleMemberLeftMessage(
                AccountId(message.sender!!), closedGroup
            )
        } catch (e: Exception) {
            Log.e("GroupUpdated", "Failed to handle member left message", e)
        }
    }
}

private fun handleMemberLeftNotification(message: GroupUpdated, closedGroup: AccountId) {
    MessagingModuleConfiguration.shared.storage.insertGroupInfoChange(message, closedGroup)
}

private fun handleGroupInfoChange(message: GroupUpdated, closedGroup: AccountId) {
    val inner = message.inner
    val infoChanged = inner.infoChangeMessage ?: return
    if (!infoChanged.hasAdminSignature()) return Log.e("GroupUpdated", "Info changed message doesn't contain admin signature")
    val adminSignature = infoChanged.adminSignature
    val type = infoChanged.type
    val timestamp = message.sentTimestamp!!
    verifyAdminSignature(closedGroup, adminSignature.toByteArray(), buildInfoChangeSignature(type, timestamp))

    MessagingModuleConfiguration.shared.groupManagerV2.handleGroupInfoChange(message, closedGroup)
}

private fun handlePromotionMessage(message: GroupUpdated) {
    val promotion = message.inner.promoteMessage
    val seed = promotion.groupIdentitySeed.toByteArray()
    val sender = message.sender!!
    val adminId = AccountId(sender)
    GlobalScope.launch {
        try {
            MessagingModuleConfiguration.shared.groupManagerV2
                .handlePromotion(
                    groupId = AccountId(IdPrefix.GROUP, ED25519.generate(seed).pubKey.data),
                    groupName = promotion.name,
                    adminKeySeed = seed,
                    promoter = adminId,
                    promoterName = message.profile?.displayName,
                    promoteMessageHash = message.serverHash!!,
                    promoteMessageTimestamp = message.sentTimestamp!!,
                )
        } catch (e: Exception) {
            Log.e("GroupUpdated", "Failed to handle promotion message", e)
        }
    }
}

private fun MessageReceiver.handleInviteResponse(message: GroupUpdated, closedGroup: AccountId) {
    val sender = message.sender!!
    // val profile = message // maybe we do need data to be the inner so we can access profile
    val storage = MessagingModuleConfiguration.shared.storage
    val approved = message.inner.inviteResponse.isApproved
    GlobalScope.launch {
        try {
            MessagingModuleConfiguration.shared.groupManagerV2.handleInviteResponse(closedGroup, AccountId(sender), approved)
        } catch (e: Exception) {
            Log.e("GroupUpdated", "Failed to handle invite response", e)
        }
    }
}

private fun MessageReceiver.handleNewLibSessionClosedGroupMessage(message: GroupUpdated) {
    val storage = MessagingModuleConfiguration.shared.storage
    val ourUserId = storage.getUserPublicKey()!!
    val invite = message.inner.inviteMessage
    val groupId = AccountId(invite.groupSessionId)
    verifyAdminSignature(
        groupSessionId = groupId,
        signatureData = invite.adminSignature.toByteArray(),
        messageToValidate = buildGroupInviteSignature(AccountId(ourUserId), message.sentTimestamp!!)
    )

    val sender = message.sender!!
    val adminId = AccountId(sender)
    GlobalScope.launch {
        try {
            MessagingModuleConfiguration.shared.groupManagerV2
                .handleInvitation(
                    groupId = groupId,
                    groupName = invite.name,
                    authData = invite.memberAuthData.toByteArray(),
                    inviter = adminId,
                    inviterName = message.profile?.displayName,
                    inviteMessageHash = message.serverHash!!,
                    inviteMessageTimestamp = message.sentTimestamp!!,
                )
        } catch (e: Exception) {
            Log.e("GroupUpdated", "Failed to handle invite message", e)
        }
    }
}

/**
 * Does nothing on successful signature verification, throws otherwise.
 * Assumes the signer is using the ed25519 group key signing key
 * @param groupSessionId the AccountId of the group to check the signature against
 * @param signatureData the byte array supplied to us through a protobuf message from the admin
 * @param messageToValidate the expected values used for this signature generation, often something like `INVITE||{inviteeSessionId}||{timestamp}`
 * @throws SignatureException if signature cannot be verified with given parameters
 */
private fun verifyAdminSignature(groupSessionId: AccountId, signatureData: ByteArray, messageToValidate: ByteArray) {
    val groupPubKey = groupSessionId.pubKeyBytes
    if (!ED25519.verify(signature = signatureData, ed25519PublicKey = groupPubKey, message = messageToValidate)) {
        throw SignatureException("Verification failed for signature data")
    }
}

private fun MessageReceiver.handleNewLegacyGroup(message: LegacyGroupControlMessage) {
    val storage = MessagingModuleConfiguration.shared.storage
    val kind = message.kind!! as? LegacyGroupControlMessage.Kind.New ?: return
    val recipient = Recipient.from(MessagingModuleConfiguration.shared.context, Address.fromSerialized(message.sender!!), false)
    if (!recipient.isApproved && !recipient.isLocalNumber) return Log.e("Loki", "not accepting new closed group from unapproved recipient")
    val groupPublicKey = kind.publicKey.toByteArray().toHexString()
    // hard code check by group public key in the big function because I can't be bothered to do group double decode re-encodej
    if ((storage.getThreadIdFor(message.sender!!, groupPublicKey, null, false) ?: -1L) >= 0L) return
    val members = kind.members.map { it.toByteArray().toHexString() }
    val admins = kind.admins.map { it.toByteArray().toHexString() }
    val expirationTimer = kind.expirationTimer
    handleNewLegacyGroup(message.sender!!, message.sentTimestamp!!, groupPublicKey, kind.name, kind.encryptionKeyPair!!, members, admins, message.sentTimestamp!!, expirationTimer)
}

private fun handleNewLegacyGroup(sender: String, sentTimestamp: Long, groupPublicKey: String, name: String, encryptionKeyPair: ECKeyPair, members: List<String>, admins: List<String>, formationTimestamp: Long, expirationTimer: Int) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val groupExists = storage.getGroup(groupID) != null

    if (!storage.canPerformConfigChange(SharedConfigMessage.Kind.GROUPS.name, userPublicKey, sentTimestamp)) {
        // If the closed group already exists then store the encryption keys (since the config only stores
        // the latest key we won't be able to decrypt older messages if we were added to the group within
        // the last two weeks and the key has been rotated - unfortunately if the user was added more than
        // two weeks ago and the keys were rotated within the last two weeks then we won't be able to decrypt
        // messages received before the key rotation)
        if (groupExists) {
            storage.addClosedGroupEncryptionKeyPair(encryptionKeyPair, groupPublicKey, sentTimestamp)
            storage.updateGroupConfig(groupPublicKey)
        }
        return
    }

    // Create the group
    if (groupExists) {
        // Update the group
        if (!storage.isGroupActive(groupPublicKey)) {
            // Clear zombie list if the group wasn't active
            storage.setZombieMembers(groupID, listOf())
            // Update the formation timestamp
            storage.updateFormationTimestamp(groupID, formationTimestamp)
        }
        storage.updateTitle(groupID, name)
        storage.updateMembers(groupID, members.map { Address.fromSerialized(it) })
    } else {
        storage.createGroup(groupID, name, LinkedList(members.map { Address.fromSerialized(it) }),
            null, null, LinkedList(admins.map { Address.fromSerialized(it) }), formationTimestamp)
    }
    storage.setProfileSharing(Address.fromSerialized(groupID), true)
    // Add the group to the user's set of public keys to poll for
    storage.addClosedGroupPublicKey(groupPublicKey)
    // Store the encryption key pair
    storage.addClosedGroupEncryptionKeyPair(encryptionKeyPair, groupPublicKey, sentTimestamp)
    storage.createInitialConfigGroup(groupPublicKey, name, GroupUtil.createConfigMemberMap(members, admins), formationTimestamp, encryptionKeyPair, expirationTimer)
    // Notify the PN server
    PushRegistryV1.register(device = MessagingModuleConfiguration.shared.device, publicKey = userPublicKey)
    // Notify the user
    if (userPublicKey == sender && !groupExists) {
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.CREATION, name, members, admins, threadID, sentTimestamp)
    } else if (userPublicKey != sender) {
        storage.insertIncomingInfoMessage(context, sender, groupID, SignalServiceGroup.Type.CREATION, name, members, admins, sentTimestamp)
    }
}

private fun MessageReceiver.handleClosedGroupEncryptionKeyPair(message: LegacyGroupControlMessage) {
    // Prepare
    val storage = MessagingModuleConfiguration.shared.storage
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? LegacyGroupControlMessage.Kind.EncryptionKeyPair ?: return
    var groupPublicKey = kind.publicKey?.toByteArray()?.toHexString()
    if (groupPublicKey.isNullOrEmpty()) groupPublicKey = message.groupPublicKey ?: return
    val userPublicKey = storage.getUserPublicKey()!!
    val userKeyPair = storage.getUserX25519KeyPair()
    // Unwrap the message
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group encryption key pair for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group encryption key pair for inactive group.")
        return
    }
    if (!group.admins.map { it.toString() }.contains(senderPublicKey)) {
        Log.d("Loki", "Ignoring closed group encryption key pair from non-admin.")
        return
    }
    // Find our wrapper and decrypt it if possible
    val wrapper = kind.wrappers.firstOrNull { it.publicKey!! == userPublicKey } ?: return
    val encryptedKeyPair = wrapper.encryptedKeyPair!!.toByteArray()
    val plaintext = MessageDecrypter.decrypt(encryptedKeyPair, userKeyPair).first
    // Parse it
    val proto = SignalServiceProtos.KeyPair.parseFrom(plaintext)
    val keyPair = ECKeyPair(DjbECPublicKey(proto.publicKey.toByteArray().removingIdPrefixIfNeeded()), DjbECPrivateKey(proto.privateKey.toByteArray()))
    // Store it if needed
    val closedGroupEncryptionKeyPairs = storage.getClosedGroupEncryptionKeyPairs(groupPublicKey)
    if (closedGroupEncryptionKeyPairs.contains(keyPair)) {
        Log.d("Loki", "Ignoring duplicate closed group encryption key pair.")
        return
    }
    storage.addClosedGroupEncryptionKeyPair(keyPair, groupPublicKey, message.sentTimestamp!!)
    Log.d("Loki", "Received a new closed group encryption key pair.")
}

private fun MessageReceiver.handleClosedGroupNameChanged(message: LegacyGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = TextSecurePreferences.getLocalNumber(context)
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? LegacyGroupControlMessage.Kind.NameChange ?: return
    val groupPublicKey = message.groupPublicKey ?: return
    // Check that the sender is a member of the group (before the update)
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    // Check common group update logic
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) {
        return
    }
    val members = group.members.map { it.toString() }
    val admins = group.admins.map { it.toString() }
    val name = kind.name

    // Only update the group in storage if it isn't invalidated by the config state
    if (storage.canPerformConfigChange(SharedConfigMessage.Kind.GROUPS.name, userPublicKey!!, message.sentTimestamp!!)) {
        storage.updateTitle(groupID, name)
    }

    // Notify the user
    if (userPublicKey == senderPublicKey) {
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.NAME_CHANGE, name, members, admins, threadID, message.sentTimestamp!!)
    } else {
        storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, SignalServiceGroup.Type.NAME_CHANGE, name, members, admins, message.sentTimestamp!!)
    }
}

private fun MessageReceiver.handleClosedGroupMembersAdded(message: LegacyGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? LegacyGroupControlMessage.Kind.MembersAdded ?: return
    val groupPublicKey = message.groupPublicKey ?: return
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) { return }
    val name = group.title
    // Check common group update logic
    val members = group.members.map { it.toString() }
    val admins = group.admins.map { it.toString() }

    val updateMembers = kind.members.map { it.toByteArray().toHexString() }
    val newMembers = members + updateMembers

    // Only update the group in storage if it isn't invalidated by the config state
    if (storage.canPerformConfigChange(SharedConfigMessage.Kind.GROUPS.name, userPublicKey, message.sentTimestamp!!)) {
        storage.updateMembers(groupID, newMembers.map { Address.fromSerialized(it) })

        // Update zombie members in case the added members are zombies
        val zombies = storage.getZombieMembers(groupID)
        if (zombies.intersect(updateMembers).isNotEmpty()) {
            storage.setZombieMembers(groupID, zombies.minus(updateMembers).map { Address.fromSerialized(it) })
        }
    }

    // Notify the user
    if (userPublicKey == senderPublicKey) {
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.MEMBER_ADDED, name, updateMembers, admins, threadID, message.sentTimestamp!!)
    } else {
        storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, SignalServiceGroup.Type.MEMBER_ADDED, name, updateMembers, admins, message.sentTimestamp!!)
    }
    if (userPublicKey in admins) {
        // Send the latest encryption key pair to the added members if the current user is the admin of the group
        //
        // This fixes a race condition where:
        // • A member removes another member.
        // • A member adds someone to the group and sends them the latest group key pair.
        // • The admin is offline during all of this.
        // • When the admin comes back online they see the member removed message and generate + distribute a new key pair,
        //   but they don't know about the added member yet.
        // • Now they see the member added message.
        //
        // Without the code below, the added member(s) would never get the key pair that was generated by the admin when they saw
        // the member removed message.
        val encryptionKeyPair = pendingKeyPairs[groupPublicKey]?.orNull()
            ?: storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey)
        if (encryptionKeyPair == null) {
            Log.d("Loki", "Couldn't get encryption key pair for closed group.")
        } else {
            for (user in updateMembers) {
                MessageSender.sendEncryptionKeyPair(groupPublicKey, encryptionKeyPair, setOf(user), targetUser = user, force = false)
            }
        }
    }
}

/// Removes the given members from the group IF
/// • it wasn't the admin that was removed (that should happen through a `MEMBER_LEFT` message).
/// • the admin sent the message (only the admin can truly remove members).
/// If we're among the users that were removed, delete all encryption key pairs and the group public key, unsubscribe
/// from push notifications for this closed group, and remove the given members from the zombie list for this group.
private fun MessageReceiver.handleClosedGroupMembersRemoved(message: LegacyGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val senderPublicKey = message.sender ?: return
    val kind = message.kind!! as? LegacyGroupControlMessage.Kind.MembersRemoved ?: return
    val groupPublicKey = message.groupPublicKey ?: return
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    val name = group.title
    // Check common group update logic
    val members = group.members.map { it.toString() }
    val admins = group.admins.map { it.toString() }
    val removedMembers = kind.members.map { it.toByteArray().toHexString() }
    val zombies: Set<String> = storage.getZombieMembers(groupID)
    // Check that the admin wasn't removed
    if (removedMembers.contains(admins.first())) {
        Log.d("Loki", "Ignoring invalid closed group update.")
        return
    }
    // Check that the message was sent by the group admin
    if (!admins.contains(senderPublicKey)) {
        Log.d("Loki", "Ignoring invalid closed group update.")
        return
    }
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) { return }
    // If the admin leaves the group is disbanded
    val didAdminLeave = admins.any { it in removedMembers }
    val newMembers = members - removedMembers
    // A user should be posting a MEMBERS_LEFT in case they leave, so this shouldn't be encountered
    val senderLeft = senderPublicKey in removedMembers
    if (senderLeft) {
        Log.d("Loki", "Received a MEMBERS_REMOVED instead of a MEMBERS_LEFT from sender: $senderPublicKey.")
    }
    val wasCurrentUserRemoved = userPublicKey in removedMembers

    // Only update the group in storage if it isn't invalidated by the config state
    if (storage.canPerformConfigChange(SharedConfigMessage.Kind.GROUPS.name, userPublicKey, message.sentTimestamp!!)) {
        // Admin should send a MEMBERS_LEFT message but handled here just in case
        if (didAdminLeave || wasCurrentUserRemoved) {
            disableLocalGroupAndUnsubscribe(groupPublicKey, groupID, userPublicKey, true)
            return
        } else {
            storage.updateMembers(groupID, newMembers.map { Address.fromSerialized(it) })
            // Update zombie members
            storage.setZombieMembers(groupID, zombies.minus(removedMembers).map { Address.fromSerialized(it) })
        }
    }

    // Notify the user
    val type = if (senderLeft) SignalServiceGroup.Type.QUIT else SignalServiceGroup.Type.MEMBER_REMOVED
    // We don't display zombie members in the notification as users have already been notified when those members left
    val notificationMembers = removedMembers.minus(zombies)
    if (notificationMembers.isNotEmpty()) {
        // No notification to display when only zombies have been removed
        if (userPublicKey == senderPublicKey) {
            val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
            storage.insertOutgoingInfoMessage(context, groupID, type, name, notificationMembers, admins, threadID, message.sentTimestamp!!)
        } else {
            storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, type, name, notificationMembers, admins, message.sentTimestamp!!)
        }
    }
}

/// If a regular member left:
/// • Mark them as a zombie (to be removed by the admin later).
/// If the admin left:
/// • Unsubscribe from PNs, delete the group public key, etc. as the group will be disbanded.
private fun MessageReceiver.handleClosedGroupMemberLeft(message: LegacyGroupControlMessage) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val senderPublicKey = message.sender ?: return
    val userPublicKey = storage.getUserPublicKey()!!
    if (message.kind!! !is LegacyGroupControlMessage.Kind.MemberLeft) return
    val groupPublicKey = message.groupPublicKey ?: return
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Ignoring closed group update for nonexistent group.")
        return
    }
    if (!group.isActive) {
        Log.d("Loki", "Ignoring closed group update for inactive group.")
        return
    }
    val name = group.title
    // Check common group update logic
    val members = group.members.map { it.toString() }
    val admins = group.admins.map { it.toString() }
    if (!isValidGroupUpdate(group, message.sentTimestamp!!, senderPublicKey)) {
        return
    }
    // If admin leaves the group is disbanded
    val didAdminLeave = admins.contains(senderPublicKey)
    val updatedMemberList = members - senderPublicKey
    val userLeft = (userPublicKey == senderPublicKey)

    // Only update the group in storage if it isn't invalidated by the config state
    if (storage.canPerformConfigChange(SharedConfigMessage.Kind.GROUPS.name, userPublicKey, message.sentTimestamp!!)) {
        if (didAdminLeave || userLeft) {
            disableLocalGroupAndUnsubscribe(groupPublicKey, groupID, userPublicKey, delete = userLeft)

            if (userLeft) {
                return
            }
        } else {
            storage.updateMembers(groupID, updatedMemberList.map { Address.fromSerialized(it) })
            // Update zombie members
            val zombies = storage.getZombieMembers(groupID)
            storage.setZombieMembers(groupID, zombies.plus(senderPublicKey).map { Address.fromSerialized(it) })
        }
    }

    // Notify the user
    if (!userLeft) {
        storage.insertIncomingInfoMessage(context, senderPublicKey, groupID, SignalServiceGroup.Type.QUIT, name, listOf(senderPublicKey), admins, message.sentTimestamp!!)
    }
}

private fun isValidGroupUpdate(group: GroupRecord, sentTimestamp: Long, senderPublicKey: String): Boolean {
    val oldMembers = group.members.map { it.toString() }
    // Check that the message isn't from before the group was created
    if (group.formationTimestamp > sentTimestamp) {
        Log.d("Loki", "Ignoring closed group update from before thread was created.")
        return false
    }
    // Check that the sender is a member of the group (before the update)
    if (senderPublicKey !in oldMembers) {
        Log.d("Loki", "Ignoring closed group info message from non-member.")
        return false
    }
    return true
}

fun MessageReceiver.disableLocalGroupAndUnsubscribe(groupPublicKey: String, groupID: String, userPublicKey: String, delete: Boolean) {
    val storage = MessagingModuleConfiguration.shared.storage
    storage.removeClosedGroupPublicKey(groupPublicKey)
    // Remove the key pairs
    storage.removeAllClosedGroupEncryptionKeyPairs(groupPublicKey)
    // Mark the group as inactive
    storage.setActive(groupID, false)
    storage.removeMember(groupID, Address.fromSerialized(userPublicKey))
    // Notify the PN server
    PushRegistryV1.unsubscribeGroup(groupPublicKey, publicKey = userPublicKey)

    if (delete) {
        storage.getThreadId(Address.fromSerialized(groupID))?.let { threadId ->
            storage.cancelPendingMessageSendJobs(threadId)
            storage.deleteConversation(threadId)
        }
    }
}
// endregion
