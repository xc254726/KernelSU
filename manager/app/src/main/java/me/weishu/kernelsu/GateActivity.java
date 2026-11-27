package me.weishu.kernelsu;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import me.weishu.kernelsu.ui.util.DeviceCode;

import org.json.JSONObject;

import android.graphics.BitmapFactory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 卡密验证启动页（移植自 sbsu/tools/gate/GateActivity.java）。
 * 验证通过后进入管理器主界面。
 */
public class GateActivity extends Activity {

    private static final String BASE_URL = "https://sbkey.dpdns.org";
    private static final String PREFS = "sb_activation";
    private static final String KEY = "license_key";
    private static final String TARGET = "me.weishu.kernelsu.MainActivityKowsu";

    // 配色：冷灰渐变底 + 深藏青强调（呼应白猫图标的深色包）
    private static final int INK = 0xFF1B1B26;
    private static final int GREY = 0xFF8A8A96;
    private static final int GREY2 = 0xFF6E6E7A;
    private static final int BG_TOP = 0xFFF1F1F6;
    private static final int BG_BOT = 0xFFE6E6EF;
    private static final int ACCENT = 0xFF2F3247;
    private static final int ACCENT2 = 0xFF454A66;
    private static final int ACCENT_DARK = 0xFF23253A;
    private static final int INPUT_BG = 0xFFF4F4F9;
    private static final int INPUT_BORDER = 0xFFE3E3EA;
    private static final int ERROR = 0xFFD23B3B;

    private EditText input;
    private CheckBox remember;
    private Button submit;
    private TextView error;
    private TextView sub;

