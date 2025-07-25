package org.session.libsession.messaging.jobs

import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import network.loki.messenger.libsession_util.ConfigBase
import network.loki.messenger.libsession_util.util.BlindKeyAPI
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.messages.Destination
import org.session.libsession.messaging.messages.Message
import org.session.libsession.messaging.messages.Message.Companion.senderOrSync
import org.session.libsession.messaging.messages.control.CallMessage
import org.session.libsession.messaging.messages.control.LegacyGroupControlMessage
import org.session.libsession.messaging.messages.control.ConfigurationMessage
import org.session.libsession.messaging.messages.control.DataExtractionNotification
import org.session.libsession.messaging.messages.control.ExpirationTimerUpdate
import org.session.libsession.messaging.messages.control.MessageRequestResponse
import org.session.libsession.messaging.messages.control.ReadReceipt
import org.session.libsession.messaging.messages.control.SharedConfigurationMessage
import org.session.libsession.messaging.messages.control.TypingIndicator
import org.session.libsession.messaging.messages.control.UnsendRequest
import org.session.libsession.messaging.messages.visible.ParsedMessage
import org.session.libsession.messaging.messages.visible.VisibleMessage
import org.session.libsession.messaging.open_groups.OpenGroupApi
import org.session.libsession.messaging.sending_receiving.MessageReceiver
import org.session.libsession.messaging.sending_receiving.handle
import org.session.libsession.messaging.sending_receiving.handleOpenGroupReactions
import org.session.libsession.messaging.sending_receiving.handleUnsendRequest
import org.session.libsession.messaging.sending_receiving.handleVisibleMessage
import org.session.libsession.messaging.utilities.Data
import org.session.libsession.utilities.SSKEnvironment
import org.session.libsession.utilities.UserConfigType
import org.session.libsignal.protos.UtilProtos
import org.session.libsignal.utilities.AccountId
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.IdPrefix
import org.session.libsignal.utilities.Log
import org.thoughtcrime.securesms.database.model.MessageId
import kotlin.math.max

data class MessageReceiveParameters(
    val data: ByteArray,
    val serverHash: String? = null,
    val openGroupMessageServerID: Long? = null,
    val reactions: Map<String, OpenGroupApi.Reaction>? = null,
    val closedGroup: Destination.ClosedGroup? = null
)

