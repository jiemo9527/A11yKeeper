package com.jiemo.a11ykeeper;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int ACCENT = 0xFF1565C0;
    private static final int GREEN = 0xFF2E7D32;
    private static final int ORANGE = 0xFFE65100;
    private static final int RED = 0xFFC62828;
    private static final int LINE = 0x33888888;
    private static final String LAUNCHER_ALIAS = Constants.MODULE_PACKAGE + ".Launcher";

    private static final int TAB_SERVICES = 0;
    private static final int TAB_LOGS = 1;
    private static final int TAB_SETTINGS = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PendingIntent auth;

    private boolean moduleActive;
    private String moduleError;
    private String lastData = "";
    private final List<JSONObject> services = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();

    /** 0 全部 / 1 已锁定 / 2 未锁定 */
    private int lockFilter;
    /** 0 全部 / 1 已开启 / 2 未开启 */
    private int enableFilter;
    /** 关键词过滤（匹配名称或组件名，忽略大小写） */
    private String keyword = "";
    /** 默认隐藏系统自带服务（已锁定的始终显示） */
    private boolean showSystem;

    private int currentTab = TAB_SERVICES;
    private final View[] pages = new View[3];
    private final TextView[] tabs = new TextView[3];

    private TextView statusView;
    private LinearLayout lockChips;
    private LinearLayout enableChips;
    private TextView systemToggle;
    private LinearLayout listView;
    private TextView logView;
    private Switch hideIconSwitch;

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (currentTab != TAB_SETTINGS) {
                query();
            }
            main.postDelayed(this, 5_000);
        }
    };

    // ================================================================== 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout outer = vertical();
        outer.setFitsSystemWindows(true);

        // 头部：标题 + 模块状态
        LinearLayout header = vertical();
        header.setPadding(dp(16), dp(12), dp(16), dp(6));
        TextView title = text(getString(R.string.app_name), 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout titleRow = horizontal();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(32), dp(32));
        llp.setMarginEnd(dp(10));
        titleRow.addView(logo, llp);
        titleRow.addView(title);
        header.addView(titleRow);
        statusView = text("", 13);
        statusView.setPadding(0, dp(4), 0, 0);
        header.addView(statusView);
        outer.addView(header);

        FrameLayout container = new FrameLayout(this);
        pages[TAB_SERVICES] = buildServicesPage();
        pages[TAB_LOGS] = buildLogsPage();
        pages[TAB_SETTINGS] = buildSettingsPage();
        for (View p : pages) {
            container.addView(p, new FrameLayout.LayoutParams(-1, -1));
        }
        outer.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        // 底部导航
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        outer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"服务", "日志", "设置"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = text(names[i], 15);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> switchTab(idx));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        outer.addView(nav);

        setContentView(outer);
        switchTab(TAB_SERVICES);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHideIconSwitch();
        main.removeCallbacks(autoRefresh);
        main.post(autoRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(autoRefresh);
    }

    private void switchTab(int idx) {
        currentTab = idx;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? ACCENT : Color.GRAY);
            tabs[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        if (idx != TAB_SETTINGS) {
            query();
        }
    }

    // ================================================================== 页面：服务

    private View buildServicesPage() {
        LinearLayout page = vertical();

        EditText search = new EditText(this);
        search.setHint("搜索名称或包名");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(10));
        sbg.setStroke(dp(1), LINE);
        search.setBackground(sbg);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                keyword = e.toString().trim().toLowerCase(java.util.Locale.ROOT);
                renderServices();
            }
        });
        LinearLayout searchWrap = vertical();
        searchWrap.setPadding(dp(16), dp(4), dp(16), dp(4));
        searchWrap.addView(search, new LinearLayout.LayoutParams(-1, -2));
        page.addView(searchWrap);

        LinearLayout filters = vertical();
        filters.setPadding(dp(16), 0, dp(16), dp(4));
        lockChips = horizontal();
        enableChips = horizontal();
        enableChips.setPadding(0, dp(6), 0, 0);
        filters.addView(lockChips);
        filters.addView(enableChips);
        systemToggle = text("", 13);
        systemToggle.setTextColor(ACCENT);
        systemToggle.setPadding(0, dp(8), 0, dp(2));
        systemToggle.setOnClickListener(v -> {
            showSystem = !showSystem;
            renderServices();
        });
        filters.addView(systemToggle);
        page.addView(filters);

        ScrollView sv = new ScrollView(this);
        listView = vertical();
        listView.setPadding(dp(16), 0, dp(16), dp(16));
        sv.addView(listView);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void renderServices() {
        int nLocked = 0;
        int nEnabled = 0;
        int nAbnormal = 0;
        for (JSONObject o : services) {
            if (o.optBoolean("pinned")) {
                nLocked++;
            }
            if (o.optBoolean("enabled")) {
                nEnabled++;
            }
            if (o.optBoolean("abnormal")) {
                nAbnormal++;
            }
        }
        int total = services.size();
        if (lockFilter == 3 && nAbnormal == 0) {
            lockFilter = 0; // 异常全部恢复后自动回到“全部”
        }

        lockChips.removeAllViews();
        addChip(lockChips, "全部 " + total, lockFilter == 0, v -> setLockFilter(0));
        addChip(lockChips, "已锁定 " + nLocked, lockFilter == 1, v -> setLockFilter(1));
        addChip(lockChips, "未锁定 " + (total - nLocked), lockFilter == 2, v -> setLockFilter(2));
        if (nAbnormal > 0) {
            addChip(lockChips, "异常 " + nAbnormal, lockFilter == 3, v -> setLockFilter(3), RED);
        }
        enableChips.removeAllViews();
        addChip(enableChips, "全部", enableFilter == 0, v -> setEnableFilter(0));
        addChip(enableChips, "已开启 " + nEnabled, enableFilter == 1, v -> setEnableFilter(1));
        addChip(enableChips, "未开启 " + (total - nEnabled), enableFilter == 2, v -> setEnableFilter(2));

        List<JSONObject> shown = new ArrayList<>();
        int hiddenSystem = 0;
        for (JSONObject o : services) {
            boolean pinned = o.optBoolean("pinned");
            boolean enabled = o.optBoolean("enabled");
            if (lockFilter == 1 && !pinned || lockFilter == 2 && pinned
                    || lockFilter == 3 && !o.optBoolean("abnormal")) {
                continue;
            }
            if (enableFilter == 1 && !enabled || enableFilter == 2 && enabled) {
                continue;
            }
            if (o.optBoolean("system") && !pinned && !showSystem) {
                hiddenSystem++;
                continue;
            }
            if (!keyword.isEmpty()) {
                String hay = (o.optString("label") + " " + o.optString("component"))
                        .toLowerCase(java.util.Locale.ROOT);
                if (!hay.contains(keyword)) {
                    continue;
                }
            }
            shown.add(o);
        }
        shown.sort((a, b) -> {
            int c = Boolean.compare(b.optBoolean("abnormal"), a.optBoolean("abnormal"));
            if (c != 0) {
                return c;
            }
            c = Boolean.compare(b.optBoolean("pinned"), a.optBoolean("pinned"));
            if (c != 0) {
                return c;
            }
            c = Boolean.compare(a.optBoolean("system"), b.optBoolean("system"));
            if (c != 0) {
                return c;
            }
            return a.optString("label").compareToIgnoreCase(b.optString("label"));
        });

        if (showSystem) {
            systemToggle.setText("▲ 隐藏系统自带服务");
            systemToggle.setVisibility(View.VISIBLE);
        } else if (hiddenSystem > 0) {
            systemToggle.setText("▼ 显示系统自带服务（已隐藏 " + hiddenSystem + "）");
            systemToggle.setVisibility(View.VISIBLE);
        } else {
            systemToggle.setVisibility(View.GONE);
        }

        listView.removeAllViews();
        for (JSONObject o : shown) {
            listView.addView(buildCard(o));
        }
        if (shown.isEmpty()) {
            TextView empty = text(services.isEmpty() ? "没有读取到无障碍服务" : "没有符合筛选条件的服务", 13);
            empty.setAlpha(0.6f);
            empty.setPadding(0, dp(24), 0, 0);
            empty.setGravity(Gravity.CENTER);
            listView.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void setLockFilter(int f) {
        lockFilter = f;
        renderServices();
    }

    private void setEnableFilter(int f) {
        enableFilter = f;
        renderServices();
    }

    private View buildCard(JSONObject o) {
        final String comp = o.optString("component");
        final String label = o.optString("label");
        boolean installed = o.optBoolean("installed");
        boolean pinned = o.optBoolean("pinned");
        boolean enabled = o.optBoolean("enabled");

        LinearLayout card = vertical();
        card.setPadding(dp(12), dp(10), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setStroke(o.optBoolean("abnormal") ? dp(2) : dp(1), o.optBoolean("abnormal") ? RED : LINE);
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(8);
        card.setLayoutParams(clp);

        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(installed ? label : label + "（未安装）", 15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Switch lock = new Switch(this);
        lock.setText("锁定 ");
        lock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lock.setChecked(pinned);
        lock.setEnabled(moduleActive);
        lock.setOnCheckedChangeListener((b, checked) -> {
            command(Constants.ACTION_LOCK, comp, checked);
            if (checked) {
                toast("已锁定：" + label + "，将保持开启");
            }
        });
        top.addView(lock);
        card.addView(top);

        TextView compView = text(comp, 10);
        compView.setAlpha(0.55f);
        card.addView(compView);

        TextView state = text(stateText(o), 12);
        state.setTextColor(stateColor(o));
        state.setPadding(0, dp(2), 0, 0);
        card.addView(state);

        if (moduleActive && installed) {
            LinearLayout actions = horizontal();
            actions.setGravity(Gravity.END);
            if (enabled) {
                addAction(actions, "重启", v -> command(Constants.ACTION_RESTART, comp, null));
                addAction(actions, "关闭", v -> confirmDisable(comp, label, pinned));
            } else {
                addAction(actions, "开启", v -> command(Constants.ACTION_ENABLE, comp, null));
            }
            card.addView(actions);
        }
        return card;
    }

    private void confirmDisable(String comp, String label, boolean pinned) {
        if (!pinned) {
            command(Constants.ACTION_DISABLE, comp, null);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("关闭已锁定的服务")
                .setMessage(label + " 已锁定，关闭会同时解除锁定，否则会被自动重新打开。继续？")
                .setPositiveButton("解除锁定并关闭",
                        (d, w) -> command(Constants.ACTION_DISABLE, comp, null))
                .setNegativeButton("取消", null)
                .show();
    }

    private static String stateText(JSONObject o) {
        if (o.optBoolean("abnormal")) {
            return "✗ 异常：自动修复后仍未拉起（" + o.optString("abnormalReason") + "）";
        }
        if (!o.optBoolean("installed")) {
            return "未安装";
        }
        if (!o.optBoolean("enabled")) {
            return "未开启";
        }
        if (o.has("fallback")) {
            return "已开启";
        }
        if (o.optBoolean("restarting")) {
            return "已开启 · 正在重启";
        }
        if (o.optBoolean("crashed")) {
            return "已开启 · 故障（假死）";
        }
        if (o.optBoolean("bound")) {
            return "已开启 · 运行中";
        }
        if (o.optBoolean("binding")) {
            return "已开启 · 正在连接";
        }
        return "已开启 · 未运行";
    }

    private static int stateColor(JSONObject o) {
        if (o.optBoolean("abnormal")) {
            return RED;
        }
        if (!o.optBoolean("installed") || !o.optBoolean("enabled")) {
            return Color.GRAY;
        }
        if (o.has("fallback") || o.optBoolean("bound")) {
            return GREEN;
        }
        return ORANGE;
    }

    // ================================================================== 页面：日志

    private View buildLogsPage() {
        LinearLayout page = vertical();
        LinearLayout bar = horizontal();
        bar.setPadding(dp(16), 0, dp(16), dp(4));
        bar.setGravity(Gravity.END);
        addAction(bar, "刷新", v -> query());
        addAction(bar, "复制", v -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("A11yKeeper log", TextUtils.join("\n", logs)));
            toast("已复制");
        });
        addAction(bar, "清空", v -> command(Constants.ACTION_CLEAR_LOG, null, null));
        page.addView(bar);

        ScrollView sv = new ScrollView(this);
        logView = text("", 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(16), dp(4), dp(16), dp(16));
        sv.addView(logView);
        page.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void renderLogs() {
        if (!moduleActive) {
            logView.setText("（模块未生效，无日志）");
        } else if (logs.isEmpty()) {
            logView.setText("（暂无日志）");
        } else {
            logView.setText(TextUtils.join("\n", logs));
        }
    }

    // ================================================================== 页面：设置

    private View buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(8), dp(16), dp(16));
        sv.addView(page);

        hideIconSwitch = new Switch(this);
        hideIconSwitch.setText("隐藏桌面图标");
        hideIconSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hideIconSwitch.setPadding(0, dp(8), 0, dp(8));
        hideIconSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked == isIconHidden()) {
                return;
            }
            setIconHidden(checked);
            toast(checked ? "已隐藏，可从 LSPosed 管理器打开本模块" : "已恢复桌面图标");
        });
        page.addView(hideIconSwitch);
        TextView hideHint = text("隐藏后打开方式：LSPosed 管理器 → 模块 → 无障碍保持 → 点右侧设置/打开按钮。部分桌面需要稍等或重启桌面后生效。", 12);
        hideHint.setAlpha(0.7f);
        page.addView(hideHint);

        addSection(page, "工作方式",
                "• 已锁定的服务被关闭（被系统、清理工具或其他 App 关掉）会立即重新开启。\n"
                        + "• 已锁定的服务每 10 秒检查一次实际运行状态：开关显示已开启但系统标记为故障、"
                        + "进程已死或长时间未连接（假死），会自动先关闭再开启（间隔 1.5 秒）。\n"
                        + "• 同一服务自动修复至少间隔 60 秒，避免 App 本身崩溃时反复重启。\n"
                        + "• 开机后 45 秒内及锁屏未解锁时不做假死判断。\n"
                        + "• 未锁定的服务不做任何处理。");
        addSection(page, "按钮说明",
                "• 锁定：开关即时生效，锁定后自动保持开启。\n"
                        + "• 重启：手动先关闭再开启，用于修复假死。\n"
                        + "• 关闭：关闭服务；若已锁定会同时解除锁定。\n"
                        + "• 开启：手动开启服务（不锁定）。");
        addSection(page, "生效条件",
                "LSPosed 中启用本模块，作用域勾选「系统框架」，然后重启手机。"
                        + "配置保存在 /data/system/a11ykeeper_pinned.txt。");

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView ver = text("版本 " + version, 12);
        ver.setAlpha(0.5f);
        ver.setPadding(0, dp(16), 0, 0);
        page.addView(ver);
        return sv;
    }

    private void addSection(LinearLayout page, String title, String body) {
        TextView t = text(title, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        page.addView(t);
        TextView b = text(body, 13);
        b.setLineSpacing(dp(2), 1f);
        page.addView(b);
    }

    private boolean isIconHidden() {
        int s = getPackageManager().getComponentEnabledSetting(
                new ComponentName(getPackageName(), LAUNCHER_ALIAS));
        return s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setIconHidden(boolean hidden) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(getPackageName(), LAUNCHER_ALIAS),
                hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void refreshHideIconSwitch() {
        hideIconSwitch.setChecked(isIconHidden());
    }

    // ================================================================== 通信

    private void query() {
        send(new Intent(Constants.ACTION_QUERY));
    }

    private void command(String action, String comp, Boolean locked) {
        if (!moduleActive) {
            toast("模块未生效：请在 LSPosed 启用并勾选“系统框架”后重启");
            return;
        }
        Intent intent = new Intent(action);
        if (comp != null) {
            intent.putExtra(Constants.EXTRA_COMPONENT, comp);
        }
        if (locked != null) {
            intent.putExtra(Constants.EXTRA_LOCKED, locked.booleanValue());
        }
        send(intent);
        // 重启 / 开关后服务绑定需要时间，稍后再刷新一次
        main.postDelayed(this::query, 2_000);
    }

    private PendingIntent auth() {
        if (auth == null) {
            Intent i = new Intent(Constants.MODULE_PACKAGE + ".AUTH").setPackage(getPackageName());
            auth = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        }
        return auth;
    }

    /** 发往 system_server（包名 android）的有序广播；结果数据为 JSON 状态，null 表示模块未生效 */
    private void send(Intent intent) {
        intent.setPackage("android");
        intent.putExtra(Constants.EXTRA_AUTH, auth());
        sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                onResult(getResultData());
            }
        }, main, Activity.RESULT_OK, null, null);
    }

    private void onResult(String data) {
        if (data != null && data.equals(lastData)) {
            return; // 状态无变化，不重建列表（避免自动刷新打断操作/滚动）
        }
        lastData = data;
        moduleError = null;
        if (data == null) {
            moduleActive = false;
            loadFallback();
        } else {
            moduleActive = true;
            try {
                JSONObject root = new JSONObject(data);
                if (root.has("error")) {
                    moduleError = root.optString("error");
                } else {
                    services.clear();
                    JSONArray arr = root.getJSONArray("services");
                    for (int i = 0; i < arr.length(); i++) {
                        services.add(arr.getJSONObject(i));
                    }
                    logs.clear();
                    JSONArray lg = root.optJSONArray("logs");
                    if (lg != null) {
                        for (int i = 0; i < lg.length(); i++) {
                            logs.add(lg.getString(i));
                        }
                    }
                }
            } catch (Exception e) {
                moduleError = "解析失败：" + e;
            }
        }
        renderStatus();
        renderServices();
        renderLogs();
    }

    private void renderStatus() {
        if (moduleError != null) {
            statusView.setText("模块返回错误：" + moduleError);
            statusView.setTextColor(RED);
        } else if (moduleActive) {
            statusView.setText("● 模块已生效");
            statusView.setTextColor(GREEN);
        } else {
            statusView.setText("○ 模块未生效：LSPosed 启用本模块并勾选“系统框架”后重启手机");
            statusView.setTextColor(RED);
        }
    }

    /** 模块未生效时，用公开 API 显示列表（只读） */
    private void loadFallback() {
        services.clear();
        logs.clear();
        Set<String> enabled = new HashSet<>();
        String raw = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (!TextUtils.isEmpty(raw)) {
            for (String part : raw.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (cn != null) {
                    enabled.add(cn.flattenToString());
                }
            }
        }
        AccessibilityManager am = getSystemService(AccessibilityManager.class);
        PackageManager pm = getPackageManager();
        for (AccessibilityServiceInfo info : am.getInstalledAccessibilityServiceList()) {
            try {
                ResolveInfo ri = info.getResolveInfo();
                ComponentName cn = ComponentName.unflattenFromString(info.getId());
                String comp = cn == null ? info.getId() : cn.flattenToString();
                CharSequence app = ri.serviceInfo.applicationInfo.loadLabel(pm);
                CharSequence svc = ri.loadLabel(pm);
                JSONObject o = new JSONObject();
                o.put("component", comp);
                o.put("label", TextUtils.equals(app, svc) ? String.valueOf(app) : app + " · " + svc);
                o.put("installed", true);
                o.put("system", isSystemApp(ri.serviceInfo.applicationInfo));
                o.put("pinned", false);
                o.put("enabled", enabled.contains(comp));
                o.put("fallback", true);
                services.add(o);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isSystemApp(ApplicationInfo ai) {
        return ai != null && (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    // ================================================================== UI 工具

    private void addChip(LinearLayout parent, String label, boolean selected, View.OnClickListener l) {
        addChip(parent, label, selected, l, ACCENT);
    }

    private void addChip(LinearLayout parent, String label, boolean selected, View.OnClickListener l,
                         int color) {
        TextView chip = text(label, 13);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (selected) {
            bg.setColor(color);
            chip.setTextColor(Color.WHITE);
        } else {
            bg.setStroke(dp(1), color == ACCENT ? 0x66888888 : color);
            if (color != ACCENT) {
                chip.setTextColor(color);
            }
        }
        chip.setBackground(bg);
        chip.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        parent.addView(chip, lp);
    }

    private void addAction(LinearLayout parent, String label, View.OnClickListener l) {
        TextView b = text(label, 14);
        b.setTextColor(ACCENT);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setOnClickListener(l);
        parent.addView(b);
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
