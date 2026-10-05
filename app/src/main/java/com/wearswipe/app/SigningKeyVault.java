package com.wearswipe.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import com.wearswipe.core.SigningConfig;
import com.wearswipe.core.SigningKeyStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 本机签名密钥的存放处：<b>只生成一次，之后一直复用</b>。
 *
 * <p>为什么要它：以前每次补丁都在内存里现生成一张自签名证书，同一个 APK
 * 两次出的包签名不同，后一次装不进前一次的位置，必须卸载重装。现在密钥保存在
 * 应用私有目录里（默认由 Android Keystore 里的一把 AES 密钥做 AES-GCM 封装），
 * 只要密钥不变，之后所有输出都能覆盖安装前面的版本。
 *
 * <p>三级保障，逐级退让但都会如实告知用户：
 * <ol>
 *   <li>Android Keystore 里硬件保护的 AES 密钥 + AES-GCM 封装（首选）；</li>
 *   <li>Android Keystore 的软件实现（没有安全硬件的设备）；</li>
 *   <li>Keystore 完全不可用时，密钥明文放在应用私有目录里
 *       —— 保护程度只剩系统沙箱 + 设备加密。</li>
 * </ol>
 *
 * <p>导出的备份文件是用口令加密的 {@code .wskey}（PBKDF2 + AES-GCM），
 * 换机器或重装后导入即可继续覆盖安装。所有方法都可能做磁盘或密钥运算，
 * <b>请在后台线程调用</b>。
 */
public final class SigningKeyVault {

    /** 自签名证书的 CN，界面与导出文件都用它做说明。 */
    public static final String COMMON_NAME = "WearSwipePatcher";
    /** 导出备份时的默认文件名。 */
    public static final String BACKUP_FILE_NAME =
            "wear-swipe-signing-key" + SigningKeyStore.BACKUP_EXTENSION;
    /** 导出口令的最短长度。 */
    public static final int MIN_PASSWORD_LENGTH = 6;
    /** 导出/导入对话框里给文件选择器的类型。 */
    public static final String MIME_BACKUP = "application/octet-stream";

    private static final String DIR_NAME = "signing";
    private static final String WRAPPED_FILE = "signing-key.bin";
    private static final String PLAIN_FILE = "signing-key.plain";
    private static final String KEYSTORE_TYPE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "wear_swipe_patcher_signing_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int WRAPPED_VERSION = 1;
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private static final Object LOCK = new Object();

    private static SigningConfig cachedConfig;
    private static String cachedFingerprint;
    private static boolean cachedHardwareBacked;
    private static boolean cachedWrapped;

    private SigningKeyVault() {
    }

    // ------------------------------------------------------------------
    // 读取 / 生成
    // ------------------------------------------------------------------

    /**
     * 取出本机签名密钥；第一次调用时会生成并保存一把新密钥。
     *
     * <p>可能做 RSA 密钥生成（手表上通常几百毫秒），不要在 UI 线程调用。
     */
    public static SigningConfig get(Context context) throws GeneralSecurityException, IOException {
        synchronized (LOCK) {
            if (cachedConfig != null) {
                return cachedConfig;
            }
            Context app = context.getApplicationContext();
            File dir = keyDir(app);
            File wrapped = new File(dir, WRAPPED_FILE);
            File plain = new File(dir, PLAIN_FILE);

            SigningConfig config;
            if (wrapped.isFile()) {
                config = SigningKeyStore.fromBundle(unwrap(wrapped));
                cachedWrapped = true;
            } else if (plain.isFile()) {
                config = SigningKeyStore.loadBundle(plain);
                cachedWrapped = false;
            } else {
                config = SigningConfig.generateSelfSigned(COMMON_NAME);
                persist(app, config);
            }
            cachedConfig = config;
            cachedFingerprint = SigningKeyStore.fingerprintOf(config);
            return config;
        }
    }

    /** 是否已经有保存下来的密钥（不触发生成）。 */
    public static boolean hasStoredKey(Context context) {
        File dir = keyDir(context.getApplicationContext());
        return new File(dir, WRAPPED_FILE).isFile() || new File(dir, PLAIN_FILE).isFile();
    }

    /** 当前密钥的一行描述（含指纹）；第一次调用会触发生成。 */
    public static String description(Context context) throws GeneralSecurityException, IOException {
        get(context);
        return SigningKeyStore.describe(cachedConfig);
    }

    /** 当前密钥的证书指纹；还没加载时返回 {@code null}。 */
    public static String fingerprint(Context context) {
        synchronized (LOCK) {
            return cachedFingerprint;
        }
    }

    /** 这把密钥在设备上的保护方式，用于界面展示。 */
    public static String storageDescription() {
        synchronized (LOCK) {
            if (!cachedWrapped) {
                return "应用私有目录（系统沙箱保护）";
            }
            return cachedHardwareBacked
                    ? "Android Keystore（硬件保护）"
                    : "Android Keystore（软件保护）";
        }
    }

    // ------------------------------------------------------------------
    // 导出 / 导入
    // ------------------------------------------------------------------

