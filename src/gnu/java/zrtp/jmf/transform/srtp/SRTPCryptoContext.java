/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 *
 * 
 * Some of the code in this class is derived from ccRtp's SRTP implementation,
 * which has the following copyright notice: 
 *
  Copyright (C) 2004-2006 the Minisip Team

  This library is free software; you can redistribute it and/or
  modify it under the terms of the GNU Lesser General Public
  License as published by the Free Software Foundation; either
  version 2.1 of the License, or (at your option) any later version.

  This library is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
  Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public
  License along with this library; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307 USA
*/
package gnu.java.zrtp.jmf.transform.srtp;

import java.util.Arrays;

import gnu.java.zrtp.jmf.transform.RawPacket;

import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.macs.*;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersForSkein;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.Mac;

import org.bouncycastle.crypto.engines.AESFastEngine;
import org.bouncycastle.crypto.engines.TwofishEngine;


/**
 * SRTPCryptoContext class is the core class of SRTP implementation.
 * There can be multiple SRTP sources in one SRTP session. And each SRTP stream
 * has a corresponding SRTPCryptoContext object, identified by SSRC. In this
 * way, different sources can be protected independently.
 * 
 * SRTPCryptoContext class acts as a manager class and maintains all the
 * information used in SRTP transformation. It is responsible for deriving
 * encryption keys / salting keys / authentication keys from master keys. And 
 * it will invoke certain class to encrypt / decrypt (transform / reverse
 * transform) RTP packets. It will hold a replay check db and do replay check
 * against incoming packets.
 * 
 * Refer to section 3.2 in RFC3711 for detailed description of cryptographic
 * context.
 * 
 * Cryptographic related parameters, i.e. encryption mode / authentication mode,
 * master encryption key and master salt key are determined outside the scope
 * of SRTP implementation. They can be assigned manually, or can be assigned
 * automatically using some key management protocol, such as MIKEY (RFC3880) or
 * Phil Zimmermann's ZRTP protocol.
 * 
 * @author Bing SU (nova.su@gmail.com)
 */
public class SRTPCryptoContext
{
    /**
     * The replay check windows size
     */
    private static final long REPLAY_WINDOW_SIZE = 64;
    
    /**
     * RTP SSRC of this cryptographic context
     */
    private long ssrc;
    
    /**
     * Master key identifier
     */
    private byte[] mki;

    /**
     * Roll-Over-Counter, see RFC3711 section 3.2.1 for detailed description 
     */
    private int roc;
    
    /**
     * Roll-Over-Counter guessed from packet
     */
    private int guessedROC;
    
    /**
     * RTP sequence number of the packet current processing 
     */
    private int seqNum;
    
    /**
     * Whether we have the sequence number of current packet
     */
    private boolean seqNumSet;
    
    /**
     * Key Derivation Rate, used to derive session keys from master keys
     */
    private long keyDerivationRate;

    /**
     * Bit mask for replay check
     */
    private long replayWindow;

    /**
     * Master encryption key
     */
    private byte[] masterKey;
    
    /**
     * Master salting key
     */
    private byte[] masterSalt;

    /**
     * Derived session encryption key
     */
    private byte[] encKey;

    /**
     * Derived session authentication key
     */
    private byte[] authKey;
    
    /**
     * Derived session salting key
     */
    private byte[] saltKey;

    /**
     * Encryption / Authentication policy for this session
     */
    private final SRTPPolicy policy;
    
    /**
     * The HMAC object we used to do packet authentication
     */
    private Mac mac;             // used for various HMAC computations
    
    // The symmetric cipher engines we need here
    private BlockCipher cipher = null;
    private BlockCipher cipherF8 = null; // used inside F8 mode only
    
    // implements the counter cipher mode for RTP according to RFC 3711
    private final SRTPCipherCTR cipherCtr = new SRTPCipherCTR();

    // Here some fields that a allocated here or in constructor. The methods
    // use these fields to avoid too many new operations
    
    private final byte[] tagStore;
    private final byte[] ivStore = new byte[16];
    private final byte[] rbStore = new byte[4];
    
    // this is some working store, used by some methods to avoid new operations
    // the methods must use this only to store some reults for immediate processing
    private final byte[] tempStore = new byte[100];

