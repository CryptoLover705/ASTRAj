/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hashengineering.crypto;

import com.google.common.io.ByteStreams;
import org.astraj.core.Sha256Hash;
import org.astraj.core.Utils;
import org.spongycastle.util.encoders.Hex;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A Sha256Hash just wraps a byte[] so that equals and hashcode work correctly, allowing it to be used as keys in a
 * map. It also checks that the length is correct and provides a bit more type safety.
 */
public class Sha512Hash implements Serializable, Comparable {
    private byte[] bytes;
    public static final Sha512Hash ZERO_HASH = new Sha512Hash(new byte[64]);

    /**
     * Creates a Sha512Hash by wrapping the given byte array. It must be 64 bytes long.
     */
    public Sha512Hash(byte[] rawHashBytes) {
        checkArgument(rawHashBytes.length == 64);
        this.bytes = rawHashBytes;

    }

    /**
     * Creates a Sha512Hash by decoding the given hex string. It must be 64 characters long.
     */
    public Sha512Hash(String hexString) {
        checkArgument(hexString.length() == 64);
        this.bytes = Hex.decode(hexString);
    }

    /**
     * Calculates the (one-time) hash of contents and returns it as a new wrapped hash.
     */
    public static Sha512Hash create(byte[] contents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new Sha512Hash(digest.digest(contents));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    /**
     * Returns a hash of the given files contents. Reads the file fully into memory before hashing so only use with
     * small files.
     * @throws java.io.IOException
     */
    public static Sha512Hash hashFileContents(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            return create(ByteStreams.toByteArray(in));
        } finally {
            in.close();
        }
    }

    /**
     * Returns true if the hashes are equal.
     */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Sha512Hash)) return false;
        return Arrays.equals(bytes, ((Sha512Hash) other).bytes);
    }

    /**
     * Hash code of the byte array as calculated by {@link java.util.Arrays#hashCode()}. Note the difference between a SHA256
     * secure bytes and the type of quick/dirty bytes used by the Java hashCode method which is designed for use in
     * bytes tables.
     */
    @Override
    public int hashCode() {
        // Use the last 4 bytes, not the first 4 which are often zeros in Bitcoin.
        return (bytes[63] & 0xFF) | ((bytes[62] & 0xFF) << 8) | ((bytes[61] & 0xFF) << 16) | ((bytes[60] & 0xFF) << 24);
    }

    @Override
    public String toString() {
        return Utils.HEX.encode(bytes);
    }

    /**
     * Returns the bytes interpreted as a positive integer.
     */
    public BigInteger toBigInteger() {
        return new BigInteger(1, bytes);
    }

    public byte[] getBytes() {
        return bytes;
    }

    public Sha512Hash duplicate() {
        return new Sha512Hash(bytes);
    }

    @Override
    public int compareTo(Object o) {
        checkArgument(o instanceof Sha512Hash);
        int thisCode = this.hashCode();
        int oCode = ((Sha512Hash)o).hashCode();
        return thisCode > oCode ? 1 : (thisCode == oCode ? 0 : -1);
    }

    public Sha256Hash trim256()
    {
        byte [] result = new byte[32];
        for (int i = 0; i < 32; i++){
            result[i] = bytes[i];
        }
        return new Sha256Hash(result);
    }

    /*public Sha512Hash bitwiseAnd(Sha512Hash x)
    {
        byte [] result = new byte[64];
        for(int i = 0; i < 64; ++i)
            result[i] = bytes[i] & x.bytes[i];
        return Sha512Hash.create(result);
    } */
}
