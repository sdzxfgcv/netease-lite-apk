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
 * 网易云音乐三端接口封装 + 自动择优。
 *
 * 五种客户端接口（五种钥匙）：
 *   网页版 weapi    -> https://music.163.com/weapi/...
 *   手机端 eapi     -> eapi，os=android
 *   电脑端 linuxapi -> https://music.163.com/api/linux/forward
 *   macOS 客户端    -> eapi，os=osx
 *   Windows 客户端  -> eapi，os=pc
 *
 * 自动择优策略：
 *   1) 启动时 probe() 探测全部接口响应速度，选出搜索/榜单/歌词的最优接口；
 *   2) 播放地址优先手机端 eapi（实测覆盖最广），每次播放依次尝试直到拿到地址；
 *   3) 每次请求失败自动降级到下一个接口，成功则记住，下次直接用；
 *   4) 四类接口各自独立记分，互不干扰。
 *
 * 仅调用公开接口获取公开数据，不破解版权、不绕过 VIP/DRM。
 */
public final class Api {

    public static final int MODE_WEB = 0;
    public static final int MODE_MOBILE = 1;
    public static final int MODE_PC = 2;
    public static final int MODE_MAC = 3;
    public static final int MODE_WIN = 4;

    private static final int KIND_SEARCH = 0;
    private static final int KIND_TOP = 1;
    private static final int KIND_PLAY = 2;
    private static final int KIND_LYRIC = 3;

    /** 每种接口当前最优模式：搜索/榜单/播放/歌词 */
    private static final int[] bestMode = {
            MODE_WEB, MODE_WEB, MODE_MOBILE, MODE_WEB
    };
    /** 每种接口在每个模式上的连续失败次数，越小越优先 */
    private static final int[][] failCount = new int[4][5];

    private static volatile boolean auto = true;
    private static volatile int fixedMode = MODE_WEB;

    // ---------------- 模式控制 ----------------

    public static void setAuto(boolean value) {
        auto = value;
    }

    public static boolean isAuto() {
        return auto;
    }

    public static void setFixedMode(int m) {
        fixedMode = normalize(m);
        auto = false;
    }

    public static int getFixedMode() {
        return fixedMode;
    }

    /** 兼容旧调用 */
    public static void setMode(int m) {
        setFixedMode(m);
    }

    public static int getMode() {
        return auto ? bestMode[KIND_SEARCH] : fixedMode;
    }

    private static int normalize(int m) {
        return (m >= MODE_WEB && m <= MODE_WIN) ? m : MODE_WEB;
    }

    public static String modeName(int m) {
        switch (m) {
            case MODE_MOBILE:
                return "手机端";
            case MODE_PC:
                return "电脑端";
            case MODE_MAC:
                return "macOS";
            case MODE_WIN:
                return "Windows";
            default:
                return "网页版";
        }
    }

    /** 兼容旧调用 */
    public static String modeName() {
        return modeName(getMode());
    }

    /** 界面标签：自动时显示「搜索接口/播放接口」，手动时显示指定接口 */
    public static String currentLabel() {
        if (!auto) return "手动·" + modeName(fixedMode);
        return "自动·" + shortName(bestMode[KIND_SEARCH]) + "/" + shortName(bestMode[KIND_PLAY]);
    }

    private static String shortName(int m) {
        switch (m) {
            case MODE_MOBILE:
                return "手机";
            case MODE_PC:
                return "电脑";
            case MODE_MAC:
                return "Mac";
            case MODE_WIN:
                return "Win";
            default:
                return "网页";
        }
    }

    /** eapi 各客户端变体的 os 参数 */
    private static String osName(int m) {
        switch (m) {
            case MODE_MAC:
                return "osx";
            case MODE_WIN:
                return "pc";
            default:
                return "android";
        }
    }

    /** 当前自动选中的搜索接口，供持久化保存 */
    public static int bestSearchMode() {
        return bestMode[KIND_SEARCH];
    }

    public static void setBestSearchMode(int m) {
        bestMode[KIND_SEARCH] = normalize(m);
    }

    // ---------------- 择优排序 ----------------

    /** 首选当前最优，其余按失败次数升序排列 */
    private static int[] orderFor(int kind) {
        int first = auto ? bestMode[kind] : fixedMode;
        int[] rest = new int[4];
        int n = 0;
        for (int m = 0; m <= MODE_WIN; m++) {
            if (m != first) rest[n++] = m;
        }
        for (int i = 1; i < n; i++) {
            int key = rest[i];
            int j = i - 1;
            while (j >= 0 && failCount[kind][rest[j]] > failCount[kind][key]) {
                rest[j + 1] = rest[j];
                j--;
            }
            rest[j + 1] = key;
        }
        int[] out = new int[n + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, n);
        return out;
    }

    private static void noteSuccess(int kind, int m) {
        if (auto) bestMode[kind] = m;
        failCount[kind][m] = 0;
    }

