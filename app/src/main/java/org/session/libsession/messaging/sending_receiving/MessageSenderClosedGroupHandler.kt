@file:Suppress("NAME_SHADOWING")

package org.session.libsession.messaging.sending_receiving

import com.google.protobuf.ByteString
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import network.loki.messenger.libsession_util.Curve25519
import nl.komponents.kovenant.Promise
import org.session.libsession.messaging.MessagingModuleConfiguration
import org.session.libsession.messaging.jobs.GroupLeavingJob
import org.session.libsession.messaging.jobs.JobQueue
import org.session.libsession.messaging.messages.control.LegacyGroupControlMessage
import org.session.libsession.messaging.sending_receiving.MessageSender.Error
import org.session.libsession.messaging.sending_receiving.notifications.PushRegistryV1
import org.session.libsession.snode.SnodeAPI
import org.session.libsession.snode.utilities.asyncPromise
import org.session.libsession.snode.utilities.await
import org.session.libsession.utilities.Address
import org.session.libsession.utilities.Address.Companion.fromSerialized
import org.session.libsession.utilities.Device
import org.session.libsession.utilities.GroupUtil
import org.session.libsignal.crypto.ecc.DjbECPrivateKey
import org.session.libsignal.crypto.ecc.DjbECPublicKey
import org.session.libsignal.crypto.ecc.ECKeyPair
import org.session.libsignal.messages.SignalServiceGroup
import org.session.libsignal.protos.SignalServiceProtos
import org.session.libsignal.utilities.Hex
import org.session.libsignal.utilities.Log
import org.session.libsignal.utilities.guava.Optional
import org.session.libsignal.utilities.removingIdPrefixIfNeeded
import org.session.libsignal.utilities.toHexString
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

const val groupSizeLimit = 100

val pendingKeyPairs = ConcurrentHashMap<String, Optional<ECKeyPair>>()

fun MessageSender.create(
    device: Device,
    name: String,
    members: Collection<String>
): Promise<String, Exception> {
    return GlobalScope.asyncPromise {
        // Prepare
        val storage = MessagingModuleConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()!!
        val membersAsData = members.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
        // Generate the group's public key
        val groupPublicKey = Curve25519.generateKeyPair().pubKey.data.toHexString() // Includes the "05" prefix
        // Generate the key pair that'll be used for encryption and decryption
        val encryptionKeyPair = Curve25519.generateKeyPair().let { k ->
            ECKeyPair(
                DjbECPublicKey(k.pubKey.data),
                DjbECPrivateKey(k.secretKey.data),
            )
        }
        // Create the group
        val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
        val admins = setOf( userPublicKey )
        val adminsAsData = admins.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
        storage.createGroup(groupID, name, LinkedList(members.map { fromSerialized(it) }),
            null, null, LinkedList(admins.map { Address.fromSerialized(it) }), SnodeAPI.nowWithOffset)
        storage.setProfileSharing(Address.fromSerialized(groupID), true)

        // Send a closed group update message to all members individually
        val closedGroupUpdateKind = LegacyGroupControlMessage.Kind.New(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), name, encryptionKeyPair, membersAsData, adminsAsData, 0)
        val sentTime = SnodeAPI.nowWithOffset

        // Add the group to the user's set of public keys to poll for
        storage.addClosedGroupPublicKey(groupPublicKey)
        // Store the encryption key pair
        storage.addClosedGroupEncryptionKeyPair(encryptionKeyPair, groupPublicKey, sentTime)
        // Create the thread
        storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))

        // Notify the user
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))

        // ACL Note: Commenting out this line prevents the timestamp of room creation being added to a new closed group,
        // which in turn allows us to show the `groupNoMessages` control message text.
        //storage.insertOutgoingInfoMessage(context, groupID, SignalServiceGroup.Type.CREATION, name, members, admins, threadID, sentTime)

        val ourPubKey = storage.getUserPublicKey()
        for (member in members) {
            val closedGroupControlMessage = LegacyGroupControlMessage(closedGroupUpdateKind, groupID)
            closedGroupControlMessage.sentTimestamp = sentTime
            try {
                sendNonDurably(closedGroupControlMessage, fromSerialized(member), member == ourPubKey)
                    .await()
            } catch (e: Exception) {
                // We failed to properly create the group so delete it's associated data (in the past
                // we didn't create this data until the messages successfully sent but this resulted
                // in race conditions due to the `NEW` message sent to our own swarm)
                storage.removeClosedGroupPublicKey(groupPublicKey)
                storage.removeAllClosedGroupEncryptionKeyPairs(groupPublicKey)
                storage.deleteConversation(threadID)
                throw e
            }
        }

        // Add the group to the config now that it was successfully created
        storage.createInitialConfigGroup(groupPublicKey, name, GroupUtil.createConfigMemberMap(members, admins), sentTime, encryptionKeyPair, 0)
        // Notify the PN server
        PushRegistryV1.register(device = device, publicKey = userPublicKey)
        groupID
    }
}