    /**
     * 把当前密钥加密后写入 {@code out}。
     *
     * @param password 备份口令，至少 {@link #MIN_PASSWORD_LENGTH} 位
     */
    public static void export(Context context, OutputStream out, char[] password)
            throws GeneralSecurityException, IOException {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new GeneralSecurityException("口令太短");
        }
        SigningConfig config = get(context);
        out.write(SigningKeyStore.toEncryptedBundle(config, password));
        out.flush();
    }

    /** 导出的建议文件名（带指纹前缀，便于区分不同密钥的备份）。 */
    public static String exportFileName(Context context) {
        String fingerprint = fingerprint(context);
        if (fingerprint == null) {
            return BACKUP_FILE_NAME;
        }
        String prefix = BACKUP_FILE_NAME.substring(0, BACKUP_FILE_NAME.length()
                - SigningKeyStore.BACKUP_EXTENSION.length());
        return prefix + "-" + fingerprint.replace(":", "").substring(0, 8)
                + SigningKeyStore.BACKUP_EXTENSION;
    }

    /**
     * 用外部文件替换本机密钥（备份恢复，或接管用户自己的密钥）。
     *
     * <p>支持本工具的 {@code .wskey} 备份，也支持 PKCS#12 / JKS 密钥库；
     * 导入成功后，之后所有输出都会用这把密钥签名。
     */
    public static void importFrom(Context context, byte[] data, char[] password)
            throws GeneralSecurityException, IOException {
        SigningConfig config = SigningKeyStore.fromAnyFormat(data, password);
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            persist(app, config);
            cachedConfig = config;
            cachedFingerprint = SigningKeyStore.fingerprintOf(config);
        }
    }

    /** 从输入流读入密钥文件并导入（最多 8 MB）。 */
    public static void importFrom(Context context, InputStream in, char[] password)
            throws GeneralSecurityException, IOException {
        importFrom(context, readAll(in), password);
    }

    /** 忘掉缓存，下次读取时重新从磁盘加载。 */
    public static void clearCache() {
        synchronized (LOCK) {
            cachedConfig = null;
            cachedFingerprint = null;
            cachedWrapped = false;
            cachedHardwareBacked = false;
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static File keyDir(Context appContext) {
        File dir = new File(appContext.getFilesDir(), DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            // 目录建不出来时退回 files 根目录，至少不把用户卡住
            return appContext.getFilesDir();
        }
        return dir;
    }

    private static void persist(Context appContext, SigningConfig config) throws IOException {
        File dir = keyDir(appContext);
        File wrapped = new File(dir, WRAPPED_FILE);
        File plain = new File(dir, PLAIN_FILE);
        byte[] bundle = SigningKeyStore.toBundle(config);
        try {
            byte[] payload = wrap(bundle);
            writeAtomic(wrapped, payload);
            deleteIfExists(plain);
            cachedWrapped = true;
            cachedHardwareBacked = isHardwareBacked();
        } catch (GeneralSecurityException e) {
            // 这台设备上 Keystore 用不了：退化为明文保存，界面会如实说明
            writeAtomic(plain, bundle);
            deleteIfExists(wrapped);
            cachedWrapped = false;
            cachedHardwareBacked = false;
        }
    }

    private static byte[] wrap(byte[] bundle) throws GeneralSecurityException, IOException {
        SecretKey key = keystoreKey(true);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        // 不自己指定 IV：交给 Keystore 生成随机 IV（randomizedEncryptionRequired 的要求）
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH) {
            throw new GeneralSecurityException("Keystore 返回了异常的 IV 长度");
        }
        byte[] cipherText = cipher.doFinal(bundle);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(WRAPPED_VERSION);
        out.write(iv);
        out.write(cipherText);
        return out.toByteArray();
    }

    private static byte[] unwrap(File wrappedFile) throws GeneralSecurityException, IOException {
        byte[] payload = SigningKeyStore.readAll(wrappedFile);
        if (payload.length <= 1 + IV_LENGTH + 16 || payload[0] != WRAPPED_VERSION) {
            throw new GeneralSecurityException("本机保存的签名密钥文件已损坏");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(payload, 1, iv, 0, IV_LENGTH);

        SecretKey key = keystoreKey(false);
        if (key == null) {
            throw new GeneralSecurityException("本机保存的签名密钥打不开了（Keystore 里的密钥丢失）。"
                    + "请用「导入签名密钥」恢复你的 .wskey 备份。");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] bundle;
        try {
            bundle = cipher.doFinal(payload, 1 + IV_LENGTH, payload.length - 1 - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new GeneralSecurityException("本机保存的签名密钥无法解密（Keystore 密钥已变化）", e);
        }
        return bundle;
    }

    /**
     * 取 Keystore 里的 AES 密钥。
     *
     * @param create {@code false} 时不存在就返回 {@code null}，不新建
     */
    private static SecretKey keystoreKey(boolean create)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        if (!create) {
            return null;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_TYPE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    /** Keystore 里的这把密钥是不是由安全硬件托管的。 */
    private static boolean isHardwareBacked() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(null);
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry)) {
                return false;
            }
            SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), KEYSTORE_TYPE);
            KeyInfo info = factory.getKeySpec(key, KeyInfo.class);
            return info.isInsideSecureHardware();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeAtomic(File target, byte[] data) throws IOException {
        File temp = new File(target.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!temp.renameTo(target)) {
            deleteIfExists(target);
            if (!temp.renameTo(target)) {
                throw new IOException("无法写入密钥文件: " + target.getAbsolutePath());
            }
        }
    }

    private static void deleteIfExists(File file) {
        if (file.isFile() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 8 * 1024 * 1024) {
                    throw new IOException("密钥文件过大");
                }
            }
            return out.toByteArray();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