    /**
     * Construct an empty SRTPCryptoContext using ssrc.
     * The other parameters are set to default null value.
     * 
     * @param ssrc SSRC of this SRTPCryptoContext
     */
    public SRTPCryptoContext(long ssrcIn)
    {
        ssrc = ssrcIn;
        mki = null;
        roc = 0;
        guessedROC = 0;
        seqNum = 0;
        keyDerivationRate = 0;
        masterKey = null;
        masterSalt = null;
        encKey = null;
        authKey = null;
        saltKey = null;
        seqNumSet = false;
        policy = null;
        tagStore = null;
    }

    /**
     * Construct a normal SRTPCryptoContext based on the given parameters.
     * 
     * @param ssrc
     *            the RTP SSRC that this SRTP cryptographic context protects.
     * @param roc
     *            the initial Roll-Over-Counter according to RFC 3711. These are
     *            the upper 32 bit of the overall 48 bit SRTP packet index.
     *            Refer to chapter 3.2.1 of the RFC.
     * @param keyDerivationRate
     *            the key derivation rate defines when to recompute the SRTP
     *            session keys. Refer to chapter 4.3.1 in the RFC.
     * @param masterKey
     *            byte array holding the master key for this SRTP cryptographic
     *            context. Refer to chapter 3.2.1 of the RFC about the role of
     *            the master key.
     * @param masterSalt
     *            byte array holding the master salt for this SRTP cryptographic
     *            context. It is used to computer the initialization vector that
     *            in turn is input to compute the session key, session
     *            authentication key and the session salt.
     * @param policy
     *            SRTP policy for this SRTP cryptographic context, defined the
     *            encryption algorithm, the authentication algorithm, etc
     */
    public SRTPCryptoContext(long ssrcIn, int rocIn, long kdr,
            byte[] masterK, byte[] masterS, SRTPPolicy policyIn) 
    {
        ssrc = ssrcIn;
        mki = null;
        roc = rocIn;
        guessedROC = 0;
        seqNum = 0;
        keyDerivationRate = kdr;
        seqNumSet = false;

        policy = policyIn;

        masterKey = new byte[policy.getEncKeyLength()];
        System.arraycopy(masterK, 0, masterKey, 0, policy.getEncKeyLength());

        masterSalt = new byte[policy.getSaltKeyLength()];
        System.arraycopy(masterS, 0, masterSalt, 0, policy.getSaltKeyLength());

        switch (policy.getEncType()) {
        case SRTPPolicy.NULL_ENCRYPTION:
            encKey = null;
            saltKey = null;
            break;

        case SRTPPolicy.AESF8_ENCRYPTION:
            cipherF8 = new AESFastEngine();

        case SRTPPolicy.AESCM_ENCRYPTION:
            cipher = new AESFastEngine();
            encKey = new byte[this.policy.getEncKeyLength()];
            saltKey = new byte[this.policy.getSaltKeyLength()];
            break;

       case SRTPPolicy.TWOFISHF8_ENCRYPTION:
            cipherF8 = new TwofishEngine();

       case SRTPPolicy.TWOFISH_ENCRYPTION:
            cipher = new TwofishEngine();
            encKey = new byte[this.policy.getEncKeyLength()];
            saltKey = new byte[this.policy.getSaltKeyLength()];
            break;
        }
        
        switch (policy.getAuthType()) {
        case SRTPPolicy.NULL_AUTHENTICATION:
            authKey = null;
            tagStore = null;
            break;

        case SRTPPolicy.HMACSHA1_AUTHENTICATION:
            mac = new HMac(new SHA1Digest());
            authKey = new byte[policy.getAuthKeyLength()];
            tagStore = new byte[mac.getMacSize()];
            break;
            
        case SRTPPolicy.SKEIN_AUTHENTICATION:
            mac = new SkeinMac();
            authKey = new byte[policy.getAuthKeyLength()];
            tagStore = new byte[policy.getAuthTagLength()];
            break;

        default:
            tagStore = null;
        }
    }

    /**
     * Get the authentication tag length of this SRTP cryptographic context
     * 
     * @return the authentication tag length of this SRTP cryptographic context
     */
    public int getAuthTagLength() {
        return policy.getAuthTagLength();
    }