fun MessageSender.setName(groupPublicKey: String, newName: String) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't change name for nonexistent closed group.")
        throw Error.NoThread
    }
    val members = group.members.map { it.toString() }.toSet()
    val admins = group.admins.map { it.toString() }
    // Send the update to the group
    val kind = LegacyGroupControlMessage.Kind.NameChange(newName)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = LegacyGroupControlMessage(kind, groupID)
    closedGroupControlMessage.sentTimestamp = sentTime
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Update the group
    storage.updateTitle(groupID, newName)
    // Notify the user
    val infoType = SignalServiceGroup.Type.NAME_CHANGE
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, newName, members, admins, threadID, sentTime)
}

fun MessageSender.addMembers(groupPublicKey: String, membersToAdd: List<String>) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't add members to nonexistent closed group.")
        throw Error.NoThread
    }
    val threadId = storage.getOrCreateThreadIdFor(fromSerialized(groupID))
    val expireTimer = storage.getExpirationConfiguration(threadId)?.expiryMode?.expirySeconds ?: 0
    if (membersToAdd.isEmpty()) {
        Log.d("Loki", "Invalid closed group update.")
        throw Error.InvalidClosedGroupUpdate
    }
    val updatedMembers = group.members.map { it.toString() }.toSet() + membersToAdd
    // Save the new group members
    storage.updateMembers(groupID, updatedMembers.map { Address.fromSerialized(it) })
    val membersAsData = updatedMembers.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val newMembersAsData = membersToAdd.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val admins = group.admins.map { it.toString() }
    val adminsAsData = admins.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val encryptionKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: run {
        Log.d("Loki", "Couldn't get encryption key pair for closed group.")
        throw Error.NoKeyPair
    }
    val name = group.title
    // Send the update to the group
    val memberUpdateKind = LegacyGroupControlMessage.Kind.MembersAdded(newMembersAsData)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = LegacyGroupControlMessage(memberUpdateKind, groupID)
    closedGroupControlMessage.sentTimestamp = sentTime
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Send closed group update messages to any new members individually
    for (member in membersToAdd) {
        val closedGroupNewKind = LegacyGroupControlMessage.Kind.New(
            ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)),
            name,
            encryptionKeyPair,
            membersAsData,
            adminsAsData,
            expireTimer.toInt()
        )
        val closedGroupControlMessage = LegacyGroupControlMessage(closedGroupNewKind, groupID)
        // It's important that the sent timestamp of this message is greater than the sent timestamp
        // of the `MembersAdded` message above. The reason is that upon receiving this `New` message,
        // the recipient will update the closed group formation timestamp and ignore any closed group
        // updates from before that timestamp. By setting the timestamp of the message below to a value
        // greater than that of the `MembersAdded` message, we ensure that newly added members ignore
        // the `MembersAdded` message.
        closedGroupControlMessage.sentTimestamp = SnodeAPI.nowWithOffset
        send(closedGroupControlMessage, Address.fromSerialized(member))
    }
    // Notify the user
    val infoType = SignalServiceGroup.Type.MEMBER_ADDED
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, name, membersToAdd, admins, threadID, sentTime)
}

fun MessageSender.removeMembers(groupPublicKey: String, membersToRemove: List<String>) {
    val context = MessagingModuleConfiguration.shared.context
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't remove members from nonexistent closed group.")
        throw Error.NoThread
    }
    if (membersToRemove.isEmpty() || membersToRemove.contains(userPublicKey)) {
        Log.d("Loki", "Invalid closed group update.")
        throw Error.InvalidClosedGroupUpdate
    }
    val admins = group.admins.map { it.toString() }
    if (!admins.contains(userPublicKey)) {
        Log.d("Loki", "Only an admin can remove members from a group.")
        throw Error.InvalidClosedGroupUpdate
    }
    val updatedMembers = group.members.map { it.toString() }.toSet() - membersToRemove
    if (membersToRemove.any { it in admins } && updatedMembers.isNotEmpty()) {
        Log.d("Loki", "Can't remove admin from closed group unless the group is destroyed entirely.")
        throw Error.InvalidClosedGroupUpdate
    }
    // Save the new group members
    storage.updateMembers(groupID, updatedMembers.map { Address.fromSerialized(it) })
    // Update the zombie list
    val oldZombies = storage.getZombieMembers(groupID)
    storage.setZombieMembers(groupID, oldZombies.minus(membersToRemove).map { Address.fromSerialized(it) })
    val removeMembersAsData = membersToRemove.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val name = group.title
    // Send the update to the group
    val memberUpdateKind = LegacyGroupControlMessage.Kind.MembersRemoved(removeMembersAsData)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = LegacyGroupControlMessage(memberUpdateKind, groupID)
    closedGroupControlMessage.sentTimestamp = sentTime
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Send the new encryption key pair to the remaining group members.
    // At this stage we know the user is admin, no need to test.
    generateAndSendNewEncryptionKeyPair(groupPublicKey, updatedMembers)
    // Notify the user
    // We don't display zombie members in the notification as users have already been notified when those members left
    val notificationMembers = membersToRemove.minus(oldZombies)
    if (notificationMembers.isNotEmpty()) {
        // No notification to display when only zombies have been removed
        val infoType = SignalServiceGroup.Type.MEMBER_REMOVED
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, infoType, name, notificationMembers, admins, threadID, sentTime)
    }
}

