package com.houseofkraft.nsp.encryption;

/*
 * AES Encryption for Next Socket Protocol
 * Copyright (c) 2022 houseofkraft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for specific language governing permissions and
 * limitations under the License.
 */

import com.houseofkraft.nsp.networking.NSPHeader;
import com.houseofkraft.nsp.tool.ByteTools;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Random;

public class AES {
    private SecretKey privateKey;
    private IvParameterSpec ivKey;
    private String encryptAlgorithm;
    private GCMParameterSpec gcmSpec;
    protected AESOption options;

    private final byte[] recordSplit = NSPHeader.RECORD_SEPARATOR.toByteArray();
    private final byte[] groupSplit = NSPHeader.GROUP_SEPARATOR.toByteArray();

    /**
     * Generates a Password using a specified length and charset.
     * @param length Password Length
     * @param charset Charset
     * @return Generated Password
     */
    public static String generatePassword(int length, String charset) {
        StringBuilder passwordOutput = new StringBuilder();
        Random random = new Random();

        for (int i=0; i<length; i++) {
            passwordOutput.append(charset.charAt(random.nextInt(charset.length())));
        }
        return passwordOutput.toString();
    }

    /**
     * Generates a Password using a specified length, with the default ASCII charset.
     * @param length Password Length
     * @return Generated Password
     */
    public static String generatePassword(int length) {
        return generatePassword(length, "1234567890-=qwertyuiop[]asdfghjkl;'zxcvbnm,./~!@#$%^&*()_+QWERTYUIOP{}|ASDFGHJKL:ZXCVBNM<>?");
    }

    /** @return Combined Algorithm for Hashing */
    private String getCombinedAlgorithm() { return "PBKDF2WithHmacSHA" + options.getShaType().size(); }

    /**
     * Generates a key based on the Options, with optional IV generation.
     * @param genIV Generate IV
     * @return AES Builder
     */
    private AES generateKey(boolean genIV) throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(getCombinedAlgorithm());
        PBEKeySpec keySpec = new PBEKeySpec(
                options.getKeyPassword().toCharArray(),
                options.getKeySalt().getBytes(StandardCharsets.UTF_8),
                options.getIteration(),
                options.getKeyType().size()
        );

        this.privateKey = new SecretKeySpec(factory.generateSecret(keySpec).getEncoded(), "AES");

