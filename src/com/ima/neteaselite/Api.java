package com.ima.neteaselite;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云音乐三端接口封装：
 *   网页版 weapi  -> https://music.163.com/weapi/...
 *   手机端 eapi   -> https://interfacepc.music.163.com/eapi/...
 *   电脑端 linuxapi -> https://music.163.com/api/linux/forward
 * 仅调用公开接口获取公开数据，不破解版权、不绕过 VIP/DRM。
 */
public final class Api {

    public static final int MODE_WEB = 0;
    public static final int MODE_MOBILE = 1;
    public static final int MODE_PC = 2;

    private static volatile int mode = MODE_WEB;

    public static void setMode(int m) {
        mode = (m == MODE_MOBILE || m == MODE_PC) ? m : MODE_WEB;
    }

    public static int getMode() {
        return mode;
    }

    public static String modeName() {
        switch (mode) {
            case MODE_MOBILE:
                return "手机端";
            case MODE_PC:
                return "电脑端";
            default:
                return "网页版";
        }
    }

    // ---------------- 加密常量 ----------------

    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String PUB_KEY = "010001";
    private static final String MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final String BASE62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String LINUX_KEY = "rFgB&h#%2?^eDg:Q";
    private static final String SPLIT = "36cd479b6b5";

    private static final String UA_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final String UA_PC = "NeteaseMusic/7.2.24.0(200901015511)";

    private Api() {
    }

    // ---------------- 加密实现 ----------------