suspend fun MessageSender.leave(groupPublicKey: String, deleteThread: Boolean = false) {
    val channel = Channel<Result<Unit>>()
    val job = GroupLeavingJob(groupPublicKey, completeChannel = channel, deleteThread)
    JobQueue.shared.add(job)

    channel.receive().getOrThrow()
}

fun MessageSender.generateAndSendNewEncryptionKeyPair(groupPublicKey: String, targetMembers: Collection<String>) {
    // Prepare
    val storage = MessagingModuleConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't update nonexistent closed group.")
        throw Error.NoThread
    }
    if (!group.admins.map { it.toString() }.contains(userPublicKey)) {
        Log.d("Loki", "Can't distribute new encryption key pair as non-admin.")
        throw Error.InvalidClosedGroupUpdate
    }
    // Generate the new encryption key pair
    val newKeyPair = Curve25519.generateKeyPair().let {
        ECKeyPair(
            DjbECPublicKey(it.pubKey.data),
            DjbECPrivateKey(it.secretKey.data),
        )
    }
    // Replace call will not succeed if no value already set
    pendingKeyPairs.putIfAbsent(groupPublicKey,Optional.absent())
    do {
        // Make sure we set the pending key pair or wait until it is not null
    } while (!pendingKeyPairs.replace(groupPublicKey,Optional.absent(),Optional.fromNullable(newKeyPair)))
    // Distribute it
    sendEncryptionKeyPair(groupPublicKey, newKeyPair, targetMembers)?.success {
        // Store it * after * having sent out the message to the group
        storage.addClosedGroupEncryptionKeyPair(newKeyPair, groupPublicKey, SnodeAPI.nowWithOffset)
        pendingKeyPairs[groupPublicKey] = Optional.absent()
    }
}

fun MessageSender.sendEncryptionKeyPair(groupPublicKey: String, newKeyPair: ECKeyPair, targetMembers: Collection<String>, targetUser: String? = null, force: Boolean = true): Promise<Unit, Exception>? {
    val destination = targetUser ?: GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val proto = SignalServiceProtos.KeyPair.newBuilder()
    proto.publicKey = ByteString.copyFrom(newKeyPair.publicKey.serialize().removingIdPrefixIfNeeded())
    proto.privateKey = ByteString.copyFrom(newKeyPair.privateKey.serialize())
    val plaintext = proto.build().toByteArray()
    val wrappers = targetMembers.map { publicKey ->
        val ciphertext = MessageEncrypter.encrypt(plaintext, publicKey)
        LegacyGroupControlMessage.KeyPairWrapper(publicKey, ByteString.copyFrom(ciphertext))
    }
    val kind = LegacyGroupControlMessage.Kind.EncryptionKeyPair(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), wrappers)
    val sentTime = SnodeAPI.nowWithOffset
    val closedGroupControlMessage = LegacyGroupControlMessage(kind, null)
    closedGroupControlMessage.sentTimestamp = sentTime
    return if (force) {
        val isSync = MessagingModuleConfiguration.shared.storage.getUserPublicKey() == destination
        MessageSender.sendNonDurably(closedGroupControlMessage, Address.fromSerialized(destination), isSyncMessage = isSync)
    } else {
        MessageSender.send(closedGroupControlMessage, Address.fromSerialized(destination))
        null
    }
}

fun MessageSender.sendLatestEncryptionKeyPair(publicKey: String, groupPublicKey: String) {
    val storage = MessagingModuleConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't send encryption key pair for nonexistent closed group.")
        throw Error.NoThread
    }
    val members = group.members.map { it.toString() }
    if (!members.contains(publicKey)) {
        Log.d("Loki", "Refusing to send latest encryption key pair to non-member.")
        return
    }
    // Get the latest encryption key pair
    val encryptionKeyPair = pendingKeyPairs[groupPublicKey]?.orNull()
        ?: storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: return
    // Send it
    val proto = SignalServiceProtos.KeyPair.newBuilder()
    proto.publicKey = ByteString.copyFrom(encryptionKeyPair.publicKey.serialize().removingIdPrefixIfNeeded())
    proto.privateKey = ByteString.copyFrom(encryptionKeyPair.privateKey.serialize())
    val plaintext = proto.build().toByteArray()
    val ciphertext = MessageEncrypter.encrypt(plaintext, publicKey)
    Log.d("Loki", "Sending latest encryption key pair to: $publicKey.")
    val wrapper = LegacyGroupControlMessage.KeyPairWrapper(publicKey, ByteString.copyFrom(ciphertext))
    val kind = LegacyGroupControlMessage.Kind.EncryptionKeyPair(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), listOf(wrapper))
    val closedGroupControlMessage = LegacyGroupControlMessage(kind, groupID)
    MessageSender.send(closedGroupControlMessage, Address.fromSerialized(publicKey))
}