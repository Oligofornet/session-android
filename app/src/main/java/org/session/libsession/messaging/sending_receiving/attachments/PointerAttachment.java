package org.session.libsession.messaging.sending_receiving.attachments;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.session.libsignal.utilities.guava.Optional;
import org.session.libsignal.messages.SignalServiceAttachment;
import org.session.libsignal.utilities.Base64;
import org.session.libsignal.protos.SignalServiceProtos;

import java.util.LinkedList;
import java.util.List;

public class PointerAttachment extends Attachment {

  private PointerAttachment(@NonNull String contentType, int transferState, long size,
                            @Nullable String fileName,  @NonNull String location,
                            @Nullable String key, @Nullable String relay,
                            @Nullable byte[] digest, @Nullable String fastPreflightId, boolean voiceNote,
                            int width, int height, @Nullable String caption, String url)
  {
    super(contentType, transferState, size, fileName, location, key, relay, digest, fastPreflightId, voiceNote, width, height, false, caption, url, -1L);
  }

  @Nullable
  @Override
  public Uri getDataUri() {
    return null;
  }

  @Nullable
  @Override
  public Uri getThumbnailUri() {
    return null;
  }


  public static List<Attachment> forPointers(Optional<List<SignalServiceAttachment>> pointers) {
    List<Attachment> results = new LinkedList<>();

    if (pointers.isPresent()) {
      for (SignalServiceAttachment pointer : pointers.get()) {
        Optional<Attachment> result = forPointer(Optional.of(pointer));

        if (result.isPresent()) {
          results.add(result.get());
        }
      }
    }

    return results;
  }

  public static List<Attachment> forPointers(List<SignalServiceProtos.DataMessage.Quote.QuotedAttachment> pointers) {
    List<Attachment> results = new LinkedList<>();

    if (pointers != null) {
      for (SignalServiceProtos.DataMessage.Quote.QuotedAttachment pointer : pointers) {
        Optional<Attachment> result = forPointer(pointer);

        if (result.isPresent()) {
          results.add(result.get());
        }
      }
    }

    return results;
  }

  public static Optional<Attachment> forPointer(Optional<SignalServiceAttachment> pointer) {
    return forPointer(pointer, null);
  }

  public static Optional<Attachment> forPointer(Optional<SignalServiceAttachment> pointer, @Nullable String fastPreflightId) {
    if (!pointer.isPresent() || !pointer.get().isPointer()) return Optional.absent();

    String encodedKey = null;

    if (pointer.get().asPointer().getKey() != null) {
      encodedKey = Base64.encodeBytes(pointer.get().asPointer().getKey());
    }

    return Optional.of(new PointerAttachment(pointer.get().getContentType(),
            AttachmentState.PENDING.getValue(),
            pointer.get().asPointer().getSize().or(0),
            pointer.get().asPointer().getFilename(),
            String.valueOf(pointer.get().asPointer().getId()),
            encodedKey, null,
            pointer.get().asPointer().getDigest().orNull(),
            fastPreflightId,
            pointer.get().asPointer().getVoiceNote(),
            pointer.get().asPointer().getWidth(),
            pointer.get().asPointer().getHeight(),
            pointer.get().asPointer().getCaption().orNull(),
            pointer.get().asPointer().getUrl()));

  }

  public static Optional<Attachment> forPointer(SignalServiceProtos.AttachmentPointer pointer) {
    return Optional.of(new PointerAttachment(pointer.getContentType(),
            AttachmentState.PENDING.getValue(),
            (long)pointer.getSize(),
            pointer.getFileName(),
            String.valueOf(pointer != null ? pointer.getId() : 0),
            pointer.getKey() != null ? Base64.encodeBytes(pointer.getKey().toByteArray()) : null,
            null,
            pointer.getDigest().toByteArray(),
            null,
            false,
            pointer.getWidth(),
            pointer.getHeight(),
            pointer.getCaption(),
            pointer.getUrl()));
  }

  public static Optional<Attachment> forPointer(SignalServiceProtos.DataMessage.Quote.QuotedAttachment pointer) {
    SignalServiceProtos.AttachmentPointer thumbnail = pointer.getThumbnail();

    return Optional.of(new PointerAttachment(pointer.getContentType(),
            AttachmentState.PENDING.getValue(),
            thumbnail != null ? (long)thumbnail.getSize() : 0,
            thumbnail.getFileName(),
            String.valueOf(thumbnail != null ? thumbnail.getId() : 0),
            thumbnail != null && thumbnail.getKey() != null ? Base64.encodeBytes(thumbnail.getKey().toByteArray()) : null,
            null,
            thumbnail != null ? thumbnail.getDigest().toByteArray() : null,
            null,
            false,
            thumbnail != null ? thumbnail.getWidth() : 0,
            thumbnail != null ? thumbnail.getHeight() : 0,
            thumbnail != null ? thumbnail.getCaption() : null,
            thumbnail != null ? thumbnail.getUrl() : ""));
  }

  /**
   * Converts a Session Attachment to a Signal Attachment
   * @param attachment Session Attachment
   * @return Signal Attachment
   */
  public static Attachment forAttachment(org.session.libsession.messaging.messages.visible.Attachment attachment) {
    return new PointerAttachment(
            attachment.getContentType(),
            AttachmentState.PENDING.getValue(),
            attachment.getSizeInBytes(),
            attachment.getFilename(),
            null, Base64.encodeBytes(attachment.getKey()),
            null,
            attachment.getDigest(),
            null,
            attachment.getKind() == org.session.libsession.messaging.messages.visible.Attachment.Kind.VOICE_MESSAGE,
            attachment.getSize() != null ? attachment.getSize().getWidth() : 0,
            attachment.getSize() != null ? attachment.getSize().getHeight() : 0,
            attachment.getCaption(),
            attachment.getUrl()
    );
  }
}
