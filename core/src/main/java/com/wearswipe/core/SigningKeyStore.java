package com.wearswipe.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 签名密钥的持久化：把「一把私钥 + 一条证书链」写成字节流再读回来。
 *
 * <p>解决的问题：早先每次打补丁都在内存里现生成一张自签名证书，
 * 于是<b>同一个 APK 每次出的包签名都不一样</b>，前一次装上的成品无法被后一次覆盖安装，
 * 每次都得先卸载旧版。现在密钥生成一次、存下来、之后一直复用。
 *
 * <p>两种格式：
 * <ul>
 *   <li>{@link #toBundle}/{@link #fromBundle} —— {@code WSKB} 开头的自有格式，
 *       内容就是 PKCS#8 私钥 + X.509 证书链。只用 JDK 自带的 KeyFactory /
 *       CertificateFactory，任何 Android 版本都能读写，用于设备上的本地存储；</li>
 *   <li>{@link #toEncryptedBundle}/{@link #fromEncryptedBundle} —— {@code WSKE} 开头的
 *       带口令备份文件：PBKDF2-HMAC-SHA256 派生密钥 + AES-256-GCM 认证加密，
 *       用于导出/导入（口令错或文件被改过都会明确报错，而不是给出坏密钥）。</li>
 * </ul>
 *
 * <p>{@link #fromAnyFormat} 还能直接读用户自己的 PKCS#12 / JKS 密钥库，方便接管已有密钥。
 */
public final class SigningKeyStore {

    /** 自有格式（明文，仅用于设备私有目录）的文件头。 */
    public static final String BUNDLE_MAGIC = "WSKB";
    /** 带口令备份文件的文件头。 */
    public static final String ENCRYPTED_MAGIC = "WSKE";
    /** 备份文件的推荐扩展名。 */
    public static final String BACKUP_EXTENSION = ".wskey";

    /** 读密钥库时，第一个私钥条目的兜底别名（也用于我们自己写出的 PKCS#12）。 */
    public static final String DEFAULT_ALIAS = "wear-swipe";

    private static final int BUNDLE_VERSION = 1;
    private static final int ENCRYPTED_VERSION = 1;

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_LENGTH = 16;
    private static final int GCM_TAG_BITS = 128;
    private static final String AES_GCM = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SigningKeyStore() {
    }

    // ------------------------------------------------------------------
    // 自有格式（WSKB，明文）
    // ------------------------------------------------------------------

    /** 把签名配置编码成 {@code WSKB} 字节流。 */
    public static byte[] toBundle(SigningConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("签名配置不能为空");
        }
        PrivateKey key = config.getPrivateKey();
        List<X509Certificate> chain = config.getCertificateChain();
        if (key == null || key.getEncoded() == null) {
            throw new IOException("私钥不可导出，无法保存");
        }
        if (chain.isEmpty()) {
            throw new IOException("证书链为空，无法保存");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            writeFully(out, BUNDLE_MAGIC.getBytes("US-ASCII"));
            out.write(BUNDLE_VERSION);
            writeBlock(out, nameOf(config).getBytes("UTF-8"));
            writeBlock(out, key.getEncoded());
            out.write((chain.size() >>> 8) & 0xff);
            out.write(chain.size() & 0xff);
            for (X509Certificate certificate : chain) {
                writeBlock(out, certificate.getEncoded());
            }
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IOException("证书无法编码: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /** 读回 {@link #toBundle} 写出的字节流。 */
    public static SigningConfig fromBundle(byte[] data) throws IOException, GeneralSecurityException {
        if (data == null || data.length < BUNDLE_MAGIC.length() + 1) {
            throw new IOException("密钥数据太短，不是有效的密钥文件");
        }
        Cursor cursor = new Cursor(data);
        String magic = cursor.readAscii(BUNDLE_MAGIC.length());
        if (!BUNDLE_MAGIC.equals(magic)) {
            throw new IOException("不是本工具导出的密钥文件（文件头是 " + magic + "）");
        }
        int version = cursor.readByte();
        if (version != BUNDLE_VERSION) {
            throw new IOException("不支持的密钥文件版本: " + version);
        }
        String signerName = cursor.readString();
        byte[] keyBytes = cursor.readBlock();
        int count = (cursor.readByte() << 8) | cursor.readByte();
        if (count <= 0) {
            throw new IOException("密钥文件里没有证书");
        }
        List<X509Certificate> chain = new ArrayList<X509Certificate>(count);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < count; i++) {
            byte[] encoded = cursor.readBlock();
            chain.add((X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(encoded)));
        }
        cursor.expectEnd();
        PrivateKey privateKey = KeyFactory.getInstance(keyAlgorithm(chain.get(0)))
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        return SigningConfig.fromPrivateKeyAndCertificate(signerName, privateKey, chain);
    }

    // ------------------------------------------------------------------
    // 带口令的备份格式（WSKE，AES-GCM）
    // ------------------------------------------------------------------

    /**
     * 用口令把签名配置加密成可备份的字节流。
     *
     * <p>格式：{@code WSKE} + 版本 + 迭代次数 + 盐 + IV + 密文（含 GCM 认证标签）。
     * 口令不对或文件被改动过都会在解密时失败并抛异常。
     */
    public static byte[] toEncryptedBundle(SigningConfig config, char[] password)
            throws IOException, GeneralSecurityException {
        requirePassword(password);
        byte[] salt = randomBytes(SALT_LENGTH);
        SecretKey key = deriveKey(password, salt, PBKDF2_ITERATIONS);

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, randomBytes(12)));
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(toBundle(config));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFully(out, ENCRYPTED_MAGIC.getBytes("US-ASCII"));
        out.write(ENCRYPTED_VERSION);
        writeInt(out, PBKDF2_ITERATIONS);
        writeBlock(out, salt);
        writeBlock(out, iv);
        writeBlock(out, cipherText);
        return out.toByteArray();
    }

    /** 用口令解开 {@link #toEncryptedBundle} 写出的字节流。 */
    public static SigningConfig fromEncryptedBundle(byte[] data, char[] password)
            throws IOException, GeneralSecurityException {
        requirePassword(password);
        Cursor cursor = new Cursor(data);
        String magic = cursor.readAscii(ENCRYPTED_MAGIC.length());
        if (!ENCRYPTED_MAGIC.equals(magic)) {
            throw new IOException("不是本工具导出的密钥备份文件（文件头是 " + magic + "）");
        }
        int version = cursor.readByte();
        if (version != ENCRYPTED_VERSION) {
            throw new IOException("不支持的密钥备份版本: " + version);
        }
        int iterations = cursor.readInt();
        if (iterations < 1000) {
            throw new IOException("备份文件里的迭代次数不合理: " + iterations);
        }
        byte[] salt = cursor.readBlock();
        byte[] iv = cursor.readBlock();
        byte[] cipherText = cursor.readBlock();
        cursor.expectEnd();

        SecretKey key = deriveKey(password, salt, iterations);
        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain;
        try {
            plain = cipher.doFinal(cipherText);
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException("口令不对，或备份文件已损坏", e);
        }
        return fromBundle(plain);
    }

    // ------------------------------------------------------------------
    // 导入：自动识别格式
    // ------------------------------------------------------------------

    /**
     * 从任意受支持的载体读出签名配置。
     *
     * <p>依次尝试：本工具的带口令备份（{@code WSKE}）、本工具的明文载体（{@code WSKB}）、
     * 用户自己的 PKCS#12 / JKS 密钥库（按文件头决定先试哪个）。
     *
     * @param password 口令；密钥库和备份文件都需要
     */
    public static SigningConfig fromAnyFormat(byte[] data, char[] password)
            throws IOException, GeneralSecurityException {
        if (data == null || data.length == 0) {
            throw new IOException("密钥文件是空的");
        }
        if (startsWith(data, ENCRYPTED_MAGIC)) {
            return fromEncryptedBundle(data, password);
        }
        if (startsWith(data, BUNDLE_MAGIC)) {
            return fromBundle(data);
        }

        List<String> types = new ArrayList<String>(2);
        if (isJks(data)) {
            types.add("JKS");
            types.add("PKCS12");
        } else {
            types.add("PKCS12");
            types.add("JKS");
        }
        String lastError = null;
        for (String type : types) {
            try {
                return SigningConfig.fromKeyStore(new ByteArrayInputStream(data), type,
                        password, null, null);
            } catch (GeneralSecurityException e) {
                lastError = e.getMessage();
            } catch (IOException e) {
                lastError = e.getMessage();
            }
        }
        throw new GeneralSecurityException("无法识别这个密钥文件：既不是本工具的 .wskey 备份，"
                + "也无法按 PKCS#12/JKS 密钥库读出来（" + lastError + "）");
    }

    // ------------------------------------------------------------------
    // 文件读写
    // ------------------------------------------------------------------

    /** 原子地把签名配置写入文件（先写 .tmp 再改名），并尽力收紧密码。 */
    public static void saveBundle(SigningConfig config, File file) throws IOException {
        File temp = new File(file.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            writeFully(out, toBundle(config));
            out.flush();
            out.getFD().sync();
        } finally {
            closeQuietly(out);
        }
        restrictToOwner(temp);
        replace(temp, file);
    }

    /** 读回 {@link #saveBundle} 写出的文件。 */
    public static SigningConfig loadBundle(File file) throws IOException, GeneralSecurityException {
        return fromBundle(readAll(file));
    }

    /** 原子地把带口令备份写入文件。 */
    public static void saveEncryptedBundle(SigningConfig config, File file, char[] password)
            throws IOException, GeneralSecurityException {
        byte[] data = toEncryptedBundle(config, password);
        File temp = new File(file.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            writeFully(out, data);
            out.flush();
            out.getFD().sync();
        } finally {
            closeQuietly(out);
        }
        restrictToOwner(temp);
        replace(temp, file);
    }

    /** 读回 {@link #saveEncryptedBundle} 写出的文件。 */
    public static SigningConfig loadEncryptedBundle(File file, char[] password)
            throws IOException, GeneralSecurityException {
        return fromEncryptedBundle(readAll(file), password);
    }

    /** 读文件全部字节（单次最大 8 MB，密钥文件远小于此）。 */
    public static byte[] readAll(File file) throws IOException {
        long length = file.length();
        if (length <= 0 || length > 8L * 1024 * 1024) {
            throw new IOException("密钥文件大小异常: " + length + " 字节");
        }
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) length);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    // ------------------------------------------------------------------
    // 指纹与描述
    // ------------------------------------------------------------------

    /** 叶子证书的 SHA-256 指纹（大写、冒号分隔），用来判断两次出的包是不是同一把密钥。 */
    public static String fingerprintOf(SigningConfig config) {
        if (config == null || config.getCertificateChain().isEmpty()) {
            return null;
        }
        return fingerprintOf(config.getCertificateChain().get(0));
    }

    /** 证书的 SHA-256 指纹（大写、冒号分隔）。 */
    public static String fingerprintOf(X509Certificate certificate) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()), true);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一句话描述这把密钥，用于界面展示和日志。 */
    public static String describe(SigningConfig config) {
        if (config == null) {
            return "（无）";
        }
        X509Certificate leaf = config.getCertificateChain().get(0);
        String subject = leaf.getSubjectX500Principal().getName();
        return subject + "，SHA-256 " + fingerprintOf(leaf)
                + "，有效期至 " + new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .format(leaf.getNotAfter());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static String nameOf(SigningConfig config) {
        String name = config.getSignerName();
        return name == null || name.isEmpty() ? DEFAULT_ALIAS : name;
    }

    /** 表示私钥类型的 KeyFactory 算法名，直接从叶子证书的公钥算法取。 */
    private static String keyAlgorithm(X509Certificate leaf) {
        return leaf.getPublicKey().getAlgorithm();
    }

    private static boolean startsWith(byte[] data, String magic) {
        if (data.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if ((data[i] & 0xff) != magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** JKS 的文件头是固定魔数 0xFEEDFEED。 */
    private static boolean isJks(byte[] data) {
        return data.length >= 4 && (data[0] & 0xff) == 0xfe && (data[1] & 0xff) == 0xed
                && (data[2] & 0xff) == 0xfe && (data[3] & 0xff) == 0xed;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static SecretKey deriveKey(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        byte[] key = factory.generateSecret(
                new PBEKeySpec(password, salt, iterations, PBKDF2_KEY_BITS)).getEncoded();
        return new SecretKeySpec(key, "AES");
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 4) {
            throw new IllegalArgumentException("口令至少 4 位");
        }
    }

    private static void writeBlock(OutputStream out, byte[] block) throws IOException {
        writeInt(out, block.length);
        writeFully(out, block);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeFully(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
    }

    private static String hex(byte[] bytes, boolean colonSeparated) {
        StringBuilder sb = new StringBuilder(bytes.length * (colonSeparated ? 3 : 2));
        for (int i = 0; i < bytes.length; i++) {
            if (colonSeparated && i > 0) {
                sb.append(':');
            }
            int value = bytes[i] & 0xff;
            sb.append(Character.toUpperCase(Character.forDigit(value >>> 4, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(value & 0x0f, 16)));
        }
        return sb.toString();
    }

    /** 尽力把文件限制为只有拥有者可读写；平台不支持时静默跳过。 */
    private static void restrictToOwner(File file) {
        try {
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            file.setExecutable(false, false);
        } catch (Exception ignored) {
            // 某些文件系统不支持，忽略
        }
    }

    /** 把临时文件换成正式文件；rename 不可用时退化为直接覆盖写。 */
    private static void replace(File temp, File target) throws IOException {
        if (temp.renameTo(target)) {
            return;
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖已有密钥文件: " + target.getAbsolutePath());
        }
        if (temp.renameTo(target)) {
            return;
        }
        byte[] data = readAll(temp);
        FileOutputStream out = new FileOutputStream(target);
        try {
            writeFully(out, data);
        } finally {
            closeQuietly(out);
        }
        if (!temp.delete()) {
            temp.deleteOnExit();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    /** 简单的顺序读游标，读到一半发现数据不完整就报错。 */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        String readAscii(int length) throws IOException {
            byte[] bytes = readBytes(length);
            return new String(bytes, "US-ASCII");
        }

        String readString() throws IOException {
            return new String(readBlock(), "UTF-8");
        }

        int readByte() throws IOException {
            return readBytes(1)[0] & 0xff;
        }

        int readInt() throws IOException {
            byte[] bytes = readBytes(4);
            return ((bytes[0] & 0xff) << 24) | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
        }

        byte[] readBlock() throws IOException {
            return readBytes(readInt());
        }

        void expectEnd() throws IOException {
            if (position != data.length) {
                throw new IOException("密钥文件尾部有多余的 " + (data.length - position) + " 字节");
            }
        }

        private byte[] readBytes(int length) throws IOException {
            if (length < 0 || position + length > data.length) {
                throw new IOException("密钥文件不完整或已损坏");
            }
            byte[] result = new byte[length];
            System.arraycopy(data, position, result, 0, length);
            position += length;
            return result;
        }
    }
}
