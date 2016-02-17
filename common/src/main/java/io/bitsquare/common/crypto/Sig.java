/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.common.crypto;

import com.google.common.base.Charsets;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.*;

/**
 * StorageSignatureKeyPair/STORAGE_SIGN_KEY_ALGO: That is used for signing the data to be stored to the P2P network (by flooding).
 * The algo is selected because it originated from the TomP2P version which used DSA.
 * Changing to EC keys might be considered.
 * <p>
 * MsgSignatureKeyPair/MSG_SIGN_KEY_ALGO/MSG_SIGN_ALGO: That is used when sending a message to a peer which is encrypted and signed.
 * Changing to EC keys might be considered.
 */
public class Sig {
    private static final Logger log = LoggerFactory.getLogger(Sig.class);

    public static final String KEY_ALGO = "DSA";
    private static final String ALGO = "SHA256withDSA";


    /**
     * @return keyPair
     */
    public static KeyPair generateKeyPair() {
        long ts = System.currentTimeMillis();
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGO, "BC");
            keyPairGenerator.initialize(1024);
            KeyPair keyPair = keyPairGenerator.genKeyPair();
            log.trace("Generate msgSignatureKeyPair needed {} ms", System.currentTimeMillis() - ts);
            return keyPair;
        } catch (NoSuchAlgorithmException | NoSuchProviderException e) {
            e.printStackTrace();
            throw new RuntimeException("Could not create key.");
        }
    }


    /**
     * @param privateKey
     * @param data
     * @return
     * @throws SignatureException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     */
    public static byte[] sign(PrivateKey privateKey, byte[] data) throws CryptoException {
        try {
            Signature sig = Signature.getInstance(ALGO, "BC");
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (SignatureException | NoSuchProviderException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new CryptoException("Signing failed. " + e.getMessage());
        }
    }

    /**
     * @param privateKey
     * @param message    UTF-8 encoded message to sign
     * @return Base64 encoded signature
     * @throws SignatureException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     */
    public static String sign(PrivateKey privateKey, String message) throws CryptoException {
        byte[] sigAsBytes = sign(privateKey, message.getBytes(Charsets.UTF_8));
        return Base64.toBase64String(sigAsBytes);
    }

    /**
     * @param publicKey
     * @param data
     * @param signature
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws SignatureException
     */
    public static boolean verify(PublicKey publicKey, byte[] data, byte[] signature) throws CryptoException {
        try {
            Signature sig = Signature.getInstance(ALGO, "BC");
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (SignatureException | NoSuchProviderException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new CryptoException("Signature verification failed. " + e.getMessage());
        }
    }

    /**
     * @param publicKey
     * @param message   UTF-8 encoded message
     * @param signature Base64 encoded signature
     * @return
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws SignatureException
     */
    public static boolean verify(PublicKey publicKey, String message, String signature) throws CryptoException {
        return verify(publicKey, message.getBytes(Charsets.UTF_8), Base64.decode(signature));
    }
}

