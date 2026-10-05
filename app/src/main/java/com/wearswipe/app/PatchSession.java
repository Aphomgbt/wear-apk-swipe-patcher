package com.wearswipe.app;

import com.wearswipe.core.ApkInspector;
import com.wearswipe.core.ApkSignerTool;
import com.wearswipe.core.PatchReport;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内共享的补丁会话状态。
 *
 * <p>{@link PatchService} 是唯一写入方，{@link MainActivity} 只读并注册监听。
 * 用静态持有而不是广播：同进程内更简单，且 Activity 重建后能立刻拿回当前状态。
 */
public final class PatchSession {

    public enum Phase {
        IDLE,
        ANALYZING,
        READY,
        PATCHING,
        DONE,
        FAILED
    }

    /** 回调在写入方线程上触发，UI 侧需自行切回主线程。 */
    public interface Listener {
        void onSessionChanged();
    }

    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<Listener>();

    private static final int MAX_LOG_LINES = 200;

    private static File inputFile;
    private static String inputDisplayName;
    private static ApkInspector.Inspection inspection;

    private static Phase phase = Phase.IDLE;
    private static int percent;
    private static String message = "";
    private static final StringBuilder LOG = new StringBuilder();

    private static File outputFile;
    private static PatchReport report;
    private static ApkSignerTool.VerifyResult verification;
    private static String error;

    private PatchSession() {
    }

    // ------------------------------------------------------------------
    // 监听
    // ------------------------------------------------------------------

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onSessionChanged();
            } catch (Throwable ignored) {
                // 单个监听器出错不影响其它
            }
        }
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    public static void setInput(File file, String displayName) {
        synchronized (LOCK) {
            inputFile = file;
            inputDisplayName = displayName;
            inspection = null;
            phase = Phase.ANALYZING;
            percent = 0;
            message = "正在分析 APK…";
            error = null;
            outputFile = null;
            report = null;
            verification = null;
            LOG.setLength(0);
        }
        notifyListeners();
    }

    public static void setInspection(ApkInspector.Inspection value) {
        synchronized (LOCK) {
            inspection = value;
            if (value != null && value.isPatchable()) {
                phase = Phase.READY;
            } else {
                phase = Phase.FAILED;
                error = "无法处理：" + (value == null ? "分析失败" : String.valueOf(value.getPatchableReason()));
            }
        }
        notifyListeners();
    }

    public static void failAnalysis(String reason) {
        synchronized (LOCK) {
            inspection = null;
            phase = Phase.FAILED;
            error = reason;
        }
        notifyListeners();
    }

    // ------------------------------------------------------------------
    // 运行中
    // ------------------------------------------------------------------

    public static void startPatching() {
        synchronized (LOCK) {
            phase = Phase.PATCHING;
            percent = 0;
            message = "准备中…";
            error = null;
            outputFile = null;
            report = null;
            verification = null;
            LOG.setLength(0);
        }
        notifyListeners();
    }

    public static void progress(int newPercent, String newMessage) {
        synchronized (LOCK) {
            percent = newPercent;
            message = newMessage;
            appendLogLocked(newPercent + "%  " + newMessage);
        }
        notifyListeners();
    }

    public static void log(String line) {
        synchronized (LOCK) {
            appendLogLocked(line);
        }
        notifyListeners();
    }

    private static void appendLogLocked(String line) {
        LOG.append(line).append('\n');
        int lines = 0;
        for (int i = LOG.length() - 1; i >= 0; i--) {
            if (LOG.charAt(i) == '\n' && ++lines > MAX_LOG_LINES) {
                LOG.delete(0, i + 1);
                return;
            }
        }
    }

    public static void finish(File output, PatchReport patchReport,
                              ApkSignerTool.VerifyResult verifyResult) {
        synchronized (LOCK) {
            outputFile = output;
            report = patchReport;
            verification = verifyResult;
            phase = Phase.DONE;
            percent = 100;
            message = "完成";
        }
        notifyListeners();
    }

    public static void fail(String reason) {
        synchronized (LOCK) {
            error = reason;
            phase = Phase.FAILED;
            appendLogLocked("失败: " + reason);
        }
        notifyListeners();
    }

    public static void reset() {
        synchronized (LOCK) {
            inputFile = null;
            inputDisplayName = null;
            inspection = null;
            phase = Phase.IDLE;
            percent = 0;
            message = "";
            LOG.setLength(0);
            outputFile = null;
            report = null;
            verification = null;
            error = null;
        }
        notifyListeners();
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public static Phase getPhase() {
        synchronized (LOCK) {
            return phase;
        }
    }

    public static File getInputFile() {
        synchronized (LOCK) {
            return inputFile;
        }
    }

    public static String getInputDisplayName() {
        synchronized (LOCK) {
            return inputDisplayName;
        }
    }

    public static ApkInspector.Inspection getInspection() {
        synchronized (LOCK) {
            return inspection;
        }
    }

    public static int getPercent() {
        synchronized (LOCK) {
            return percent;
        }
    }

    public static String getMessage() {
        synchronized (LOCK) {
            return message;
        }
    }

    public static String getLog() {
        synchronized (LOCK) {
            return LOG.toString();
        }
    }

    public static File getOutputFile() {
        synchronized (LOCK) {
            return outputFile;
        }
    }

    public static PatchReport getReport() {
        synchronized (LOCK) {
            return report;
        }
    }

    public static ApkSignerTool.VerifyResult getVerification() {
        synchronized (LOCK) {
            return verification;
        }
    }

    public static String getError() {
        synchronized (LOCK) {
            return error;
        }
    }
}
