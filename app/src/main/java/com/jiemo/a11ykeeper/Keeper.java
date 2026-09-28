package com.jiemo.a11ykeeper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 运行在 system_server。负责：
 * 1. 固定列表里的服务被关闭（设置项里被移除）→ 重新写回；
 * 2. 固定列表里的服务“已开启但未绑定”（crashed / binding 卡住 / binder 已死）→ 先关后开；
 * 3. 通过受签名权限保护的广播与模块 App 通信。
 */
final class Keeper {

    private static final String KEY_ENABLED = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String KEY_A11Y_ON = Settings.Secure.ACCESSIBILITY_ENABLED;
    private static final File CONFIG = new File("/data/system/a11ykeeper_pinned.txt");

    private static final long CHECK_INTERVAL_MS = 10_000;
    private static final long BOOT_GRACE_MS = 45_000;
    /** 连续多少次检查都处于“开启但未绑定”才判定假死 */
    private static final int STUCK_THRESHOLD = 2;
    private static final long TOGGLE_GAP_MS = 1_500;
    /** 同一服务两次自动修复的最小间隔，防止 App 本身崩溃时无限重启 */
    private static final long RESTART_COOLDOWN_MS = 60_000;
    private static final int LOG_MAX = 200;
    /** 被反复关闭/强杀的判定：FLAP_WINDOW_MS 内恢复 FLAP_COUNT 次后，恢复间隔不小于 FLAP_BACKOFF_MS */
    private static final long FLAP_WINDOW_MS = 120_000;
    private static final int FLAP_COUNT = 4;
    private static final long FLAP_BACKOFF_MS = 60_000;
    /** 持续不正常多久判为“异常（未能拉起）”：覆盖假死判定(约20s) + 先关后开(1.5s) + 绑定时间 */
    private static final long ABNORMAL_AFTER_MS = 35_000;
    private static final long TOAST_MS = 4_000;
    /** WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY（@hide），system_server 可用 */
    private static final int TYPE_SECURE_SYSTEM_OVERLAY = 2015;

    private static Keeper sInstance;

    private final Object ams;
    private final Object amsLock;
    private Context context;
    private Handler handler;

    private final Set<String> pinned = new LinkedHashSet<>();
    /** 已锁定服务所属包名；在 AMS 锁内的 hook 线程读取，故用 volatile 快照 */
    private volatile Set<String> pinnedPkgs = new HashSet<>();
    private final Map<String, Integer> stuckCounter = new HashMap<>();
    private final Map<String, Long> lastRestart = new HashMap<>();
    private final Set<String> restarting = new HashSet<>();
    /** 已锁定服务开始不正常的时间 */
    private final Map<String, Long> unhealthySince = new HashMap<>();
    /** 已判定为异常（修复后仍未拉起）的服务 → 原因 */
    private final Map<String, String> abnormal = new HashMap<>();
    private final ArrayDeque<String> logs = new ArrayDeque<>();

    private long readyAt;
    private boolean initialized;
    /** 自己写设置的时间戳，用来屏蔽由此触发的 ContentObserver 回调，避免自激循环 */
    private volatile long lastSelfWrite;
    /** 每个固定服务最近的恢复时间戳，用于识别被外部反复强杀 */
    private final Map<String, ArrayDeque<Long>> recoverHistory = new HashMap<>();
    /** 已就外部强杀发出过警告的服务，避免刷屏 */
    private final Set<String> flappingWarned = new HashSet<>();

    static synchronized void start(Object ams) {
        if (sInstance != null) {
            return;
        }
        sInstance = new Keeper(ams);
        sInstance.boot();
    }

    private Keeper(Object ams) {
        this.ams = ams;
        this.amsLock = XposedHelpers.getObjectField(ams, "mLock");
    }

