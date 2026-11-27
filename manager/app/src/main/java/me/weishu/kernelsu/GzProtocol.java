package me.weishu.kernelsu;

import android.util.Base64;

import java.nio.charset.Charset;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * GZ1 / gzv1 加密协议（与服务端 sbkey 一致）。
 *
 * <pre>
 * sessionKey = HMAC-SHA256( HMAC-SHA256("gzv1|app|device", DATA_KEY), "gz-payload" + 0x01 )
 * 请求体      = "GZ1." + b64url( iv[12] || tag[16] || ciphertext )
 *              AAD = "gzv1|app|device|ts"
 * 响应体      = "GZ1." + b64url( iv[12] || tag[16] || ciphertext )
 *              AAD = "gzv1|resp|ts"
 * 请求头      = x-gz-app / x-gz-device / x-gz-ts
 * </pre>
 *
 * 注意：任何改动（密钥、label、AAD 拼法、tag 位置）都会导致服务端解密失败，
 * 已用线上接口双向验证过，改前务必重跑验证脚本。
 */
final class GzProtocol {

    static final String APP_ID = "AK202F827A9C7BAA5A";
    private static final String DATA_KEY = "44adc3b2deeb8915668331e3fa1dd4fc";
    private static final String LABEL = "gz-payload";

    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 16;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private GzProtocol() {
    }

    static byte[] sessionKey(String device, String appId) throws Exception {
        byte[] seed = hmac(("gzv1|" + appId + "|" + device).getBytes(UTF8), DATA_KEY.getBytes(UTF8));
        byte[] label = LABEL.getBytes(UTF8);
        byte[] msg = new byte[label.length + 1];
        System.arraycopy(label, 0, msg, 0, label.length);
        msg[label.length] = 0x01;
        return hmac(seed, msg);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** 加密请求体，返回可以直接作为 body 发送的 "GZ1.xxx" */
    static String encrypt(String plain, String device, String ts, String appId) throws Exception {
        byte[] key = sessionKey(device, appId);
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN * 8, iv));
        c.updateAAD(("gzv1|" + appId + "|" + device + "|" + ts).getBytes(UTF8));
        byte[] out = c.doFinal(plain.getBytes(UTF8)); // ct || tag

        int ctLen = out.length - TAG_LEN;
        byte[] blob = new byte[IV_LEN + TAG_LEN + ctLen];
        System.arraycopy(iv, 0, blob, 0, IV_LEN);
        System.arraycopy(out, ctLen, blob, IV_LEN, TAG_LEN);            // tag
        System.arraycopy(out, 0, blob, IV_LEN + TAG_LEN, ctLen);        // ciphertext
        return "GZ1." + b64u(blob);
    }

    /** 解密响应体（传 "GZ1.xxx" 或裸 b64url 都行） */
    static String decrypt(String wire, String device, String ts, String appId) throws Exception {
        String body = wire.trim();
        if (body.startsWith("GZ1.")) body = body.substring(4);

        byte[] raw = unb64u(body);
        if (raw.length < IV_LEN + TAG_LEN) throw new IllegalArgumentException("cipher too short");

        byte[] iv = new byte[IV_LEN];
        System.arraycopy(raw, 0, iv, 0, IV_LEN);

        int ctLen = raw.length - IV_LEN - TAG_LEN;
        byte[] data = new byte[ctLen + TAG_LEN];
        System.arraycopy(raw, IV_LEN + TAG_LEN, data, 0, ctLen);        // ciphertext
        System.arraycopy(raw, IV_LEN, data, ctLen, TAG_LEN);            // tag

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sessionKey(device, appId), "AES"),
                new GCMParameterSpec(TAG_LEN * 8, iv));
        c.updateAAD(("gzv1|resp|" + ts).getBytes(UTF8));
        return new String(c.doFinal(data), UTF8).trim();
    }

    private static String b64u(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] unb64u(String s) {
        StringBuilder sb = new StringBuilder(s.replace('-', '+').replace('_', '/'));
        while (sb.length() % 4 != 0) sb.append('=');
        return Base64.decode(sb.toString(), Base64.NO_WRAP);
    }
}
