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
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

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

        return root;
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

    private JSONObject post(String path, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            if (text.trim().isEmpty()) return null;
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void activateAsync(final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject body = new JSONObject();
                try {
                    body.put("key", key);
                    body.put("device", DeviceCode.get(GateActivity.this));
                } catch (Exception ignored) {
                }
                final JSONObject resp = post("/activate", body);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        sub.setText("输入卡密以继续使用");
                        if (resp == null) {
                            error.setText("网络连接失败，请检查网络");
                            return;
                        }
                        if (resp.optBoolean("ok")) {
                            hidePayPanel();
                            if (remember.isChecked()) {
                                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                        .edit().putString(KEY, key).apply();
                            } else {
                                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                        .edit().remove(KEY).apply();
                            }
                            openManager();
                        } else {
                            handleGateResp(resp);
                        }
                    }
                });
            }
        }).start();
    }

    private void verifyAsync(final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject body = new JSONObject();
                try {
                    body.put("key", key);
                    body.put("device", DeviceCode.get(GateActivity.this));
                } catch (Exception ignored) {
                }
                final JSONObject resp = post("/verify", body);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (resp == null) {
                            sub.setText("输入卡密以继续使用");
                            error.setText("网络连接失败，请检查网络");
                            return;
                        }
                        if (resp.optBoolean("ok")) {
                            hidePayPanel();
                            openManager();
                        } else {
                            sub.setText("输入卡密以继续使用");
                            handleGateResp(resp);
                        }
                    }
                });
            }
        }).start();
    }

    /** 服务器返回的统一处理：need_pay -> 弹付款码；否则显示普通错误 */
    private void handleGateResp(JSONObject resp) {
        if (resp == null) {
            error.setText("网络连接失败，请检查网络");
            return;
        }
        if (resp.optBoolean("need_pay")) {
            String money = resp.optString("money", "").trim();
            int days = resp.optInt("days", 0);
            String expireAt = resp.optString("expire_at", "").trim();
            String payUrl = resp.optString("pay_url", "").trim();

            String why = resp.optString("err", "").trim();
            // 主文案以后台下发为准（后台可自定义，比如别人已激活的卡密写成「你的卡密已过期」），
            // 没给就用默认文案
            sub.setText(why.isEmpty() ? "你的卡密已过期" : why);
            error.setText("");
            payTitle.setText("扫码付款续期");

            // 金额由后台自定义，原样展示；没填就只显示收款码
            payAmount.setText(money.isEmpty() ? "" : ("¥ " + money));
            payAmount.setVisibility(money.isEmpty() ? View.GONE : View.VISIBLE);

            StringBuilder hint = new StringBuilder("扫码付款后自动续期");
            if (days > 0) hint.append(" · 续 ").append(days).append(" 天");
            if (!expireAt.isEmpty()) hint.append("\n到期时间 ").append(expireAt);
            hint.append("\n付款后点下方按钮确认");
            payHint.setText(hint.toString());

            if (payUrl.isEmpty()) {
                payQr.setVisibility(View.GONE);
                payHint.setText("后台未配置收款码，请联系发卡方");
            } else {
                showPayCode(payUrl);
            }
            payPanel.setVisibility(View.VISIBLE);
            return;
        }

        hidePayPanel();
        error.setText(resp.optString("err", "激活失败"));
    }

    private void hidePayPanel() {
        if (payPanel != null) payPanel.setVisibility(View.GONE);
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