    private void boot() {
        HandlerThread thread = new HandlerThread("A11yKeeper");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::waitForBoot);
    }

    private void waitForBoot() {
        String done = (String) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.os.SystemProperties", null),
                "get", "sys.boot_completed", "");
        if (!"1".equals(done)) {
            handler.postDelayed(this::waitForBoot, 3_000);
            return;
        }
        try {
            init();
        } catch (Throwable t) {
            log("初始化失败: " + t);
            handler.postDelayed(this::waitForBoot, 10_000);
        }
    }

    private void init() {
        if (initialized) {
            return;
        }
        context = (Context) XposedHelpers.getObjectField(ams, "mContext");
        loadConfig();
        refreshPinnedPkgs();
        registerReceiver();
        registerObserver();
        hookForceStop();
        readyAt = SystemClock.elapsedRealtime() + BOOT_GRACE_MS;
        initialized = true;
        log("已启动，固定 " + pinned.size() + " 个服务");
        handler.post(this::periodicCheck);
    }

    // ------------------------------------------------------------------ 强制停止保护

    /**
     * Android 14+ / ColorOS 16 实测：包被 force-stop 时，系统会把它的无障碍服务从
     * enabled_accessibility_services 里删掉（等同于用户手动关闭）。清理类应用（黑阈、一键清理、
     * 电池管理）反复 force-stop 时，就会出现“恢复→又被关→恢复”的拉锯。
     * 这里对已锁定的包跳过这一步：设置保持开启，只需把崩溃的连接重新拉起。
     */
    private void hookForceStop() {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.server.accessibility.AccessibilityManagerService",
                    ams.getClass().getClassLoader());
            Set<?> hooks = XposedBridge.hookAllMethods(cls, "onPackagesForceStoppedLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            Set<String> keep = pinnedPkgs;
                            if (keep.isEmpty()) {
                                return;
                            }
                            for (int i = 0; i < p.args.length; i++) {
                                if (!(p.args[i] instanceof String[])) {
                                    continue;
                                }
                                List<String> rest = new ArrayList<>();
                                List<String> hit = new ArrayList<>();
                                for (String s : (String[]) p.args[i]) {
                                    (keep.contains(s) ? hit : rest).add(s);
                                }
                                if (!hit.isEmpty()) {
                                    p.args[i] = rest.toArray(new String[0]);
                                    handler.post(() -> onPinnedForceStopped(hit));
                                }
                            }
                        }
                    });
            if (hooks.isEmpty()) {
                log("未找到 onPackagesForceStoppedLocked，强制停止保护不可用");
            }
        } catch (Throwable t) {
            log("强制停止保护初始化失败: " + t);
        }
    }

    private void onPinnedForceStopped(List<String> pkgs) {
        for (String c : new ArrayList<>(pinned)) {
            ComponentName cn = ComponentName.unflattenFromString(c);
            if (cn == null || !pkgs.contains(cn.getPackageName())) {
                continue;
            }
            if (!allowRecover(c)) {
                continue;
            }
            log(cn.getPackageName() + " 被强制停止，保持开启并重新拉起");
            // 进程被杀后连接进入 crashed，需要一次“关→开”才会重新绑定
            handler.postDelayed(() -> {
                lastRestart.remove(c);
                restartService(c, "被强制停止");
            }, 1_500);
        }
    }

    private void refreshPinnedPkgs() {
        Set<String> s = new HashSet<>();
        for (String c : pinned) {
            ComponentName cn = ComponentName.unflattenFromString(c);
            if (cn != null) {
                s.add(cn.getPackageName());
            }
        }
        pinnedPkgs = s;
    }

    /**
     * 频繁被关/被杀时限流：2 分钟内已恢复 4 次，则之后每分钟最多恢复一次，并提示用户排查。
     * 避免和清理工具无休止地拉锯、耗电。
     */
    private boolean allowRecover(String c) {
        long now = SystemClock.elapsedRealtime();
        ArrayDeque<Long> h = recoverHistory.get(c);
        if (h == null) {
            h = new ArrayDeque<>();
            recoverHistory.put(c, h);
        }
        while (!h.isEmpty() && now - h.peekFirst() > FLAP_WINDOW_MS) {
            h.pollFirst();
        }
        if (h.isEmpty()) {
            flappingWarned.remove(c);
        }
        if (h.size() >= FLAP_COUNT && now - h.peekLast() < FLAP_BACKOFF_MS) {
            if (flappingWarned.add(c)) {
                log("⚠ " + shortName(c) + " 2 分钟内被关闭/强杀 " + h.size()
                        + " 次，疑似有应用在反复强制停止它（黑阈、一键清理、电池优化等），"
                        + "已降为每分钟恢复一次；请把它加入这些应用的白名单");
            }
            return false;
        }
        h.addLast(now);
        return true;
    }

    // ------------------------------------------------------------------ 通信

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_QUERY);
        filter.addAction(Constants.ACTION_LOCK);
        filter.addAction(Constants.ACTION_RESTART);
        filter.addAction(Constants.ACTION_ENABLE);
        filter.addAction(Constants.ACTION_DISABLE);
        filter.addAction(Constants.ACTION_CLEAR_LOG);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleCommand(intent, this);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, null, handler);
        }
    }

    /** 校验广播来自本模块 App：PendingIntent 的创建者由系统记录，无法伪造 */
    @SuppressWarnings("deprecation")
    private boolean verifyCaller(Intent intent) {
        try {
            PendingIntent pi = intent.getParcelableExtra(Constants.EXTRA_AUTH);
            if (pi == null || !Constants.MODULE_PACKAGE.equals(pi.getCreatorPackage())) {
                return false;
            }
            int expected = context.getPackageManager().getPackageUid(Constants.MODULE_PACKAGE, 0);
            return pi.getCreatorUid() % 100000 == expected % 100000;
        } catch (Throwable t) {
            return false;
        }
    }

    private void handleCommand(Intent intent, BroadcastReceiver receiver) {
        String action = intent.getAction();
        if (!verifyCaller(intent)) {
            log("拒绝未授权的命令: " + action);
            return;
        }
        try {
            String comp = null;
            String rawComp = intent.getStringExtra(Constants.EXTRA_COMPONENT);
            ComponentName compName = rawComp == null ? null : ComponentName.unflattenFromString(rawComp);
            if (compName != null) {
                comp = compName.flattenToString();
            }
            if (Constants.ACTION_LOCK.equals(action)) {
                if (comp != null) {
                    boolean lock = intent.getBooleanExtra(Constants.EXTRA_LOCKED, true);
                    boolean changed = lock ? pinned.add(comp) : pinned.remove(comp);
                    if (changed) {
                        stuckCounter.remove(comp);
                        saveConfig();
                        log((lock ? "锁定 " : "解除锁定 ") + shortName(comp));
                    }
                    if (lock) {
                        check(false);
                    }
                }
            } else if (Constants.ACTION_RESTART.equals(action)) {
                if (comp != null) {
                    restartService(comp, "手动");
                }
            } else if (Constants.ACTION_ENABLE.equals(action)) {
                if (comp != null) {
                    int userId = currentUserId();
                    Set<String> enabled = readEnabledSetting(userId);
                    if (enabled.add(comp)) {
                        writeEnabledSetting(userId, enabled);
                    }
                    log("手动开启 " + shortName(comp));
                }
            } else if (Constants.ACTION_DISABLE.equals(action)) {
                if (comp != null) {
                    if (pinned.remove(comp)) {
                        stuckCounter.remove(comp);
                        saveConfig();
                        log("解除锁定 " + shortName(comp) + "（手动关闭）");
                    }
                    int userId = currentUserId();
                    Set<String> enabled = readEnabledSetting(userId);
                    if (enabled.remove(comp)) {
                        writeEnabledSetting(userId, enabled);
                    }
                    log("手动关闭 " + shortName(comp));
                }
            } else if (Constants.ACTION_CLEAR_LOG.equals(action)) {
                synchronized (logs) {
                    logs.clear();
                }
            }
            if (receiver.isOrderedBroadcast()) {
                // 开关操作后给 AMS 一点时间完成绑定，再回传状态
                if (!Constants.ACTION_QUERY.equals(action)
                        && !Constants.ACTION_CLEAR_LOG.equals(action)) {
                    SystemClock.sleep(Constants.ACTION_RESTART.equals(action) ? 0 : 400);
                }
                receiver.setResultData(buildStatus().toString());
            }
        } catch (Throwable t) {
            log("处理命令失败 " + action + ": " + t);
            if (receiver.isOrderedBroadcast()) {
                receiver.setResultData("{\"error\":" + JSONObject.quote(String.valueOf(t)) + "}");
            }
        }
    }

    private void registerObserver() {
        ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                // 屏蔽由本模块写设置触发的回调，避免“写→回调→再写”的自激循环
                if (SystemClock.elapsedRealtime() - lastSelfWrite < 1_500) {
                    return;
                }
                handler.removeCallbacks(quickCheck);
                handler.postDelayed(quickCheck, 800);
            }
        };
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_ENABLED), false, observer);
    }

    private final Runnable quickCheck = () -> check(false);

    // ------------------------------------------------------------------ 检查逻辑

    private void periodicCheck() {
        try {
            check(true);
        } catch (Throwable t) {
            log("检查异常: " + t);
        }
        try {
            updateAbnormal();
        } catch (Throwable t) {
            log("异常检测失败: " + t);
        }
        handler.postDelayed(this::periodicCheck, CHECK_INTERVAL_MS);
    }

    // ------------------------------------------------------------------ 异常（修复后仍未拉起）

    /**
     * 已锁定的服务持续 ABNORMAL_AFTER_MS 仍未正常运行（未开启 / 未绑定 / 故障），
     * 说明自动修复没能拉起 → 标记为异常，写日志并弹出 4 秒提示。恢复后自动清除。
     */
    private void updateAbnormal() {
        if (!initialized || SystemClock.elapsedRealtime() < readyAt || !isUserUnlocked()) {
            return;
        }
        Snapshot snap = snapshot();
        if (snap == null) {
            return;
        }
        Set<String> enabled = readEnabledSetting(currentUserId());
        long now = SystemClock.elapsedRealtime();
        List<String> newly = new ArrayList<>();
        abnormal.keySet().retainAll(pinned);
        unhealthySince.keySet().retainAll(pinned);
        for (String c : new ArrayList<>(pinned)) {
            if (!snap.installed.containsKey(c)) {
                unhealthySince.remove(c);
                abnormal.remove(c);
                continue;
            }
            String reason = null;
            if (!enabled.contains(c)) {
                reason = "开关被关闭且未能恢复";
            } else if (snap.deadBinder.contains(c)) {
                reason = "进程已死";
            } else if (snap.crashed.contains(c)) {
                reason = "服务故障";
            } else if (!snap.bound.contains(c)) {
                reason = snap.binding.contains(c) ? "一直在连接" : "未运行";
            }
            if (reason == null) {
                unhealthySince.remove(c);
                if (abnormal.remove(c) != null) {
                    log("✓ " + labelOf(c, snap) + " 已恢复正常");
                }
                continue;
            }
            if (restarting.contains(c)) {
                continue;
            }
            Long since = unhealthySince.get(c);
            if (since == null) {
                unhealthySince.put(c, now);
                continue;
            }
            if (now - since < ABNORMAL_AFTER_MS) {
                continue;
            }
            if (!abnormal.containsKey(c)) {
                newly.add(labelOf(c, snap) + "（" + reason + "）");
            }
            abnormal.put(c, reason);
        }
        if (!newly.isEmpty()) {
            String msg = "未能拉起：" + TextUtils.join("、", newly);
            log("✗ " + msg);
            showToast("无障碍保持\n" + msg);
        }
    }

    private String labelOf(String c, Snapshot snap) {
        AccessibilityServiceInfo info = snap.installed.get(c);
        if (info != null) {
            try {
                return String.valueOf(info.getResolveInfo().serviceInfo.applicationInfo
                        .loadLabel(context.getPackageManager()));
            } catch (Throwable ignored) {
            }
        }
        return shortName(c);
    }

    private android.view.View toastView;

    /** 在 system_server 中添加系统浮层作为 Toast，固定显示 TOAST_MS；失败时退回普通 Toast */
    private void showToast(String text) {
        handler.post(() -> {
            try {
                android.view.WindowManager wm;
                Context wc = context;
                android.hardware.display.DisplayManager dm =
                        context.getSystemService(android.hardware.display.DisplayManager.class);
                Context dc = context.createDisplayContext(
                        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY));
                wc = Build.VERSION.SDK_INT >= 30
                        ? dc.createWindowContext(TYPE_SECURE_SYSTEM_OVERLAY, null) : dc;
                wm = wc.getSystemService(android.view.WindowManager.class);
                removeToast(wm);

                float d = wc.getResources().getDisplayMetrics().density;
                android.widget.TextView tv = new android.widget.TextView(wc);
                tv.setText(text);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding((int) (18 * d), (int) (10 * d), (int) (18 * d), (int) (10 * d));
                tv.setMaxWidth((int) (320 * d));
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xE6C62828);
                bg.setCornerRadius(20 * d);
                tv.setBackground(bg);

                android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                        TYPE_SECURE_SYSTEM_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        android.graphics.PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
                lp.y = (int) (140 * d);
                lp.setTitle("A11yKeeperToast");
                wm.addView(tv, lp);
                toastView = tv;
                final android.view.WindowManager fwm = wm;
                handler.postDelayed(() -> {
                    if (toastView == tv) {
                        removeToast(fwm);
                    }
                }, TOAST_MS);
            } catch (Throwable t) {
                log("浮层提示失败，改用 Toast: " + t);
                try {
                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void removeToast(android.view.WindowManager wm) {
        android.view.View v = toastView;
        toastView = null;
        if (v != null) {
            try {
                wm.removeViewImmediate(v);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * @param detectStuck 是否做假死检测（设置变化触发的快速检查只补开关，不判假死）
     */
    private void check(boolean detectStuck) {
        if (!initialized || pinned.isEmpty()) {
            return;
        }
        int userId = currentUserId();
        Snapshot snap = snapshot();
        if (snap == null) {
            return;
        }

        // 1. 被关闭的固定服务 → 写回设置
        Set<String> enabledSetting = readEnabledSetting(userId);
        List<String> missing = new ArrayList<>();
        for (String c : pinned) {
            if (restarting.contains(c)) {
                continue;
            }
            if (!snap.installed.containsKey(c)) {
                continue; // 未安装或包不可见，跳过
            }
            if (!enabledSetting.contains(c)) {
                if (allowRecover(c)) {
                    missing.add(c);
                }
            }
        }
        if (!missing.isEmpty()) {
            enabledSetting.addAll(missing);
            writeEnabledSetting(userId, enabledSetting);
            log("恢复被关闭的服务: " + TextUtils.join(", ", shortNames(missing)));
            return; // 等下一轮再判断是否绑定成功
        }

        // 2. 假死检测
        if (!detectStuck || SystemClock.elapsedRealtime() < readyAt || !isUserUnlocked()) {
            return;
        }
        for (String c : pinned) {
            if (restarting.contains(c) || !snap.installed.containsKey(c)
                    || !enabledSetting.contains(c)) {
                stuckCounter.remove(c);
                continue;
            }
            String reason = null;
            if (snap.crashed.contains(c)) {
                reason = "服务故障(crashed)";
            } else if (snap.deadBinder.contains(c)) {
                reason = "进程已死但仍标记为已绑定";
            } else if (!snap.bound.contains(c)) {
                reason = snap.binding.contains(c) ? "绑定卡住" : "已开启但未绑定";
            }
            if (reason == null) {
                stuckCounter.remove(c);
                continue;
            }
            // 死 binder 是确定状态，立即处理；crashed 可能是进程刚被杀、AMS 正在自动重启
            // （实测 kill -9 后约 1 秒自动恢复），与其他情况一样需连续命中
            int n = stuckCounter.containsKey(c) ? stuckCounter.get(c) + 1 : 1;
            stuckCounter.put(c, n);
            boolean definite = snap.deadBinder.contains(c);
            if (!definite && n < STUCK_THRESHOLD) {
                continue;
            }
            Long last = lastRestart.get(c);
            if (last != null && SystemClock.elapsedRealtime() - last < RESTART_COOLDOWN_MS) {
                continue;
            }
            stuckCounter.remove(c);
            restartService(c, reason);
        }
    }

    /** 模拟用户手动“关闭→打开”：从设置中移除，间隔后写回。系统会清理 crashed/binding 状态并重新绑定。 */
    private void restartService(String comp, String reason) {
        if (restarting.contains(comp)) {
            return;
        }
        int userId = currentUserId();
        Set<String> enabled = readEnabledSetting(userId);
        restarting.add(comp);
        lastRestart.put(comp, SystemClock.elapsedRealtime());
        log("重启 " + shortName(comp) + "（" + reason + "）");
        enabled.remove(comp);
        writeEnabledSetting(userId, enabled);
        handler.postDelayed(() -> {
            try {
                Set<String> now = readEnabledSetting(userId);
                now.add(comp);
                writeEnabledSetting(userId, now);
            } catch (Throwable t) {
                log("重新开启失败 " + shortName(comp) + ": " + t);
            } finally {
                restarting.remove(comp);
            }
        }, TOGGLE_GAP_MS);
    }

    // ------------------------------------------------------------------ AMS 状态读取

    private static final class Snapshot {
        final Map<String, AccessibilityServiceInfo> installed = new HashMap<>();
        final Set<String> bound = new HashSet<>();
        final Set<String> binding = new HashSet<>();
        final Set<String> crashed = new HashSet<>();
        final Set<String> deadBinder = new HashSet<>();
    }

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        synchronized (amsLock) {
            Object userState = XposedHelpers.callMethod(ams, "getCurrentUserStateLocked");
            @SuppressWarnings("unchecked")
            List<AccessibilityServiceInfo> installed = (List<AccessibilityServiceInfo>)
                    XposedHelpers.getObjectField(userState, "mInstalledServices");
            for (AccessibilityServiceInfo info : installed) {
                ComponentName cn = ComponentName.unflattenFromString(info.getId());
                if (cn != null) {
                    s.installed.put(cn.flattenToString(), info);
                }
            }
            List<?> boundList = (List<?>) XposedHelpers.getObjectField(userState, "mBoundServices");
            for (Object conn : boundList) {
                ComponentName cn = connComponent(conn);
                if (cn == null) {
                    continue;
                }
                String key = cn.flattenToString();
                s.bound.add(key);
                IBinder binder = connBinder(conn);
                if (binder != null && !binder.isBinderAlive()) {
                    s.deadBinder.add(key);
                }
            }
            addAll(s.binding, optField(userState, "mBindingServices"));
            addAll(s.crashed, optField(userState, "mCrashedServices"));
        }
        return s;
    }

    private static ComponentName connComponent(Object conn) {
        try {
            return (ComponentName) XposedHelpers.callMethod(conn, "getComponentName");
        } catch (Throwable ignored) {
        }
        try {
            return (ComponentName) XposedHelpers.getObjectField(conn, "mComponentName");
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 新版 AOSP 字段为 mClient，旧版为 mService(IBinder) / mServiceInterface */
    private static IBinder connBinder(Object conn) {
        for (String name : new String[]{"mClient", "mServiceInterface", "mService"}) {
            Object v = optField(conn, name);
            if (v instanceof IBinder) {
                return (IBinder) v;
            }
            if (v instanceof android.os.IInterface) {
                return ((android.os.IInterface) v).asBinder();
            }
        }
        return null;
    }

    private static Object optField(Object obj, String name) {
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void addAll(Set<String> out, Object set) {
        if (!(set instanceof Collection)) {
            return;
        }
        for (Object o : (Collection<?>) set) {
            if (o instanceof ComponentName) {
                out.add(((ComponentName) o).flattenToString());
            }
        }
    }

    private int currentUserId() {
        try {
            return XposedHelpers.getIntField(ams, "mCurrentUserId");
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean isUserUnlocked() {
        try {
            UserManager um = context.getSystemService(UserManager.class);
            return um == null || um.isUserUnlocked();
        } catch (Throwable t) {
            return true;
        }
    }

    // ------------------------------------------------------------------ Settings

    private Set<String> readEnabledSetting(int userId) {
        String raw = (String) XposedHelpers.callStaticMethod(Settings.Secure.class,
                "getStringForUser", context.getContentResolver(), KEY_ENABLED, userId);
        Set<String> out = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(raw)) {
            for (String part : raw.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (cn != null) {
                    out.add(cn.flattenToString());
                }
            }
        }
        return out;
    }

    private void writeEnabledSetting(int userId, Set<String> set) {
        ContentResolver cr = context.getContentResolver();
        lastSelfWrite = SystemClock.elapsedRealtime();
        XposedHelpers.callStaticMethod(Settings.Secure.class, "putStringForUser",
                cr, KEY_ENABLED, TextUtils.join(":", set), userId);
        if (!set.isEmpty()) {
            XposedHelpers.callStaticMethod(Settings.Secure.class, "putIntForUser",
                    cr, KEY_A11Y_ON, 1, userId);
        }
    }

    // ------------------------------------------------------------------ 配置 / 状态 / 日志

    private void loadConfig() {
        pinned.clear();
        if (!CONFIG.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(CONFIG)) {
            byte[] buf = new byte[(int) CONFIG.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            for (String line : new String(buf, 0, off, StandardCharsets.UTF_8).split("\n")) {
                ComponentName cn = ComponentName.unflattenFromString(line.trim());
                if (cn != null) {
                    pinned.add(cn.flattenToString());
                }
            }
        } catch (Throwable t) {
            log("读取配置失败: " + t);
        }
    }

    private void saveConfig() {
        refreshPinnedPkgs();
        File tmp = new File(CONFIG.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(TextUtils.join("\n", pinned).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable t) {
            log("写入配置失败: " + t);
            return;
        }
        if (!tmp.renameTo(CONFIG)) {
            log("写入配置失败: rename");
        }
    }

    private JSONObject buildStatus() throws Exception {
        JSONObject root = new JSONObject();
        Snapshot snap = snapshot();
        Set<String> enabled = readEnabledSetting(currentUserId());
        PackageManager pm = context.getPackageManager();
        JSONArray services = new JSONArray();
        Set<String> all = new LinkedHashSet<>(snap.installed.keySet());
        all.addAll(pinned);
        for (String c : all) {
            JSONObject o = new JSONObject();
            o.put("component", c);
            AccessibilityServiceInfo info = snap.installed.get(c);
            String label = shortName(c);
            if (info != null) {
                try {
                    ResolveInfo ri = info.getResolveInfo();
                    CharSequence app = ri.serviceInfo.applicationInfo.loadLabel(pm);
                    CharSequence svc = ri.loadLabel(pm);
                    label = TextUtils.equals(app, svc) ? String.valueOf(app) : app + " · " + svc;
                } catch (Throwable ignored) {
                }
            }
            o.put("label", label);
            o.put("installed", info != null);
            boolean system = false;
            if (info != null) {
                try {
                    int flags = info.getResolveInfo().serviceInfo.applicationInfo.flags;
                    system = (flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM
                            | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                } catch (Throwable ignored) {
                }
            }
            o.put("system", system);
            o.put("pinned", pinned.contains(c));
            o.put("enabled", enabled.contains(c));
            o.put("bound", snap.bound.contains(c) && !snap.deadBinder.contains(c));
            o.put("crashed", snap.crashed.contains(c));
            o.put("binding", snap.binding.contains(c));
            o.put("restarting", restarting.contains(c));
            String ab = pinned.contains(c) ? abnormal.get(c) : null;
            o.put("abnormal", ab != null);
            if (ab != null) {
                o.put("abnormalReason", ab);
            }
            services.put(o);
        }
        root.put("services", services);
        JSONArray logArr = new JSONArray();
        synchronized (logs) {
            for (String l : logs) {
                logArr.put(l);
            }
        }
        root.put("logs", logArr);
        return root;
    }

    private void log(String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                + " " + msg;
        synchronized (logs) {
            logs.addFirst(line);
            while (logs.size() > LOG_MAX) {
                logs.removeLast();
            }
        }
        XposedBridge.log(Constants.TAG + ": " + msg);
    }

    private static String shortName(String comp) {
        ComponentName cn = ComponentName.unflattenFromString(comp);
        return cn == null ? comp : cn.getPackageName();
    }

    private static List<String> shortNames(List<String> comps) {
        List<String> out = new ArrayList<>();
        for (String c : comps) {
            out.add(shortName(c));
        }
        return out;
    }
}