    // ---- 付款面板：卡密已过期 / 需先付款时展示（金额与收款码由后台下发）----
    private LinearLayout payPanel;
    private TextView payTitle;
    private TextView payAmount;
    private TextView payHint;
    private ImageView payQr;
    private Button payDone;
    private TextView payReset;
    private String lastKey = "";
    private boolean lastOpIsActivate = true;
    /** true = 当前面板是「别人已激活的卡密」触发的（付款后不会自动解绑，需卖家开通） */
    private boolean lastBorrowed = false;
    /** 付款面板里已经下过单的卡密，避免每次点验证都新建订单 */
    private String payOrderKey = "";
    /** 当前订单号，点「我已付款」时用来主动查单 */
    private String payOrderId = "";
    private ScrollView scroller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        String saved = storedKey(this);
        if (saved != null && !saved.isEmpty()) {
            input.setText(saved);
            remember.setChecked(true);
            error.setText("");
            sub.setText("正在验证已保存的卡密…");
            setBusy(true);
            lastKey = saved;
            lastOpIsActivate = false;
            verifyAsync(saved);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOT}));
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setMinimumHeight(getResources().getDisplayMetrics().heightPixels);

        // 圆形头像：白色圆盘 + 阴影托底，内嵌圆形裁切的应用图标
        FrameLayout avatarWrap = new FrameLayout(this);
        GradientDrawable plate = new GradientDrawable();
        plate.setShape(GradientDrawable.OVAL);
        plate.setColor(Color.WHITE);
        avatarWrap.setBackground(plate);
        avatarWrap.setElevation(dp(10));
        avatarWrap.setPadding(dp(6), dp(6), dp(6), dp(6));

        ImageView avatar = new ImageView(this);
        try {
            Drawable drawable = getPackageManager().getApplicationIcon(getPackageName());
            int px = dp(100);
            Bitmap src = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas iconCanvas = new Canvas(src);
            drawable.setBounds(0, 0, px, px);
            drawable.draw(iconCanvas);
            avatar.setImageBitmap(circleCrop(src));
        } catch (Exception e) {
            avatar.setBackground(circleDrawable(INK));
        }
        avatarWrap.addView(avatar, new FrameLayout.LayoutParams(dp(100), dp(100)));
        root.addView(avatarWrap, new LinearLayout.LayoutParams(dp(112), dp(112)));

        // 标题
        TextView title = new TextView(this);
        title.setText("chen");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.05f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(22);
        root.addView(title, tlp);

        // 副标题
        sub = new TextView(this);
        sub.setText("输入卡密以继续使用");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        sub.setTextColor(GREY);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        root.addView(sub, slp);

        // 白色卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundDrawable(Color.WHITE, dp(28), 0));
        card.setElevation(dp(10));
        card.setPadding(dp(24), dp(24), dp(24), dp(24));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(32);
        root.addView(card, clp);

        TextView label = new TextView(this);
        label.setText("卡 密");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(GREY);
        label.setLetterSpacing(0.12f);
        card.addView(label);

        input = new EditText(this);
        input.setHint("输入卡密");
        input.setHintTextColor(0xFFB6B6C2);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(INK);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(inputDrawable());
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(10);
        card.addView(input, ilp);

        // 回车直接触发验证
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit.performClick();
                return true;
            }
            return false;
        });

        error = new TextView(this);
        error.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        error.setTextColor(ERROR);
        error.setMinHeight(dp(18));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(6);
        card.addView(error, elp);

        // 记住此卡密
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        card.addView(row, rlp);

        remember = new CheckBox(this);
        remember.setChecked(true);
        remember.setButtonTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{ACCENT, 0xFFB6B6C2}));
        remember.setMinimumWidth(0);
        remember.setMinWidth(0);
        row.addView(remember);

        TextView remLabel = new TextView(this);
        remLabel.setText("记住此卡密");
        remLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        remLabel.setTextColor(GREY2);
        LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        remLp.leftMargin = dp(2);
        row.addView(remLabel, remLp);

        submit = new Button(this);
        submit.setText("验证");
        submit.setAllCaps(false);
        submit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        submit.setTextColor(Color.WHITE);
        submit.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        submit.setLetterSpacing(0.12f);
        submit.setBackground(buttonDrawable());
        submit.setStateListAnimator(null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        blp.topMargin = dp(18);
        card.addView(submit, blp);

        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = java.text.Normalizer
                        .normalize(input.getText().toString().trim(), java.text.Normalizer.Form.NFKC)
                        .replaceAll("[^\\p{L}\\p{N}_-]", "");
                if (key.length() < 2 || key.length() > 40) {
                    error.setText("卡密格式不对");
                    return;
                }
                error.setText("");
                hidePayPanel();
                sub.setText("正在验证…");
                setBusy(true);
                lastKey = key;
                lastOpIsActivate = true;
                activateAsync(key);
            }
        });

        // ---- 付款面板：默认隐藏；卡密已过期/需先付款时展开 ----
        payPanel = new LinearLayout(this);
        payPanel.setOrientation(LinearLayout.VERTICAL);
        payPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        payPanel.setBackground(roundDrawable(0xFFF7F6FE, dp(18), 0xFFE4E0F5));
        payPanel.setPadding(dp(18), dp(18), dp(18), dp(18));
        payPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams ppl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ppl.topMargin = dp(16);
        card.addView(payPanel, ppl);

        payTitle = new TextView(this);
        payTitle.setText("卡密已过期");
        payTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        payTitle.setTextColor(INK);
        payTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        payTitle.setGravity(Gravity.CENTER);
        payPanel.addView(payTitle);

        payAmount = new TextView(this);
        payAmount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        payAmount.setTextColor(ACCENT);
        payAmount.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        payAmount.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams pal = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pal.topMargin = dp(8);
        payPanel.addView(payAmount, pal);

        payHint = new TextView(this);
        payHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        payHint.setTextColor(GREY);
        payHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams phl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        phl.topMargin = dp(4);
        payPanel.addView(payHint, phl);

        payQr = new ImageView(this);
        payQr.setBackgroundColor(Color.WHITE);
        payQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        payQr.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(200), dp(200));
        qlp.topMargin = dp(12);
        payPanel.addView(payQr, qlp);

        payDone = new Button(this);
        payDone.setText("我已付款，重新验证");
        payDone.setAllCaps(false);
        payDone.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        payDone.setTextColor(Color.WHITE);
        payDone.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        payDone.setBackground(buttonDrawable());
        payDone.setStateListAnimator(null);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        blp2.topMargin = dp(14);
        payPanel.addView(payDone, blp2);

        payDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastKey == null || lastKey.isEmpty()) return;
                error.setText("");
                setBusy(true);
                final String oid = payOrderId;
                if (oid == null || oid.isEmpty()) {
                    // 静态收款码模式（后台没接支付网关，只放了个人收款码）：
                    // 先把「我已付款」上报给后台，卖家在后台「待放行」里看到后手动放行，
                    // 然后再重新验证一次——如果卖家已经放行，这一步就直接进去了。
                    final String k = lastKey;
                    sub.setText("正在通知卖家…");
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSONObject b = new JSONObject();
                                b.put("key", k);
                                b.put("note", "app");
                                post("/pay/claim", b);
                            } catch (Exception ignored) {
                            }
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    retryVerify();
                                }
                            });
                        }
                    }).start();
                    return;
                }
                // 先查这一单到底到没到账：回调丢了也能靠主动查单放行
                sub.setText("正在查询付款…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean paid = false;
                        try {
                            JSONObject body = new JSONObject();
                            body.put("order", oid);
                            JSONObject r = post("/pay/status", body);
                            paid = r != null && (r.optBoolean("paid") || r.optInt("state", 0) == 1);
                        } catch (Exception ignored) {
                        }
                        final boolean okPaid = paid;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (okPaid) {
                                    payOrderId = "";
                                    retryVerify();
                                } else {
                                    setBusy(false);
                                    sub.setText("还没查到付款，付完后稍等几秒再点一次");
                                }
                            }
                        });
                    }
                }).start();
            }
        });

        payReset = new TextView(this);
        payReset.setText("换个卡密");
        payReset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        payReset.setTextColor(GREY2);
        payReset.setGravity(Gravity.CENTER);
        payReset.setPadding(dp(10), dp(10), dp(10), dp(4));
        payPanel.addView(payReset);
        payReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hidePayPanel();
                error.setText("");
                input.requestFocus();
            }
        });

        // 底部设备码：点按复制，补卡/解绑时提供给发卡方
        TextView device = new TextView(this);
        final String deviceCode = DeviceCode.get(this);
        device.setText("设备码  " + deviceCode + "  ·  点按复制");
        device.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        device.setTextColor(GREY);
        device.setGravity(Gravity.CENTER);
        device.setPadding(dp(10), dp(8), dp(10), dp(8));
        device.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("device code", deviceCode));
                Toast.makeText(GateActivity.this, "设备码已复制", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(22);
        root.addView(device, dlp);

        // 付款面板展开后页面会变高：套一层滚动容器，二维码才不会被屏幕裁掉
        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    /** 付款面板展开后滚到底部，保证二维码完整可见 */
    private void scrollToPay() {
        if (scroller == null || payPanel == null) return;
        if (payPanel.getVisibility() != View.VISIBLE) return;
        // 等面板完成测量（含二维码图片）再滚，避免滚早了停在半截
        scroller.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 直接滚到底：二维码和「我已付款」按钮都在最下面
                scroller.fullScroll(View.FOCUS_DOWN);
            }
        }, 200);
    }

    /** 输入框：常态浅灰、聚焦白色 + 藏青描边 */
    private Drawable inputDrawable() {
        GradientDrawable normal = roundDrawable(INPUT_BG, dp(15), INPUT_BORDER);
        GradientDrawable focused = roundDrawable(Color.WHITE, dp(15), 0);
        focused.setStroke(dp(2), ACCENT);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** 按钮：藏青渐变，按下加深 */
    private Drawable buttonDrawable() {
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2});
        normal.setCornerRadius(dp(18));
        GradientDrawable pressed = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT_DARK, ACCENT});
        pressed.setCornerRadius(dp(18));
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private GradientDrawable roundDrawable(int color, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeColor != 0) {
            d.setStroke(dp(1), strokeColor);
        }
        return d;
    }

    private GradientDrawable circleDrawable(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private Bitmap circleCrop(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, new Rect(0, 0, size, size), new Rect(0, 0, size, size), p);
        return out;
    }

    private static String storedKey(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
    }

    /** 低层 POST：返回响应体原文；网络异常返回 null */
    private String httpPost(String path, String payload, String contentType, Map<String, String> headers) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            OutputStream os = conn.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject getJson(String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream is = conn.getInputStream();
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            return text.trim().isEmpty() ? null : new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 明文接口（旧协议，作为加密链路不可用时的兜底） */
    private JSONObject post(String path, JSONObject body) {
        String text = httpPost(path, body.toString(), "application/json; charset=utf-8", null);
        if (text == null || text.trim().isEmpty()) return null;
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 加密接口（GZ1/gzv1）：请求体与响应体都是 AES-GCM 密文。
     * 返回统一结构 {ok, err, need_pay, money, days, expire_at, pay_url}；
     * 加密链路不可用时返回 null，调用方自动回退明文接口。
     */
    private JSONObject securePost(String path, JSONObject body, String device) {
        String ts = String.valueOf(System.currentTimeMillis());
        try {
            String wire = GzProtocol.encrypt(body.toString(), device, ts, GzProtocol.APP_ID);
            Map<String, String> h = new HashMap<>();
            h.put("x-gz-app", GzProtocol.APP_ID);
            h.put("x-gz-device", device);
            h.put("x-gz-ts", ts);

            String text = httpPost(path, wire, "text/plain; charset=utf-8", h);
            if (text == null || text.trim().isEmpty()) return null;

            String trimmed = text.trim();
            String plain = trimmed.startsWith("GZ1.")
                    ? GzProtocol.decrypt(trimmed, device, ts, GzProtocol.APP_ID)
                    : trimmed;

            JSONObject raw = new JSONObject(plain);
            String msg = raw.optString("msg", "");
            // 服务端加密链路异常（解密失败 / 缺 GZ 头 / 接口不存在）→ 让调用方走明文
            if (raw.optInt("code", 0) == 400 && (msg.contains("解密") || msg.contains("GZ"))) return null;
            if ("not found".equals(msg)) return null;
            return normalizeResp(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把新协议 {code,msg,data} 归一成 UI 层一直用的旧结构 */
    private JSONObject normalizeResp(JSONObject raw) {
        if (raw == null || !raw.has("code")) return raw;
        JSONObject out = new JSONObject();
        try {
            int code = raw.optInt("code", 1);
            if (code == 0) {
                out.put("ok", true);
                JSONObject d = raw.optJSONObject("data");
                if (d != null) {
                    out.put("days", d.optInt("days", 0));
                    out.put("expire_at", d.optString("expire_at", ""));
                }
                return out;
            }
            out.put("ok", false);
            out.put("code", code);
            String msg = raw.optString("msg", "").trim();
            out.put("err", msg.isEmpty() ? "验证失败" : msg);

            JSONObject d = raw.optJSONObject("data");
            if (d != null && d.optBoolean("need_pay")) {
                out.put("need_pay", true);
                out.put("money", d.optString("money", ""));
                out.put("days", d.optInt("days", 0));
                out.put("pay_url", d.optString("pay_url", ""));
                out.put("expire_at", d.optString("expire_at", ""));
                out.put("mode", d.optString("mode", ""));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 先加密，链路不通再走明文 */
    private JSONObject callGate(String apiPath, String legacyPath, JSONObject body, String device) {
        JSONObject r = securePost(apiPath, body, device);
        if (r != null) return r;
        return post(legacyPath, body);
    }

    /**
     * 别人已激活的卡密：服务端回「该卡密已绑定其他设备」，不带付款信息，
     * 这里补查一次 /pay/info，按发卡方的引导策略展示「已过期 + 收款码」。
     * 免费卡（金额 0）不引导，保持原提示。
     */
    private JSONObject enrichBorrowed(JSONObject resp, String key) {
        if (resp == null || resp.optBoolean("ok") || resp.optBoolean("need_pay")) return resp;
        String err = resp.optString("err", "");
        if (!(err.contains("设备") || err.contains("绑定"))) return resp;
        JSONObject info = getJson("/pay/info?k=" + urlEnc(key));
        if (info == null || !info.optBoolean("ok")) return resp;
        String money = info.optString("money", "").trim();
        if (money.isEmpty() || "0".equals(money) || "0.00".equals(money)) return resp;
        try {
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("need_pay", true);
            out.put("err", "你的卡密已过期");
            out.put("money", money);
            out.put("days", info.optInt("buyDays", 0));
            out.put("pay_url", BASE_URL + "/pay?k=" + urlEnc(key));
            out.put("expire_at", "");
            out.put("mode", "renew");
            out.put("borrowed", true);
            return out;
        } catch (Exception e) {
            return resp;
        }
    }

    private static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 后台下发的 ISO 时间（UTC）转成本地「yyyy-MM-dd HH:mm」 */
    private static String fmtExpire(String iso) {
        if (iso == null) return "";
        String v = iso.trim();
        if (v.isEmpty()) return "";
        String[] pats = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSSZ"};
        for (String p : pats) {
            try {
                SimpleDateFormat in = new SimpleDateFormat(p, Locale.US);
                if (p.endsWith("'Z'")) in.setTimeZone(TimeZone.getTimeZone("UTC"));
                java.util.Date d = in.parse(v);
                if (d != null) return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(d);
            } catch (Exception ignored) {
            }
        }
        return v.length() >= 16 ? v.substring(0, 16) : v;
    }

    private void activateAsync(final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String device = DeviceCode.get(GateActivity.this);
                JSONObject body = new JSONObject();
                try {
                    body.put("key", key);
                    body.put("code", key);
                    body.put("device", device);
                    body.put("device_id", device);
                } catch (Exception ignored) {
                }
                JSONObject resp = callGate("/api/activate", "/activate", body, device);
                final JSONObject out = enrichBorrowed(resp, key);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onGateResult(out, true, key);
                    }
                });
            }
        }).start();
    }

    private void verifyAsync(final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String device = DeviceCode.get(GateActivity.this);
                JSONObject body = new JSONObject();
                try {
                    body.put("key", key);
                    body.put("code", key);
                    body.put("device", device);
                    body.put("device_id", device);
                } catch (Exception ignored) {
                }
                JSONObject resp = callGate("/api/verify", "/verify", body, device);
                final JSONObject out = enrichBorrowed(resp, key);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onGateResult(out, false, key);
                    }
                });
            }
        }).start();
    }

    /** 验证/激活统一结果处理 */
    private void onGateResult(JSONObject resp, boolean isActivate, String key) {
        setBusy(false);
        sub.setText("输入卡密以继续使用");
        if (resp == null) {
            error.setText("网络连接失败，请检查网络");
            return;
        }
        if (resp.optBoolean("ok")) {
            hidePayPanel();
            if (isActivate) {
                if (remember.isChecked()) {
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY, key).apply();
                } else {
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().remove(KEY).apply();
                }
            }
            openManager();
            return;
        }
        handleGateResp(resp);
    }

    /** 重新提交验证：新卡密走 activate，已保存的卡密走 verify */
    private void retryVerify() {
        if (lastBorrowed) {
            Toast.makeText(GateActivity.this,
                    "付款后请把设备码发给卖家开通，再点此验证", Toast.LENGTH_LONG).show();
        }
        error.setText("");
        sub.setText("正在确认付款…");
        setBusy(true);
        // 新卡密付款后要重新 activate（首次付款：付款前被拒、到账后放行）；
        // 已保存卡密续费则走 verify（到账后自动延期）
        if (lastOpIsActivate) {
            activateAsync(lastKey);
        } else {
            verifyAsync(lastKey);
        }
    }

    /** 服务器返回的统一处理：need_pay -> 弹付款码；否则显示普通错误 */
    private void handleGateResp(JSONObject resp) {
        if (resp == null) {
            error.setText("网络连接失败，请检查网络");
            return;
        }
        if (resp.optBoolean("need_pay")) {
            lastBorrowed = resp.optBoolean("borrowed");
            showPayPanel(resp);
            return;
        }

        hidePayPanel();
        error.setText(resp.optString("err", "激活失败"));
    }

    /** 展开付款面板：金额与收款码全部来自后台 */
    private void showPayPanel(JSONObject resp) {
        String money = resp.optString("money", "").trim();
        int days = resp.optInt("days", 0);
        String expireAt = fmtExpire(resp.optString("expire_at", ""));
        String payUrl = resp.optString("pay_url", "").trim();

        String why = resp.optString("err", "").trim();
        // 已经点过「我已付款」：改成等待卖家确认的文案，免得客户以为没反应又付一次
        boolean claimed = resp.optBoolean("claimed");
        // 主文案以后台下发为准（后台可自定义，比如别人已激活的卡密写成「你的卡密已过期」），
        // 没给就用默认文案
        if (claimed) {
            sub.setText("已提交付款，等待卖家确认");
        } else {
            sub.setText(why.isEmpty() ? "你的卡密已过期" : why);
        }
        error.setText("");
        payTitle.setText(claimed ? "等待卖家确认" : "扫码付款续期");

        // 金额由后台自定义，原样展示；没填就只显示收款码
        payAmount.setText(money.isEmpty() ? "" : ("¥ " + money));
        payAmount.setVisibility(money.isEmpty() ? View.GONE : View.VISIBLE);

        StringBuilder hint = new StringBuilder(claimed ? "已通知卖家核对到账" : "扫码付款后自动续期");
        if (days > 0) hint.append(" · 续 ").append(days).append(" 天");
        if (!expireAt.isEmpty()) hint.append("\n到期时间 ").append(expireAt);
        if (lastBorrowed) hint.append("\n付款后把设备码发给卖家开通");
        else if (claimed) hint.append("\n卖家确认后点下方按钮即可进入");
        else hint.append("\n付款后点下方按钮确认");
        payHint.setText(hint.toString());

        if (payUrl.isEmpty()) {
            payQr.setVisibility(View.GONE);
            payHint.setText("后台未配置收款码，请联系发卡方");
        } else {
            showPayCode(payUrl);
            // 后台若接了支付网关，这里直接下单拿原生微信收款码（扫码即付）
            if (!lastKey.equals(payOrderKey)) {
                payOrderKey = lastKey;
                loadNativePayCode(payUrl);
            }
        }
        payPanel.setVisibility(View.VISIBLE);
        scrollToPay();
    }

    /**
     * 向后台下单，取支付网关返回的原始收款码：
     * 微信 Native 下单会给 weixin://wxpay/bizpayurl?pr=xxx，本地编码成二维码，扫码直接拉起付款；
     * 网关没配置/下单失败则保留 pay_url 的收款码（固定收款码图片或支付页链接）。
     */
    private void loadNativePayCode(final String fallback) {
        if (lastKey == null || lastKey.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String code = null;
                String money = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("key", lastKey);
                    JSONObject r = post("/pay/create", body);
                    if (r != null && r.optBoolean("ok")) {
                        String qc = r.optString("qrcode", "").trim();
                        String pu = r.optString("payurl", "").trim();
                        code = !qc.isEmpty() ? qc : pu;
                        money = r.optString("money", "").trim();
                        payOrderId = r.optString("order", "").trim();
                    }
                } catch (Exception ignored) {
                }
                final String fCode = code;
                final String fMoney = money;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (payPanel.getVisibility() != View.VISIBLE) return;
                        if (fMoney != null && !fMoney.isEmpty()) {
                            payAmount.setText("¥ " + fMoney);
                            payAmount.setVisibility(View.VISIBLE);
                        }
                        if (fCode != null && !fCode.isEmpty()) {
                            payHint.setText("微信「扫一扫 → 相册」选这张码付款\n到账后点下方按钮");
                            showPayCode(fCode);
                            scrollToPay();
                        }
                    }
                });
            }
        }).start();
    }

    private void hidePayPanel() {
        if (payPanel != null) payPanel.setVisibility(View.GONE);
        payOrderKey = "";
        payOrderId = "";
    }

    /**
     * 展示收款码。pay_url 后台可配两种形态：
     *   1) 图片直链（.png/.jpg/.jpeg/.webp）—— 直接下载显示，适合后台上传固定收款码
     *   2) 支付跳转链接 / 微信支付宝支付串 —— 本地编码成二维码，扫码即付
     */
    private void showPayCode(final String payUrl) {
        String lower = payUrl.toLowerCase(Locale.ROOT);
        boolean isImage = lower.matches(".*\\.(png|jpe?g|webp|gif)(\\?.*)?$");
        if (!isImage) {
            Bitmap qr = qrBitmap(payUrl, dp(200));
            if (qr != null) {
                payQr.setVisibility(View.VISIBLE);
                payQr.setImageBitmap(qr);
            } else {
                payQr.setVisibility(View.GONE);
                payHint.setText("收款码生成失败，链接：" + payUrl);
            }
            return;
        }
        // 图片直链：后台下载，避免主线程网络
        payQr.setVisibility(View.VISIBLE);
        payQr.setImageDrawable(null);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(payUrl).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    InputStream is = conn.getInputStream();
                    bmp = BitmapFactory.decodeStream(is);
                    is.close();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final Bitmap out = bmp;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (out != null) {
                            payQr.setImageBitmap(out);
                        } else {
                            payQr.setVisibility(View.GONE);
                            payHint.setText("收款码下载失败，请检查网络后重试");
                        }
                    }
                });
            }
        }).start();
    }

    /** 生成收款二维码 */
    private Bitmap qrBitmap(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    bmp.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private void setBusy(boolean busy) {
        submit.setEnabled(!busy);
        submit.setAlpha(busy ? 0.55f : 1f);
        submit.setText(busy ? "验证中…" : "验证");
    }

    private void openManager() {
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), TARGET);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        } catch (Exception e) {
            error.setText("启动失败: " + e.getMessage());
        }
    }
}