class BatchMessageReceiveJob(
    val messages: List<MessageReceiveParameters>,
    val openGroupID: String? = null
) : Job {

    override var delegate: JobDelegate? = null
    override var id: String? = null
    override var failureCount: Int = 0
    override val maxFailureCount: Int = 1 // handled in JobQueue onJobFailed
    // Failure Exceptions must be retryable if they're a  MessageReceiver.Error
    val failures = mutableListOf<MessageReceiveParameters>()

    companion object {
        const val TAG = "BatchMessageReceiveJob"
        const val KEY = "BatchMessageReceiveJob"

        const val BATCH_DEFAULT_NUMBER = 512

        // used for processing messages that don't have a thread and shouldn't create one
        const val NO_THREAD_MAPPING = -1L

        // Keys used for database storage
        private val NUM_MESSAGES_KEY = "numMessages"
        private val DATA_KEY = "data"
        private val SERVER_HASH_KEY = "serverHash"
        private val OPEN_GROUP_MESSAGE_SERVER_ID_KEY = "openGroupMessageServerID"
        private val OPEN_GROUP_ID_KEY = "open_group_id"
        private val CLOSED_GROUP_DESTINATION_KEY = "closed_group_destination"
    }

    private fun shouldCreateThread(parsedMessage: ParsedMessage): Boolean {
        val message = parsedMessage.message
        if (message is VisibleMessage) return true
        else { // message is control message otherwise
            return when(message) {
                is SharedConfigurationMessage -> false
                is LegacyGroupControlMessage -> false // message.kind is ClosedGroupControlMessage.Kind.New && !message.isSenderSelf
                is DataExtractionNotification -> false
                is MessageRequestResponse -> false
                is ExpirationTimerUpdate -> false
                is ConfigurationMessage -> false
                is TypingIndicator -> false
                is UnsendRequest -> false
                is ReadReceipt -> false
                is CallMessage -> false // TODO: maybe
                else -> false // shouldn't happen, or I guess would be Visible
            }
        }
    }

    override suspend fun execute(dispatcherName: String) {
        executeAsync(dispatcherName)
    }

    private fun isHidden(message: Message): Boolean {
        // if the contact is marked as hidden for 1on1 messages
        // and  the message's sentTimestamp is earlier than the sentTimestamp of the last config
        val configFactory = MessagingModuleConfiguration.shared.configFactory
        val publicKey = MessagingModuleConfiguration.shared.storage.getUserPublicKey()
        if (message.sentTimestamp == null || publicKey == null) return false

        val contactConfigTimestamp = configFactory.getConfigTimestamp(UserConfigType.CONTACTS, publicKey)

        return configFactory.withUserConfigs { configs ->
            message.groupPublicKey == null && // not a group
                    message.openGroupServerMessageID == null && // not a community
                    // not marked as hidden
                    configs.contacts.get(message.senderOrSync)?.priority == ConfigBase.PRIORITY_HIDDEN &&
                    // the message's sentTimestamp is earlier than the sentTimestamp of the last config
                    message.sentTimestamp!! < contactConfigTimestamp
        }
    }

    suspend fun executeAsync(dispatcherName: String) {
        val threadMap = mutableMapOf<Long, MutableList<ParsedMessage>>()
        val storage = MessagingModuleConfiguration.shared.storage
        val context = MessagingModuleConfiguration.shared.context
        val localUserPublicKey = storage.getUserPublicKey()
        val serverPublicKey = openGroupID?.let { storage.getOpenGroupPublicKey(it.split(".").dropLast(1).joinToString(".")) }
        val currentClosedGroups = storage.getAllActiveClosedGroupPublicKeys()

        // parse and collect IDs
        messages.forEach { messageParameters ->
            val (data, serverHash, openGroupMessageServerID) = messageParameters
            try {
                val (message, proto) = MessageReceiver.parse(
                    data,
                    openGroupMessageServerID,
                    openGroupPublicKey = serverPublicKey,
                    currentClosedGroups = currentClosedGroups,
                    closedGroupSessionId = messageParameters.closedGroup?.publicKey
                )
                message.serverHash = serverHash
                val parsedParams = ParsedMessage(messageParameters, message, proto)

                if(isHidden(message)) return@forEach

                val threadID = Message.getThreadId(
                    message = message,
                    openGroupID = openGroupID,
                    storage = storage,
                    shouldCreateThread = shouldCreateThread(parsedParams)
                ) ?: NO_THREAD_MAPPING
                threadMap.getOrPut(threadID) { mutableListOf() } += parsedParams
            } catch (e: Exception) {
                when (e) {
                    is MessageReceiver.Error.DuplicateMessage, MessageReceiver.Error.SelfSend -> {
                        Log.i(TAG, "Couldn't receive message, failed with error: ${e.message} (id: $id)")
                    }
                    is MessageReceiver.Error -> {
                        if (!e.isRetryable) {
                            Log.e(TAG, "Couldn't receive message, failed permanently (id: $id)", e)
                        }
                        else {
                            Log.e(TAG, "Couldn't receive message, failed (id: $id)", e)
                            failures += messageParameters
                        }
                    }
                    else -> {
                        Log.e(TAG, "Couldn't receive message, failed (id: $id)", e)
                        failures += messageParameters
                    }
                }
            }
        }

        // iterate over threads and persist them (persistence is the longest constant in the batch process operation)
        fun processMessages(threadId: Long, messages: List<ParsedMessage>) {
            // The LinkedHashMap should preserve insertion order
            val messageIds = linkedMapOf<MessageId, Pair<Boolean, Boolean>>()
            val myLastSeen = storage.getLastSeen(threadId)
            var newLastSeen = myLastSeen.takeUnless { it == -1L } ?: 0
            messages.forEach { (parameters, message, proto) ->
                try {
                    when (message) {
                        is VisibleMessage -> {
                            val isUserBlindedSender =
                                message.sender == serverPublicKey?.let {
                                    BlindKeyAPI.blind15KeyPairOrNull(
                                        ed25519SecretKey = storage.getUserED25519KeyPair()!!
                                            .secretKey.data,
                                        serverPubKey = Hex.fromStringCondensed(it),
                                    )
                                }?.let {
                                    AccountId(IdPrefix.BLINDED, it.pubKey.data).hexString
                                }
                            if (message.sender == localUserPublicKey || isUserBlindedSender) {
                                // use sent timestamp here since that is technically the last one we have
                                newLastSeen = max(newLastSeen, message.sentTimestamp!!)
                            }
                            val messageId = MessageReceiver.handleVisibleMessage(message, proto, openGroupID,
                                threadId,
                                runThreadUpdate = false,
                                runProfileUpdate = true)

                            if (messageId != null && message.reaction == null) {
                                messageIds[messageId] = Pair(
                                    (message.sender == localUserPublicKey || isUserBlindedSender),
                                    message.hasMention
                                )
                            }
                            parameters.openGroupMessageServerID?.let {
                                MessageReceiver.handleOpenGroupReactions(
                                    threadId,
                                    it,
                                    parameters.reactions
                                )
                            }
                        }

                        is UnsendRequest -> {
                            val deletedMessage = MessageReceiver.handleUnsendRequest(message)

                            // If we removed a message then ensure it isn't in the 'messageIds'
                            if (deletedMessage != null) {
                                messageIds.remove(deletedMessage)
                            }
                        }

                        else -> MessageReceiver.handle(
                            message = message,
                            proto = proto,
                            threadId = threadId,
                            openGroupID = openGroupID,
                            groupv2Id = parameters.closedGroup?.publicKey?.let(::AccountId)
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't process message (id: $id)", e)
                    if (e is MessageReceiver.Error && !e.isRetryable) {
                        Log.e(TAG, "Message failed permanently (id: $id)", e)
                    } else {
                        Log.e(TAG, "Message failed (id: $id)", e)
                        failures += parameters
                    }
                }
            }
            // increment unreads, notify, and update thread
            // last seen will be the current last seen if not changed (re-computes the read counts for thread record)
            // might have been updated from a different thread at this point
            val currentLastSeen = storage.getLastSeen(threadId).let { if (it == -1L) 0 else it }
            newLastSeen = max(newLastSeen, currentLastSeen)
            if (newLastSeen > 0 || currentLastSeen == 0L) {
                storage.markConversationAsRead(threadId, newLastSeen, force = true)
            }
            storage.updateThread(threadId, true)
            SSKEnvironment.shared.notificationManager.updateNotification(context, threadId)
        }

        coroutineScope {
            val withoutDefault = threadMap.entries.filter { it.key != NO_THREAD_MAPPING }
            val deferredThreadMap = withoutDefault.map { (threadId, messages) ->
                async(Dispatchers.Default) {
                    processMessages(threadId, messages)
                }
            }
            // await all thread processing
            deferredThreadMap.awaitAll()
        }

        val noThreadMessages = threadMap[NO_THREAD_MAPPING] ?: listOf()
        if (noThreadMessages.isNotEmpty()) {
            processMessages(NO_THREAD_MAPPING, noThreadMessages)
        }

        if (failures.isEmpty()) {
            handleSuccess(dispatcherName)
        } else {
            handleFailure(dispatcherName)
        }
    }

    private fun handleSuccess(dispatcherName: String) {
        Log.i(TAG, "Completed processing of ${messages.size} messages (id: $id)")
        delegate?.handleJobSucceeded(this, dispatcherName)
    }

    private fun handleFailure(dispatcherName: String) {
        Log.i(TAG, "Handling failure of ${failures.size} messages (${messages.size - failures.size} processed successfully) (id: $id)")
        delegate?.handleJobFailed(this, dispatcherName, Exception("One or more jobs resulted in failure"))
    }

    override fun serialize(): Data {
        val arraySize = messages.size
        val dataArrays = UtilProtos.ByteArrayList.newBuilder()
            .addAllContent(messages.map(MessageReceiveParameters::data).map(ByteString::copyFrom))
            .build()
        val serverHashes = messages.map { it.serverHash.orEmpty() }
        val openGroupServerIds = messages.map { it.openGroupMessageServerID ?: -1L }
        val closedGroups = messages.map { it.closedGroup?.publicKey.orEmpty() }
        return Data.Builder()
            .putInt(NUM_MESSAGES_KEY, arraySize)
            .putByteArray(DATA_KEY, dataArrays.toByteArray())
            .putString(OPEN_GROUP_ID_KEY, openGroupID)
            .putLongArray(OPEN_GROUP_MESSAGE_SERVER_ID_KEY, openGroupServerIds.toLongArray())
            .putStringArray(SERVER_HASH_KEY, serverHashes.toTypedArray())
            .putStringArray(CLOSED_GROUP_DESTINATION_KEY, closedGroups.toTypedArray())
            .build()
    }

    override fun getFactoryKey(): String = KEY

    class Factory : Job.Factory<BatchMessageReceiveJob> {
        override fun create(data: Data): BatchMessageReceiveJob {
            val numMessages = data.getInt(NUM_MESSAGES_KEY)
            val dataArrays = data.getByteArray(DATA_KEY)
            val contents =
                UtilProtos.ByteArrayList.parseFrom(dataArrays).contentList.map(ByteString::toByteArray)
            val serverHashes =
                if (data.hasStringArray(SERVER_HASH_KEY)) data.getStringArray(SERVER_HASH_KEY) else arrayOf()
            val openGroupMessageServerIDs = data.getLongArray(OPEN_GROUP_MESSAGE_SERVER_ID_KEY)
            val openGroupID = data.getStringOrDefault(OPEN_GROUP_ID_KEY, null)
            val closedGroups =
                if (data.hasStringArray(CLOSED_GROUP_DESTINATION_KEY)) data.getStringArray(CLOSED_GROUP_DESTINATION_KEY)
                else arrayOf()

            val parameters = (0 until numMessages).map { index ->
                val serverHash = serverHashes[index].let { if (it.isEmpty()) null else it }
                val serverId = openGroupMessageServerIDs[index].let { if (it == -1L) null else it }
                val closedGroup = closedGroups.getOrNull(index)?.let {
                    if (it.isEmpty()) null else Destination.ClosedGroup(it)
                }
                MessageReceiveParameters(
                    data = contents[index],
                    serverHash = serverHash,
                    openGroupMessageServerID = serverId,
                    closedGroup = closedGroup
                )
            }

            return BatchMessageReceiveJob(parameters, openGroupID)
        }
    }

}