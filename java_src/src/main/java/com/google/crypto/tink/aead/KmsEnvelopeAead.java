// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package com.google.crypto.tink.aead; // instead of subtle, because it depends on KeyTemplate.

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.Registry;
import com.google.crypto.tink.proto.KeyTemplate;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * This primitive implements <a href="https://cloud.google.com/kms/docs/data-encryption-keys">
 * envelope encryption</a>.
 *
 * <p>In envelope encryption, a user generates a data encryption key (DEK) locally, encrypts data
 * with the DEK, sends the DEK to a KMS to be encrypted (with a key managed by KMS), and then stores
 * the encrypted DEK with the encrypted data. At a later point, a user can retrieve the encrypted
 * data and the encyrpted DEK, use the KMS to decrypt the DEK, and use the decrypted DEK to decrypt
 * the data.
 *
 * <p>The ciphertext structure is as follows:
 *
 * <ul>
 *   <li>Length of the encrypted DEK: 4 bytes.
 *   <li>Encrypted DEK: variable length that is equal to the value specified in the last 4 bytes.
 *   <li>AEAD payload: variable length.
 * </ul>
 */
public final class KmsEnvelopeAead implements Aead {
  private static final byte[] EMPTY_AAD = new byte[0];
  private final KeyTemplate dekTemplate;
  private final Aead remote;
  private static final int LENGTH_ENCRYPTED_DEK = 4;

  private static Set<String> listSupportedDekKeyTypes() {
    HashSet<String> dekKeyTypeUrls = new HashSet<>();
    dekKeyTypeUrls.add("type.googleapis.com/google.crypto.tink.AesGcmKey");
    dekKeyTypeUrls.add("type.googleapis.com/google.crypto.tink.ChaCha20Poly1305Key");
    dekKeyTypeUrls.add("type.googleapis.com/google.crypto.tink.XChaCha20Poly1305Key");
    dekKeyTypeUrls.add("type.googleapis.com/google.crypto.tink.AesCtrHmacAeadKey");
    dekKeyTypeUrls.add("type.googleapis.com/google.crypto.tink.AesGcmSivKey");
    dekKeyTypeUrls.add("type.googleapis.com/google.crypto.tink.AesEaxKey");
    return Collections.unmodifiableSet(dekKeyTypeUrls);
  }

  private static final Set<String> supportedDekKeyTypes = listSupportedDekKeyTypes();

  public static boolean isSupportedDekKeyType(String dekKeyTypeUrl) {
    return supportedDekKeyTypes.contains(dekKeyTypeUrl);
  }

  public KmsEnvelopeAead(KeyTemplate dekTemplate, Aead remote) {
    if (!isSupportedDekKeyType(dekTemplate.getTypeUrl())) {
      throw new IllegalArgumentException(
          "Unsupported DEK key type: "
              + dekTemplate.getTypeUrl()
              + ". Only Tink AEAD key types are supported.");
    }
    this.dekTemplate = dekTemplate;
    this.remote = remote;
  }

  @Override
  public byte[] encrypt(final byte[] plaintext, final byte[] associatedData)
      throws GeneralSecurityException {
    // Generate a new DEK.
    byte[] dek = Registry.newKeyData(dekTemplate).getValue().toByteArray();
    // Wrap it with remote.
    byte[] encryptedDek = remote.encrypt(dek, EMPTY_AAD);
    // Use DEK to encrypt plaintext.
    Aead aead = Registry.getPrimitive(dekTemplate.getTypeUrl(), dek, Aead.class);
    byte[] payload = aead.encrypt(plaintext, associatedData);
    // Build ciphertext protobuf and return result.
    return buildCiphertext(encryptedDek, payload);
  }

  @Override
  public byte[] decrypt(final byte[] ciphertext, final byte[] associatedData)
      throws GeneralSecurityException {
    try {
      ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
      int encryptedDekSize = buffer.getInt();
      if (encryptedDekSize <= 0 || encryptedDekSize > (ciphertext.length - LENGTH_ENCRYPTED_DEK)) {
        throw new GeneralSecurityException("invalid ciphertext");
      }
      byte[] encryptedDek = new byte[encryptedDekSize];
      buffer.get(encryptedDek, 0, encryptedDekSize);
      byte[] payload = new byte[buffer.remaining()];
      buffer.get(payload, 0, buffer.remaining());
      // Use remote to decrypt encryptedDek.
      byte[] dek = remote.decrypt(encryptedDek, EMPTY_AAD);
      // Use DEK to decrypt payload.
      Aead aead = Registry.getPrimitive(dekTemplate.getTypeUrl(), dek, Aead.class);
      return aead.decrypt(payload, associatedData);
    } catch (IndexOutOfBoundsException
             | BufferUnderflowException
             | NegativeArraySizeException e) {
      throw new GeneralSecurityException("invalid ciphertext", e);
    }
  }

  private byte[] buildCiphertext(final byte[] encryptedDek, final byte[] payload) {
    return ByteBuffer.allocate(LENGTH_ENCRYPTED_DEK + encryptedDek.length + payload.length)
        .putInt(encryptedDek.length)
        .put(encryptedDek)
        .put(payload)
        .array();
  }
}