        if (genIV) {
            byte[] ivBuffer = new byte[16];
            new SecureRandom().nextBytes(ivBuffer);
            this.ivKey = new IvParameterSpec(ivBuffer);
        }
        return this;
    }

    /** @return Parameter Spec */
    private AlgorithmParameterSpec getParameterSpec() {
        switch (options.getAlgorithm()) {
            case CBC: return this.ivKey;
            case GCM: return this.gcmSpec;
            default: throw new IllegalArgumentException();
        }
    }

    /**
     * Updates the AES options, and verifies/generates all the required Key's based on the updated Options.
     * @param options AESOption
     * @return AES Builder
     */
    public AES updateOptions(AESOption options) throws IOException, GeneralSecurityException {
        this.options = options;
        if (options.verify()) {
            if (options.isAutoGenerated()) {
                if (options.getIv() != null) {
                    this.ivKey = options.getIv();
                    generateKey(false);
                } else {
                    generateKey(true);
                }
            } else {
                this.privateKey = options.getPrivateKey();
                this.ivKey = options.getIv();
            }
        }
        return this;
    }

    /**
     * Creates a KeyFile which will store all the encryption information such as Key Length, Private Key,
     * IV Length, Algorithm Type, etc. into one file which can be loaded later to re-generate everything without
     * requiring manual input.
     *
     * @param fileObject File Object to Write To
     * @throws IOException If there is any kind of file error.
     * @return AES Builder
     */
    public AES writeKeyFile(File fileObject) throws IOException {
        if (fileObject.exists()) {
            throw new IOException("file already exists");
        }

        byte[] keyBytes = this.privateKey.getEncoded();
        byte[] algoBytes = getCombinedAlgorithm().getBytes(StandardCharsets.UTF_8);
        byte[] keySizeBytes = ByteTools.intToBytes(options.getKeyType().size());
        byte[] ivBytes = this.ivKey.getIV();

        FileOutputStream fos = new FileOutputStream(fileObject);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(keySizeBytes);
        bos.write(algoBytes);
        bos.write(recordSplit);
        bos.write(keyBytes);
        bos.write(groupSplit);
        bos.write(ivBytes);
        fos.write(bos.toByteArray());

        bos.close();
        fos.close();
        return this;
    }

    public AES(AESOption option) throws GeneralSecurityException, IOException {
        updateOptions(option);

        switch (this.options.getAlgorithm()) {
            case CBC:
                this.encryptAlgorithm = "AES/CBC/PKCS5Padding";
                break;
            case GCM:
                this.encryptAlgorithm = "AES/GCM/NoPadding";
                this.gcmSpec = new GCMParameterSpec(16 * 8, ivKey.getIV());
                break;
        }
    }

    /**
     * Encrypts a ByteArray with the information received in the Options.
     * @param input ByteArray Input
     * @return Encrypted ByteArray
     * @throws GeneralSecurityException If there is an encryption error.
     */
    public byte[] encryptBytes(byte[] input) throws GeneralSecurityException {

        Cipher cipher = Cipher.getInstance(this.encryptAlgorithm);
        cipher.init(Cipher.ENCRYPT_MODE, this.privateKey, getParameterSpec());
        return cipher.doFinal(input);
    }

    /**
     * Decrypts a ByteArray with the information received in the Options.
     * @param input ByteArray Input
     * @return Decrypted ByteArray
     * @throws GeneralSecurityException If there is an encryption error.
     */
    public byte[] decryptBytes(byte[] input) throws GeneralSecurityException {

        Cipher cipher = Cipher.getInstance(this.encryptAlgorithm);
        cipher.init(Cipher.DECRYPT_MODE, this.privateKey, getParameterSpec());

        return cipher.doFinal(input);
    }

    /**
     * Decrypts a ByteArray with the information received in the Options as a String.
     * @param input ByteArray Input
     * @return Decrypted String
     * @throws GeneralSecurityException If there is an encryption error.
     */
    public String decryptByteString(byte[] input) throws GeneralSecurityException {

        Cipher cipher = Cipher.getInstance(this.encryptAlgorithm);
        cipher.init(Cipher.DECRYPT_MODE, this.privateKey, getParameterSpec());
        return new String(cipher.doFinal(input));
    }

    /**
     * Encrypts a ByteArray with the information received in the Options with a String input.
     * @param input String Input
     * @return Encrypted ByteArray
     * @throws GeneralSecurityException If there is an encryption error.
     */
    public byte[] encryptBytes(String input) throws GeneralSecurityException {
        return this.encryptBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Changes the Key based on the Password and Salt. If there is no salt used, the salt will be the same
     * as the password.
     *
     * @param password Password
     * @param salt Salt
     * @return AES Builder
     */
    public AES setPassword(String password, String salt) {
        options.setKeyPassword(password);
        if (salt.equals("")) {
            options.setKeySalt(salt);
        } else { options.setKeySalt(password); }
        return this;
    }

    /**
     * Changes the Key based on the Password. The required salt will become the entered password.
     * @param password Password
     * @return AES Builder
     */
    public AES setPassword(String password) { return this.setPassword(password, ""); }

    /** @return Private Key */
    public SecretKey getPrivateKey() { return this.privateKey; }

    /** @return AES Options */
    public AESOption getOptions() { return this.options; }

    /**
     * Creates a KeyFile which will store all the encryption information such as Key Length, Private Key,
     * IV Length, Algorithm Type, etc. into one file which can be loaded later to re-generate everything without
     * requiring manual input.
     *
     * @param fileLocation String File Location
     * @return AES Builder
     */
    public AES writeKeyFile(String fileLocation) throws IOException {
        this.writeKeyFile(new File(fileLocation));
        return this;
    }

    /** @return IV */
    public IvParameterSpec getIV() { return this.ivKey; }
}