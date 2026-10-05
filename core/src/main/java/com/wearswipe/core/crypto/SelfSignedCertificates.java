package com.wearswipe.core.crypto;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

/**
 * 运行时生成自签名代码签名证书（RSA-2048 / SHA256withRSA / 有效期 30 年）。
 *
 * <p>用于"用户没有自己的签名密钥"时的默认路径：Android 上安装 APK 只要求
 * "签名有效且与已安装版本一致"，不要求证书由 CA 签发，因此自签名证书完全够用。
 */
public final class SelfSignedCertificates {

    /** 生成结果。 */
    public static final class KeyAndCert {
        private final KeyPair keyPair;
        private final X509Certificate certificate;

        KeyAndCert(KeyPair keyPair, X509Certificate certificate) {
            this.keyPair = keyPair;
            this.certificate = certificate;
        }

        public KeyPair getKeyPair() {
            return keyPair;
        }

        public X509Certificate getCertificate() {
            return certificate;
        }
    }

    private SelfSignedCertificates() {
    }

    /**
     * 生成 RSA-2048 密钥对与配套的自签名证书。
     *
     * @param commonName 证书 CN，建议形如 {@code WearSwipePatcher}
     * @param validDays  有效期天数
     */
    public static KeyAndCert generate(String commonName, int validDays)
            throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();
        X509Certificate certificate = createCertificate(keyPair, commonName, validDays);
        return new KeyAndCert(keyPair, certificate);
    }

    /** 用给定密钥对签发自签名证书。 */
    public static X509Certificate createCertificate(KeyPair keyPair, String commonName, int validDays)
            throws GeneralSecurityException, IOException {
        PublicKey publicKey = keyPair.getPublic();
        if (!(publicKey instanceof RSAPublicKey)) {
            throw new GeneralSecurityException("只支持 RSA 密钥对");
        }
        RSAPublicKey rsaPublicKey = (RSAPublicKey) publicKey;

        byte[] algorithmIdentifier = DerWriter.sequence(
                DerWriter.oid(DerWriter.OID_SHA256_WITH_RSA),
                DerWriter.nullValue());

        byte[] name = DerWriter.sequence(
                DerWriter.set(DerWriter.sequence(
                        DerWriter.oid(DerWriter.OID_COMMON_NAME),
                        DerWriter.utf8String(commonName))));

        long now = System.currentTimeMillis();
        byte[] validity = DerWriter.sequence(
                DerWriter.time(new Date(now - 60_000L)),
                DerWriter.time(new Date(now + validDays * 86_400_000L)));

        byte[] subjectPublicKeyInfo = DerWriter.sequence(
                DerWriter.sequence(
                        DerWriter.oid(DerWriter.OID_RSA_ENCRYPTION),
                        DerWriter.nullValue()),
                DerWriter.bitString(DerWriter.sequence(
                        DerWriter.integer(rsaPublicKey.getModulus()),
                        DerWriter.integer(rsaPublicKey.getPublicExponent())).clone()));

        byte[] basicConstraints = DerWriter.sequence(
                DerWriter.oid("2.5.29.19"),
                DerWriter.octetString(DerWriter.sequence(DerWriter.booleanValue(true))));

        byte[] tbsCertificate = DerWriter.sequence(
                DerWriter.explicit(0, DerWriter.integer(2)),                  // version v3
                DerWriter.integer(randomSerial()),
                algorithmIdentifier,
                name,                                                        // issuer
                validity,
                name,                                                        // subject
                subjectPublicKeyInfo,
                DerWriter.explicit(3, DerWriter.sequence(basicConstraints)));

        byte[] signatureValue = sign(tbsCertificate, keyPair);

        byte[] encoded = DerWriter.sequence(
                tbsCertificate,
                algorithmIdentifier,
                DerWriter.bitString(signatureValue));

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate =
                (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(encoded));
        checkValidity(certificate);
        return certificate;
    }

    /** 自检有效期方向，避免再次出现"UTCTime 两位年回绕"这类静默错误。 */
    private static void checkValidity(X509Certificate certificate) throws GeneralSecurityException {
        if (!certificate.getNotAfter().after(certificate.getNotBefore())) {
            throw new GeneralSecurityException("生成的证书有效期无效: notBefore="
                    + certificate.getNotBefore() + " notAfter=" + certificate.getNotAfter());
        }
    }

    private static byte[] sign(byte[] tbsCertificate, KeyPair keyPair) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(tbsCertificate);
        return signature.sign();
    }

    private static BigInteger randomSerial() {
        return new BigInteger(64, new SecureRandom()).abs().add(BigInteger.ONE);
    }
}