    private static byte[] aes(String text, String key, boolean cbc) throws Exception {
        Cipher c = cbc
                ? Cipher.getInstance("AES/CBC/PKCS5Padding")
                : Cipher.getInstance("AES/ECB/PKCS5Padding");
        SecretKeySpec sk = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        if (cbc) {
            c.init(Cipher.ENCRYPT_MODE, sk, new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        } else {
            c.init(Cipher.ENCRYPT_MODE, sk);
        }
        return c.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String aesHex(String text, String key) throws Exception {
        return hex(aes(text, key, false));
    }

    private static String randomKey() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) sb.append(BASE62.charAt(r.nextInt(BASE62.length())));
        return sb.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    /** 网页版 asrsea */
    private static String weapiBody(JSONObject data) throws Exception {
        data.put("csrf_token", "");
        String text = data.toString();
        String secKey = randomKey();
        String p = Base64.getEncoder().encodeToString(aes(text, PRESET_KEY, true));
        p = Base64.getEncoder().encodeToString(aes(p, secKey, true));
        String rev = new StringBuilder(secKey).reverse().toString();
        BigInteger v = new BigInteger(hex(rev.getBytes(StandardCharsets.US_ASCII)), 16);
        BigInteger r = v.modPow(new BigInteger(PUB_KEY, 16), new BigInteger(MODULUS, 16));
        String encSecKey = r.toString(16);
        while (encSecKey.length() < 256) encSecKey = "0" + encSecKey;
        return "params=" + URLEncoder.encode(p, "UTF-8")
                + "&encSecKey=" + URLEncoder.encode(encSecKey, "UTF-8");
    }

    /** 手机端 eapi */
    private static String eapiBody(String uri, JSONObject data) throws Exception {
        JSONObject header = new JSONObject();
        long now = System.currentTimeMillis();
        header.put("osver", "16.0.0");
        header.put("deviceId", "");
        header.put("os", "android");
        header.put("appver", "9.1.65");
        header.put("versioncode", "140");
        header.put("mobilename", "");
        header.put("buildver", Long.toString(now).substring(0, 10));
        header.put("resolution", "1920x1080");
        header.put("__csrf", "");
        header.put("channel", "");
        header.put("requestId", now + "_" + (1000 + new SecureRandom().nextInt(9000)));
        data.put("header", header);
        String text = data.toString();
        String msg = "nobody" + uri + "use" + text + "md5forencrypt";
        String digest = hex(MessageDigest.getInstance("MD5").digest(msg.getBytes(StandardCharsets.UTF_8)));
        String raw = uri + "-" + SPLIT + "-" + text + "-" + SPLIT + "-" + digest;
        return "params=" + URLEncoder.encode(aesHex(raw, EAPI_KEY), "UTF-8");
    }

    /** 电脑端 linuxapi */
    private static String linuxapiBody(String uri, JSONObject data) throws Exception {
        JSONObject wrap = new JSONObject();
        wrap.put("method", "POST");
        wrap.put("url", "https://music.163.com" + uri);
        wrap.put("params", data);
        return "eparams=" + URLEncoder.encode(aesHex(wrap.toString(), LINUX_KEY), "UTF-8");
    }

    // ---------------- HTTP ----------------

    private static String post(String url, String body, int m) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(18000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
                m == MODE_MOBILE ? UA_MOBILE : (m == MODE_PC ? UA_PC : UA_WEB));
        conn.setRequestProperty("Referer", "https://music.163.com/");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        if (m == MODE_MOBILE) {
            conn.setRequestProperty("Cookie", "os=android; osver=16.0.0; appver=9.1.65; channel=");
        } else {
            conn.setRequestProperty("Cookie", "__csrf=;");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) throw new IOException("HTTP " + code);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        conn.disconnect();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 按当前接口模式请求；失败时依次尝试另外两种模式。
     */
    private static JSONObject request(String uri, JSONObject data) throws Exception {
        Exception last = null;
        for (int k = 0; k < 3; k++) {
            int m = (mode + k) % 3;
            try {
                String url, body;
                if (m == MODE_WEB) {
                    url = "https://music.163.com/weapi/" + uri.substring(5) + "?csrf_token=";
                    body = weapiBody(new JSONObject(data.toString()));
                } else if (m == MODE_MOBILE) {
                    url = "https://interfacepc.music.163.com/eapi/" + uri.substring(5);
                    body = eapiBody(uri, new JSONObject(data.toString()));
                } else {
                    url = "https://music.163.com/api/linux/forward";
                    body = linuxapiBody(uri, new JSONObject(data.toString()));
                }
                String raw = post(url, body, m);
                JSONObject root = new JSONObject(raw);
                if (root.has("code")) {
                    int c = root.optInt("code");
                    if (c != 200 && c != 201) {
                        last = new IOException("code=" + c
                                + (root.has("message") ? " " + root.optString("message") : ""));
                        continue;
                    }
                }
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        throw (last == null ? new IOException("请求失败") : last);
    }

    // ---------------- 业务接口 ----------------

    public static List<Song> search(String keyword, int limit) throws Exception {
        JSONObject data = new JSONObject();
        data.put("s", keyword);
        data.put("type", 1);
        data.put("limit", limit);
        data.put("offset", 0);
        data.put("total", true);
        JSONObject root = request("/api/cloudsearch/pc", data);
        List<Song> out = new ArrayList<>();
        JSONObject result = root.optJSONObject("result");
        if (result == null) return out;
        JSONArray arr = result.optJSONArray("songs");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(parse(o));
        }
        return out;
    }

    public static List<Song> topList(int topId, int num) throws Exception {
        JSONObject data = new JSONObject();
        data.put("id", topId);
        data.put("n", num);
        data.put("s", 8);
        JSONObject root = request("/api/v6/playlist/detail", data);
        List<Song> out = new ArrayList<>();
        JSONObject pl = root.optJSONObject("playlist");
        if (pl == null) return out;
        JSONArray arr = pl.optJSONArray("tracks");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(parse(o));
        }
        return out;
    }

    /** 返回可播放地址；无版权/VIP 歌曲返回 null（不做任何解锁）。 */
    public static String playUrl(String songId) throws Exception {
        JSONObject data = new JSONObject();
        data.put("ids", "[" + songId + "]");
        data.put("level", "standard");
        data.put("encodeType", "aac");
        JSONObject root = request("/api/song/enhance/player/url/v1", data);
        JSONArray arr = root.optJSONArray("data");
        if (arr == null || arr.length() == 0) return null;
        JSONObject first = arr.optJSONObject(0);
        if (first == null) return null;
        String url = first.optString("url", "");
        int c = first.optInt("code", -1);
        return (c == 200 && url.length() > 0 && !"null".equals(url)) ? url : null;
    }

    public static String lyric(String songId) throws Exception {
        JSONObject data = new JSONObject();
        data.put("id", Long.parseLong(songId));
        data.put("lv", -1);
        data.put("kv", -1);
        data.put("tv", -1);
        data.put("rv", -1);
        data.put("_nmclfl", 1);
        JSONObject root = request("/api/song/lyric", data);
        JSONObject lrc = root.optJSONObject("lrc");
        return lrc == null ? null : lrc.optString("lyric", "");
    }

    public static String coverUrl(String picUrl) {
        if (picUrl == null || picUrl.length() == 0) return null;
        return picUrl.replaceFirst("^http://", "https://");
    }

    private static Song parse(JSONObject o) {
        Song s = new Song();
        s.mid = String.valueOf(o.optLong("id", 0));
        s.name = firstNonEmpty(o.optString("name"), o.optString("title"));
        JSONArray ar = o.optJSONArray("ar");
        if (ar == null) ar = o.optJSONArray("artists");
        if (ar != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ar.length(); i++) {
                JSONObject a = ar.optJSONObject(i);
                if (a == null) continue;
                String nm = a.optString("name");
                if (nm.length() == 0) continue;
                if (sb.length() > 0) sb.append(" / ");
                sb.append(nm);
            }
            s.singer = sb.toString();
        }
        JSONObject al = o.optJSONObject("al");
        if (al == null) al = o.optJSONObject("album");
        if (al != null) s.albumMid = al.optString("picUrl", "");
        s.interval = o.optInt("dt", 0) / 1000;
        return s;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && v.length() > 0) return v;
        return "";
    }
}