    /**
     * Get the MKI length of this SRTP cryptographic context
     * 
     * @return the MKI length of this SRTP cryptographic context
     */
    public int getMKILength() {
        if (mki != null) {
            return mki.length;
        } else {
            return 0;
        }
    }

    /**
     * Get the SSRC of this SRTP cryptographic context
     *
     * @return the SSRC of this SRTP cryptographic context
     */
    public long getSSRC() {
        return ssrc;
    }

    /**
     * Get the Roll-Over-Counter of this SRTP cryptographic context
     *
     * @return the Roll-Over-Counter of this SRTP cryptographic context
     */
    public int getROC() {
        return roc;
    }

    /**
     * Set the Roll-Over-Counter of this SRTP cryptographic context
     *
     * @param roc the Roll-Over-Counter of this SRTP cryptographic context
     */
    public void setROC(int rocIn) {
        roc = rocIn;
    }

    /**
     * Transform a RTP packet into a SRTP packet. 
     * This method is called when a normal RTP packet ready to be sent.
     * 
     * Operations done by the transformation may include: encryption, using
     * either Counter Mode encryption, or F8 Mode encryption, adding
     * authentication tag, currently HMC SHA1 method.
     * 
     * Both encryption and authentication functionality can be turned off
     * as long as the SRTPPolicy used in this SRTPCryptoContext is requires no
     * encryption and no authentication. Then the packet will be sent out
     * untouched. However this is not encouraged. If no SRTP feature is enabled,
     * then we shall not use SRTP TransformConnector. We should use the original
     * method (RTPManager managed transportation) instead.  
     * 
     * @param pkt the RTP packet that is going to be sent out
     */
    public void transformPacket(RawPacket pkt) {
        /* Encrypt the packet using Counter Mode encryption */
        if (policy.getEncType() == SRTPPolicy.AESCM_ENCRYPTION || 
                policy.getEncType() == SRTPPolicy.TWOFISH_ENCRYPTION) {
            processPacketAESCM(pkt);
        }

        /* Encrypt the packet using F8 Mode encryption */
        else if (policy.getEncType() == SRTPPolicy.AESF8_ENCRYPTION ||
                policy.getEncType() == SRTPPolicy.TWOFISHF8_ENCRYPTION) {
            processPacketAESF8(pkt);
        }

        /* Authenticate the packet */
        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION) {
            authenticatePacket(pkt, roc);
            pkt.append(tagStore, policy.getAuthTagLength());
        }

