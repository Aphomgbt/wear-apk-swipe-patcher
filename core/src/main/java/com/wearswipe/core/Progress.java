package com.wearswipe.core;

/**
 * 进度回调。所有回调都在调用线程上发生；Android 端需自行切回主线程再更新 UI。
 */
public interface Progress {

    /** 空实现，便于在测试/命令行场景直接使用。 */
    Progress NOOP = new Progress() {
        @Override
        public void onProgress(int percent, String message) {
            // no-op
        }
    };

    /**
     * @param percent 0..100，超出范围或不可估算时传负数表示"不确定"
     * @param message 人类可读的中文状态描述
     */
    void onProgress(int percent, String message);
}