    private static void noteFail(int kind, int m) {
        if (failCount[kind][m] < 100) failCount[kind][m]++;
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
    private static final String UA_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA_WIN =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

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

    private static String eapiBody(String uri, JSONObject data, String osName) throws Exception {
        JSONObject header = new JSONObject();
        long now = System.currentTimeMillis();
        header.put("osver", "16.0.0");
        header.put("deviceId", "");
        header.put("os", osName);
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
        String ua;
        switch (m) {
            case MODE_PC:
                ua = UA_PC;
                break;
            case MODE_MAC:
                ua = UA_MAC;
                break;
            case MODE_WIN:
                ua = UA_WIN;
                break;
            case MODE_MOBILE:
                ua = UA_MOBILE;
                break;
            default:
                ua = UA_WEB;
        }
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Referer", "https://music.163.com/");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        if (m == MODE_WEB || m == MODE_PC) {
            conn.setRequestProperty("Cookie", "__csrf=;");
        } else {
            conn.setRequestProperty("Cookie",
                    "os=" + osName(m) + "; osver=16.0.0; appver=9.1.65; channel=");
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

    private static String rawRequest(int m, String uri, JSONObject data) throws Exception {
        String url, body;
        if (m == MODE_WEB) {
            url = "https://music.163.com/weapi/" + uri.substring(5) + "?csrf_token=";
            body = weapiBody(new JSONObject(data.toString()));
        } else if (m == MODE_MOBILE || m == MODE_MAC || m == MODE_WIN) {
            url = "https://interfacepc.music.163.com/eapi/" + uri.substring(5);
            body = eapiBody(uri, new JSONObject(data.toString()), osName(m));
        } else {
            url = "https://music.163.com/api/linux/forward";
            body = linuxapiBody(uri, new JSONObject(data.toString()));
        }
        return post(url, body, m);
    }

    /** 按当前最优顺序请求，失败自动换下一条路，成功即记住 */
    private static JSONObject request(int kind, String uri, JSONObject data) throws Exception {
        Exception last = null;
        for (int m : orderFor(kind)) {
            try {
                JSONObject root = new JSONObject(rawRequest(m, uri, data));
                if (root.has("code")) {
                    int c = root.optInt("code");
                    if (c != 200 && c != 201) {
                        throw new IOException("code=" + c
                                + (root.has("message") ? " " + root.optString("message") : ""));
                    }
                }
                noteSuccess(kind, m);
                return root;
            } catch (Exception e) {
                noteFail(kind, m);
                last = e;
            }
        }
        throw (last == null ? new IOException("请求失败") : last);
    }

    // ---------------- 启动探测 ----------------

    /** 后台调用：探测三端响应速度，选出搜索/榜单/歌词的最优接口 */
    public static void probe() {
        JSONObject data = new JSONObject();
        try {
            data.put("id", 186016);
            data.put("lv", -1);
            data.put("kv", -1);
            data.put("tv", -1);
            data.put("rv", -1);
            data.put("_nmclfl", 1);
        } catch (Exception ignored) {
            return;
        }

        long[] cost = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        for (int m = 0; m <= MODE_WIN; m++) {
            long t = System.currentTimeMillis();
            try {
                new JSONObject(rawRequest(m, "/api/song/lyric", data));
                cost[m] = System.currentTimeMillis() - t;
            } catch (Exception ignored) {
            }
        }
        int best = 0;
        for (int m = 1; m <= MODE_WIN; m++) {
            if (cost[m] < cost[best]) best = m;
        }
        if (cost[best] != Long.MAX_VALUE) {
            bestMode[KIND_SEARCH] = best;
            bestMode[KIND_TOP] = best;
            bestMode[KIND_LYRIC] = best;
        }
    }

    // ---------------- 业务接口 ----------------

    public static List<Song> search(String keyword, int limit) throws Exception {
        JSONObject data = new JSONObject();
        data.put("s", keyword);
        data.put("type", 1);
        data.put("limit", limit);
        data.put("offset", 0);
        data.put("total", true);
        JSONObject root = request(KIND_SEARCH, "/api/cloudsearch/pc", data);
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
        JSONObject root = request(KIND_TOP, "/api/v6/playlist/detail", data);
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

    /** 依次尝试各模式，返回第一个可用的播放地址；全部拿不到则返回 null。 */
    public static String playUrl(String songId) throws Exception {
        JSONObject data = new JSONObject();
        data.put("ids", "[" + songId + "]");
        data.put("level", "standard");
        data.put("encodeType", "aac");
        Exception last = null;
        for (int m : orderFor(KIND_PLAY)) {
            try {
                JSONObject root = new JSONObject(rawRequest(m, "/api/song/enhance/player/url/v1", data));
                JSONArray arr = root.optJSONArray("data");
                if (arr != null && arr.length() > 0) {
                    JSONObject first = arr.optJSONObject(0);
                    if (first != null) {
                        String url = first.optString("url", "");
                        if (first.optInt("code", -1) == 200
                                && url.length() > 0 && !"null".equals(url)) {
                            noteSuccess(KIND_PLAY, m);
                            return url;
                        }
                    }
                }
                noteFail(KIND_PLAY, m);
            } catch (Exception e) {
                noteFail(KIND_PLAY, m);
                last = e;
            }
        }
        if (last != null && last instanceof IOException) throw last;
        return null;
    }

    public static String lyric(String songId) throws Exception {
        JSONObject data = new JSONObject();
        data.put("id", Long.parseLong(songId));
        data.put("lv", -1);
        data.put("kv", -1);
        data.put("tv", -1);
        data.put("rv", -1);
        data.put("_nmclfl", 1);
        JSONObject root = request(KIND_LYRIC, "/api/song/lyric", data);
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
