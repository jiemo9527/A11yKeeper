package com.jiemo.a11ykeeper;

public final class Constants {
    public static final String TAG = "A11yKeeper";
    public static final String MODULE_PACKAGE = "com.jiemo.a11ykeeper";

    /** 有序广播：system_server 通过 setResultData 回传 JSON 状态 */
    public static final String ACTION_QUERY = "com.jiemo.a11ykeeper.QUERY";
    /** 锁定 / 解除锁定：extra EXTRA_COMPONENT + EXTRA_LOCKED */
    public static final String ACTION_LOCK = "com.jiemo.a11ykeeper.LOCK";
    /** 重启（先关闭再开启）：extra EXTRA_COMPONENT */
    public static final String ACTION_RESTART = "com.jiemo.a11ykeeper.RESTART";
    /** 手动开启：extra EXTRA_COMPONENT */
    public static final String ACTION_ENABLE = "com.jiemo.a11ykeeper.ENABLE";
    /** 手动关闭（已锁定的会同时解除锁定，否则会被立即恢复）：extra EXTRA_COMPONENT */
    public static final String ACTION_DISABLE = "com.jiemo.a11ykeeper.DISABLE";
    public static final String ACTION_CLEAR_LOG = "com.jiemo.a11ykeeper.CLEAR_LOG";

    public static final String EXTRA_COMPONENT = "component";
    public static final String EXTRA_LOCKED = "locked";
    /**
     * 模块 App 创建的 PendingIntent，system_server 用 getCreatorUid() 校验发送方身份。
     * 不使用自定义权限：App 一旦声明/申请权限，隐藏桌面图标后部分 Launcher 会生成“应用详情”替身图标。
     */
    public static final String EXTRA_AUTH = "auth";

    private Constants() {
    }
}