        /* Update the ROC if necessary */
        int seqNo = PacketManipulator.GetRTPSequenceNumber(pkt);
        if (seqNo == 0xFFFF) {
            roc++;
        }
    }

    /**
     * Transform a SRTP packet into a RTP packet.
     * This method is called when a SRTP packet is received.
     * 
     * Operations done by the this operation include:
     * Authentication check, Packet replay check and decryption.
     * 
     * Both encryption and authentication functionality can be turned off
     * as long as the SRTPPolicy used in this SRTPCryptoContext is requires no
     * encryption and no authentication. Then the packet will be sent out
     * untouched. However this is not encouraged. If no SRTP feature is enabled,
     * then we shall not use SRTP TransformConnector. We should use the original
     * method (RTPManager managed transportation) instead.  
     * 
     * @param pkt the RTP packet that is just received
     * @return true if the packet can be accepted
     *         false if the packet failed authentication or failed replay check 
     */
    public boolean reverseTransformPacket(RawPacket pkt) {
        int seqNo = PacketManipulator.GetRTPSequenceNumber(pkt);

        if (!seqNumSet) {
            seqNumSet = true;
            seqNum = seqNo;
        }
        // Guess the SRTP index (48 bit), see rFC 3711, 3.3.1
        // Stores the guessed roc in this.guessedROC
        long guessedIndex = guessIndex(seqNo);

        /* Replay control */
        if (!checkReplay(seqNo, guessedIndex)) {
            return false;
        }
        /* Authenticate the packet */
        if (policy.getAuthType() != SRTPPolicy.NULL_AUTHENTICATION) {
            int tagLength = policy.getAuthTagLength();

            // get original authentication and store in tempStore
            pkt.readRegionToBuff(pkt.getLength() - tagLength,
                    tagLength, tempStore);

            pkt.shrink(tagLength);

            // save computed authentication in tagStore
            authenticatePacket(pkt, guessedROC);

            for (int i = 0; i < tagLength; i++) {
                if ((tempStore[i]&0xff) == (tagStore[i]&0xff))
                    continue;
                else 
                    return false;
            }
        }

        /* Decrypt the packet using Counter Mode encryption*/
        if (policy.getEncType() == SRTPPolicy.AESCM_ENCRYPTION ||
                policy.getEncType() == SRTPPolicy.TWOFISH_ENCRYPTION) {
            processPacketAESCM(pkt);
        }

        /* Decrypt the packet using F8 Mode encryption*/
        else if (policy.getEncType() == SRTPPolicy.AESF8_ENCRYPTION ||
                    policy.getEncType() == SRTPPolicy.TWOFISHF8_ENCRYPTION) {
            processPacketAESF8(pkt);
        }

        update(seqNo, guessedIndex);

        return true;
    }

    /**
     * Perform Counter Mode AES encryption / decryption 
     * @param pkt the RTP packet to be encrypted / decrypted
     */
    public void processPacketAESCM(RawPacket pkt) {
        long ssrc = PacketManipulator.GetRTPSSRC(pkt);
        int seqNum = PacketManipulator.GetRTPSequenceNumber(pkt);
        long index = ((long) this.roc << 16) | seqNum;

        /* Compute the CM IV (refer to chapter 4.1.1 in RFC 3711):
        *
        * k_s   XX XX XX XX XX XX XX XX XX XX XX XX XX XX
        * SSRC              XX XX XX XX
        * index                         XX XX XX XX XX XX
        * ------------------------------------------------------XOR
        * IV    XX XX XX XX XX XX XX XX XX XX XX XX XX XX 00 00
        *        0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
        */
        ivStore[0] = saltKey[0];
        ivStore[1] = saltKey[1];
        ivStore[2] = saltKey[2];
        ivStore[3] = saltKey[3];

        // The shifts transform the ssrc and index into network order
        ivStore[4] = (byte) (((ssrc >> 24) & 0xff) ^ this.saltKey[4]);
        ivStore[5] = (byte) (((ssrc >> 16) & 0xff) ^ this.saltKey[5]);
        ivStore[6] = (byte) (((ssrc >> 8) & 0xff) ^ this.saltKey[6]);
        ivStore[7] = (byte) ((ssrc & 0xff) ^ this.saltKey[7]);

        ivStore[8] = (byte) (((index >> 40) & 0xff) ^ this.saltKey[8]);
        ivStore[9] = (byte) (((index >> 32) & 0xff) ^ this.saltKey[9]);
        ivStore[10] = (byte) (((index >> 24) & 0xff) ^ this.saltKey[10]);
        ivStore[11] = (byte) (((index >> 16) & 0xff) ^ this.saltKey[11]);
        ivStore[12] = (byte) (((index >> 8) & 0xff) ^ this.saltKey[12]);
        ivStore[13] = (byte) ((index & 0xff) ^ this.saltKey[13]);

        ivStore[14] = ivStore[15] = 0;

        final int payloadOffset = PacketManipulator.GetRTPHeaderLength(pkt);
        final int payloadLength = PacketManipulator.GetRTPPayloadLength(pkt);

        cipherCtr.process(cipher, pkt.getBuffer(), pkt.getOffset() + payloadOffset,
                payloadLength, ivStore);
    }

    /**
     * Perform F8 Mode AES encryption / decryption
     *
     * @param pkt the RTP packet to be encrypted / decrypted
     */
    public void processPacketAESF8(RawPacket pkt) {
        // byte[] iv = new byte[16];

        /* Create the F8 IV (refer to chapter 4.1.2.2 in RFC 3711):
        *
        * IV = 0x00 || M || PT || SEQ  ||      TS    ||    SSRC   ||    ROC
        *      8Bit  1bit  7bit  16bit       32bit        32bit        32bit
        * ------------\     /--------------------------------------------------
        *       XX       XX      XX XX   XX XX XX XX   XX XX XX XX  XX XX XX XX
        *        0        1       2  3    4  5  6  7    8  9 10 11  12 13 14 15
        */
        // 11 bytes of the RTP header are the 11 bytes of the iv
        // the first byte of the RTP header is not used.
        System.arraycopy(pkt.getBuffer(), pkt.getOffset(), ivStore, 0, 12);
        ivStore[0] = 0;
       
        // set the ROC in network order into IV
        ivStore[12] = (byte) (this.roc >> 24);
        ivStore[13] = (byte) (this.roc >> 16);
        ivStore[14] = (byte) (this.roc >> 8);
        ivStore[15] = (byte) this.roc;

        final int payloadOffset = PacketManipulator.GetRTPHeaderLength(pkt);
        final int payloadLength = PacketManipulator.GetRTPPayloadLength(pkt);

        SRTPCipherF8.process(cipher, pkt.getBuffer(), pkt.getOffset() + payloadOffset,
                payloadLength, ivStore, cipherF8);
    }

    /**
     * Authenticate a packet.
     * 
     * Calculated authentication tag is returned.
     *
     * @param pkt the RTP packet to be authenticated
     * @return authentication tag of pkt
     */
    private void authenticatePacket(RawPacket pkt, int rocIn) {

        mac.update(pkt.getBuffer(), 0, pkt.getLength());
        // byte[] rb = new byte[4];
        rbStore[0] = (byte) (rocIn >> 24);
        rbStore[1] = (byte) (rocIn >> 16);
        rbStore[2] = (byte) (rocIn >> 8);
        rbStore[3] = (byte) rocIn;
        mac.update(rbStore, 0, rbStore.length);
        mac.doFinal(tagStore, 0);
    }

    /**
     * Checks if a packet is a replayed on based on its sequence number.
     * 
     * This method supports a 64 packet history relative the the given
     * sequence number.
     *
     * Sequence Number is guaranteed to be real (not faked) through 
     * authentication.
     * 
     * @param seqNo sequence number of the packet
     * @return true if this sequence number indicates the packet is not a
     * replayed one, false if not
     */
    boolean checkReplay(int seqNo, long guessedIndex) {
        // compute the index of previously received packet and its
        // delta to the new received packet
        long localIndex = (((long) this.roc) << 16) | this.seqNum;
        long delta = guessedIndex - localIndex;
        
        if (delta > 0) {
            /* Packet not yet received */
            return true;
        } else {
            if (-delta > REPLAY_WINDOW_SIZE) {
                /* Packet too old */
                return false;
            } else {
                if (((this.replayWindow >> (-delta)) & 0x1) != 0) {
                    /* Packet already received ! */
                    return false;
                } else {
                    /* Packet not yet received */
                    return true;
                }
            }
        }
    }

    /**
     * Compute the initialization vector, used later by encryption algorithms,
     * based on the lable, the packet index, key derivation rate and master
     * salt key. 
     * 
     * @param label label specified for each type of iv 
     * @param index 48bit RTP packet index
     */
    private void computeIv(long label, long index) {
        long key_id;

        if (keyDerivationRate == 0) {
            key_id = label << 48;
        } else {
            key_id = ((label << 48) | (index / keyDerivationRate));
        }
        /* compute the IV
        key_id:                           XX XX XX XX XX XX XX
        master_salt: XX XX XX XX XX XX XX XX XX XX XX XX XX XX
        ------------------------------------------------------------ XOR
        IV:          XX XX XX XX XX XX XX XX XX XX XX XX XX XX 00 00
        *             0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
        */
        for (int i = 0; i < 7; i++) {
            ivStore[i] = masterSalt[i];
        }
        for (int i = 7; i < 14; i++) {
            ivStore[i] = (byte) ((byte) (0xFF & (key_id >> (8 * (13 - i)))) ^ masterSalt[i]);
        }
        ivStore[14] = ivStore[15] = 0;
    }

    /**
     * Derives the srtp session keys from the master key
     * 
     * @param index
     *            the 48 bit SRTP packet index
     */
    public void deriveSrtpKeys(long index) {
        // compute the session encryption key
        long label = 0;
        computeIv(label, index);

        KeyParameter encryptionKey = new KeyParameter(masterKey);
        cipher.init(true, encryptionKey);
        Arrays.fill(masterKey, (byte)0);

        cipherCtr.getCipherStream(cipher, encKey, policy.getEncKeyLength(), ivStore);

        // compute the session authentication key
        if (authKey != null) {
            label = 0x01;
            computeIv(label, index);
            cipherCtr.getCipherStream(cipher, authKey, policy.getAuthKeyLength(), ivStore);

            switch ((policy.getAuthType())) {
            case SRTPPolicy.HMACSHA1_AUTHENTICATION:
                KeyParameter key =  new KeyParameter(authKey);
                mac.init(key);
                break;

            case SRTPPolicy.SKEIN_AUTHENTICATION:
                // Skein MAC uses number of bits as MAC size, not just bytes
                ParametersForSkein pfs = new ParametersForSkein(new KeyParameter(authKey),
                        ParametersForSkein.Skein512, tagStore.length*8);
                mac.init(pfs);
                break;
            }
        }
        Arrays.fill(authKey, (byte)0);

        // compute the session salt
        label = 0x02;
        computeIv(label, index);
        cipherCtr.getCipherStream(cipher, saltKey, policy.getSaltKeyLength(), ivStore);
        Arrays.fill(masterSalt, (byte)0);
        
        // As last step: initialize cipher with derived encryption key.
        if (cipherF8 != null)
            SRTPCipherF8.deriveForIV(cipherF8, encKey, saltKey);
        encryptionKey = new KeyParameter(encKey);
        cipher.init(true, encryptionKey);
        Arrays.fill(encKey, (byte)0);
    }

    /**
     * Compute (guess) the new SRTP index based on the sequence number of a
     * received RTP packet.
     * 
     * @param seqNum
     *            sequence number of the received RTP packet
     * @return the new SRTP packet index
     */
    private long guessIndex(int seqNo) {

        if (this.seqNum < 32768) {
            if (seqNo - this.seqNum > 32768) {
                guessedROC = roc - 1;
            } else {
                guessedROC = roc;
            }
        } else {
            if (seqNum - 32768 > seqNo) {
                guessedROC = roc + 1;
            } else {
                guessedROC = roc;
            }
        }

        return ((long) guessedROC) << 16 | seqNo;
    }

    /**
     * Update the SRTP packet index.
     * 
     * This method is called after all checks were successful. 
     * See section 3.3.1 in RFC3711 for detailed description.
     * 
     * @param seqNo sequence number of the accepted packet
     */
    private void update(int seqNo, long guessedIndex) {
        long delta = guessedIndex - (((long) this.roc) << 16 | this.seqNum);

        /* update the replay bit mask */
        if( delta > 0 ){
          replayWindow = replayWindow << delta;
          replayWindow |= 1;
        }
        else {
          replayWindow |= ( 1 << delta );
        }

        if (seqNo > seqNum) {
            seqNum = seqNo & 0xffff;
        }
        if (this.guessedROC > this.roc) {
            roc = guessedROC;
            seqNum = seqNo & 0xffff;
        }
    }

    /**
     * Derive a new SRTPCryptoContext for use with a new SSRC
     * 
     * This method returns a new SRTPCryptoContext initialized with the data of
     * this SRTPCryptoContext. Replacing the SSRC, Roll-over-Counter, and the
     * key derivation rate the application cab use this SRTPCryptoContext to
     * encrypt / decrypt a new stream (Synchronization source) inside one RTP
     * session.
     * 
     * Before the application can use this SRTPCryptoContext it must call the
     * deriveSrtpKeys method.
     * 
     * @param ssrc
     *            The SSRC for this context
     * @param roc
     *            The Roll-Over-Counter for this context
     * @param deriveRate
     *            The key derivation rate for this context
     * @return a new SRTPCryptoContext with all relevant data set.
     */
    public SRTPCryptoContext deriveContext(long ssrc, int roc, long deriveRate) {
        SRTPCryptoContext pcc = null;
        pcc = new SRTPCryptoContext(ssrc, roc, deriveRate, masterKey,
                masterSalt, policy);
        return pcc;
    }
}
