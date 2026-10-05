package com.wearswipe.core;

import com.wearswipe.core.crypto.SelfSignedCertificates;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 签名配置：一个私钥 + 一条证书链。
 *
 * <p>两种来源：
 * <ul>
 *   <li>{@link #generateSelfSigned(String)} —— 运行时自签名，用户零配置；</li>
 *   <li>{@link #fromKeyStore} / {@link #fromPrivateKeyAndCertificate} —— 用户自带密钥。</li>
 * </ul>
 */
public final class SigningConfig {

    private final String signerName;
    private final PrivateKey privateKey;
    private final List<X509Certificate> certificateChain;

    private SigningConfig(String signerName, PrivateKey privateKey,
                          List<X509Certificate> certificateChain) {
        this.signerName = signerName;
        this.privateKey = privateKey;
        this.certificateChain = Collections.unmodifiableList(
                new ArrayList<X509Certificate>(certificateChain));
    }

    public String getSignerName() {
        return signerName;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public List<X509Certificate> getCertificateChain() {
        return certificateChain;
    }

    /** 运行时生成一张自签名证书并立即用于签名。 */
    public static SigningConfig generateSelfSigned(String commonName)
            throws GeneralSecurityException, IOException {
        SelfSignedCertificates.KeyAndCert generated =
                SelfSignedCertificates.generate(commonName, 365 * 30);
        return new SigningConfig("self-signed:" + commonName,
                generated.getKeyPair().getPrivate(),
                Collections.singletonList(generated.getCertificate()));
    }

    public static SigningConfig fromPrivateKeyAndCertificate(String signerName,
                                                             PrivateKey privateKey,
                                                             X509Certificate certificate) {
        return new SigningConfig(signerName, privateKey,
                Collections.singletonList(certificate));
    }

    public static SigningConfig fromPrivateKeyAndCertificate(String signerName,
                                                             PrivateKey privateKey,
                                                             List<X509Certificate> chain) {
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("证书链不能为空");
        }
        return new SigningConfig(signerName, privateKey, chain);
    }

    /**
     * 从 JKS / PKCS12 密钥库读取。
     *
     * @param keyStoreFile 密钥库文件
     * @param storeType    {@code JKS} / {@code PKCS12}，为 {@code null} 时按扩展名猜
     * @param storePass    密钥库口令，可为 {@code null}
     * @param alias        条目别名，为 {@code null} 时取第一个私钥条目
     * @param keyPass      私钥口令，为 {@code null} 时沿用 {@code storePass}
     */
    public static SigningConfig fromKeyStore(File keyStoreFile, String storeType,
                                             char[] storePass, String alias, char[] keyPass)
            throws GeneralSecurityException, IOException {
        String type = storeType;
        if (type == null || type.isEmpty()) {
            type = KeyStore.getDefaultType();
        }
        KeyStore keyStore = KeyStore.getInstance(type);
        InputStream in = new FileInputStream(keyStoreFile);
        try {
            keyStore.load(in, storePass);
        } finally {
            closeQuietly(in);
        }

        String entryAlias = alias;
        if (entryAlias == null) {
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String candidate = aliases.nextElement();
                if (keyStore.isKeyEntry(candidate)) {
                    entryAlias = candidate;
                    break;
                }
            }
        }
        if (entryAlias == null) {
            throw new GeneralSecurityException("密钥库里没有可用的私钥条目");
        }
        char[] pass = keyPass != null ? keyPass : storePass;
        PrivateKey key = (PrivateKey) keyStore.getKey(entryAlias, pass);
        java.security.cert.Certificate[] chain = keyStore.getCertificateChain(entryAlias);
        if (chain == null || chain.length == 0) {
            throw new GeneralSecurityException("条目 " + entryAlias + " 没有证书链");
        }
        List<X509Certificate> certificates = new ArrayList<X509Certificate>(chain.length);
        for (java.security.cert.Certificate c : chain) {
            certificates.add((X509Certificate) c);
        }
        return new SigningConfig(entryAlias, key, certificates);
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
