package com.muse.xdownloader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析工具类：
 *  1. 快速通道：把 FxTwitter 公开接口的响应体（JSON）解析成媒体列表（v1.0.2 起，只下公开推文、速度快；
 *     拿不到时由 MainActivity 自动转到已登录 WebView 路线兜底）。
 *  2. 把 WebView 拦截到的、页面自己发出的 GraphQL 响应体（JSON）解析成媒体列表；
 *     queryId 在拦截到的 URL 里，由页面决定，本类不出现任何写死的 queryId。
 *  3. 图片原图/小图地址转换等小工具。
 * DOM 收集、GraphQL 拦截都在 MainActivity 的已登录 WebView 里完成。
 */
public class TweetParser {

    static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    // 页面 HTML 文本里找直链用的正则（找 mp4 / pbs 图）
    static final Pattern MP4_PATTERN = Pattern.compile("https://video\\.twimg\\.com/[^\\s\"'\\\\<>]+?\\.mp4(?:\\?[^\\s\"'\\\\<>]*)?");
    static final Pattern PBS_PATTERN = Pattern.compile("https://pbs\\.twimg\\.com/media/[^\\s\"'\\\\<>]+");

    /** 从一段 HTML/脚本文字里用正则提取媒体，返回去重后的列表 */
    public static void extractFromHtmlText(String html, LinkedHashMap<String, TweetInfo.Media> out) {
        if (html == null || html.isEmpty()) return;
        // 先解开 JSON 里常见的转义斜杠
        String text = html.replace("\\/", "/");
        // 先抓 variants 里码率最大的那个（同一个视频会有多个清晰度的 mp4，只留下最好的）
        String bestVariant = extractVariants(text, out);
        Matcher mv = MP4_PATTERN.matcher(text);
        while (mv.find()) {
            String u = cleanUrl(mv.group());
            // 已经有 variants 的最佳地址时，跳过同一个视频的其它清晰度地址，避免重复下载
            if (bestVariant != null && sameVideoId(u, bestVariant)) continue;
            if (!out.containsKey(u)) out.put(u, new TweetInfo.Media(u, "video"));
        }
        Matcher mi = PBS_PATTERN.matcher(text);
        while (mi.find()) {
            String raw = cleanUrl(mi.group());
            String u = toOrigUrl(raw);
            if (!out.containsKey(u)) out.put(u, new TweetInfo.Media(u, "photo", toThumbUrl(raw)));
        }
    }

    private static String cleanUrl(String u) {
        // 去掉尾部被正则带进来的杂字符
        while (u.endsWith("\\") || u.endsWith(",") || u.endsWith("}") || u.endsWith("]")) {
            u = u.substring(0, u.length() - 1);
        }
        return u.replace("\\u0026", "&").replace("&amp;", "&");
    }

    // 在文本里抓 variants 附近的 bitrate + mp4 url 配对，只留码率最大的一个视频地址
    private static final Pattern VARIANT_PATTERN = Pattern.compile(
            "\"bitrate\"\\s*:\\s*(\\d+)[^}]*?\"url\"\\s*:\\s*\"(https://video\\.twimg\\.com/[^\"\\\\]+?\\.mp4[^\"\\\\]*)\"");
    private static final Pattern VARIANT_PATTERN_REV = Pattern.compile(
            "\"content_type\"\\s*:\\s*\"video/mp4\"[^}]*?\"bitrate\"\\s*:\\s*(\\d+)[^}]*?\"url\"\\s*:\\s*\"(https://video\\.twimg\\.com/[^\"\\\\]+?\\.mp4[^\"\\\\]*)\"");

    private static String extractVariants(String text, LinkedHashMap<String, TweetInfo.Media> out) {
        String best = null; long bestRate = -1;
        for (Pattern p : new Pattern[]{VARIANT_PATTERN, VARIANT_PATTERN_REV}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                try {
                    long rate = Long.parseLong(m.group(1));
                    String u = cleanUrl(m.group(2).replace("\\/", "/"));
                    if (rate > bestRate) { bestRate = rate; best = u; }
                } catch (Exception ignored) {}
            }
        }
        if (best != null && !out.containsKey(best)) {
            out.put(best, new TweetInfo.Media(best, "video"));
        }
        return best;
    }

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("(?:ext_tw_video|amplify_video|tweet_video)/(\\d+)");

    /** 两个 mp4 地址是不是同一个视频的不同清晰度（看 video.twimg 路径里的视频 ID） */
    static boolean sameVideoId(String a, String b) {
        if (a == null || b == null) return false;
        Matcher ma = VIDEO_ID_PATTERN.matcher(a);
        Matcher mb = VIDEO_ID_PATTERN.matcher(b);
        return ma.find() && mb.find() && ma.group(1).equals(mb.group(1));
    }

    /** 一个视频地址的去重键：有视频 ID 用 ID，没有就用完整地址 */
    static String videoKey(String url) {
        if (url == null) return "";
        Matcher m = VIDEO_ID_PATTERN.matcher(url);
        if (m.find()) return "vid:" + m.group(1);
        return "url:" + url;
    }

    // ---------- GraphQL 响应体解析（响应来自页面自己发出的请求，URL 不写死） ----------

    /** 把拦截到的 GraphQL 响应体解析成 TweetInfo；没媒体返回 null */
    public static TweetInfo parseGraphqlBody(String body, String tweetId, String hintUser) {
        try {
            JSONObject root = new JSONObject(body);
            LinkedHashMap<String, TweetInfo.Media> found = new LinkedHashMap<>();
            collectMedia(root, found);
            if (found.isEmpty()) return null;
            TweetInfo info = new TweetInfo();
            info.media.addAll(found.values());
            info.text = findFullText(root);
            String[] user = findUser(root, hintUser);
            if (user != null) { info.authorName = user[0]; info.screenName = user[1]; }
            String created = findCreatedAt(root);
            if (created != null) {
                String d = parseTwitterDate(created);
                if (d != null) info.dateStr = d;
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** 递归遍历任意 JSON：含 media_url_https 收图片；含 video_info.variants 选码率最大的 mp4。 */
    public static void collectMedia(Object node, LinkedHashMap<String, TweetInfo.Media> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String img = o.optString("media_url_https", "");
            if (!img.isEmpty()) {
                String orig = toOrigUrl(img);
                if (!out.containsKey(orig)) out.put(orig, new TweetInfo.Media(orig, "photo", toThumbUrl(img)));
            }
            JSONObject vi = o.optJSONObject("video_info");
            if (vi != null) {
                JSONArray vars = vi.optJSONArray("variants");
                if (vars != null) {
                    String best = null; long bestRate = -1;
                    for (int i = 0; i < vars.length(); i++) {
                        JSONObject v = vars.optJSONObject(i);
                        if (v == null) continue;
                        if (!"video/mp4".equals(v.optString("content_type"))) continue;
                        long rate = v.optLong("bitrate", 0);
                        String u = v.optString("url", "");
                        if (!u.isEmpty() && rate > bestRate) { bestRate = rate; best = u; }
                    }
                    if (best != null && !out.containsKey(best)) {
                        // 视频的封面图就是同一对象上的 media_url_https
                        out.put(best, new TweetInfo.Media(best, "video", img));
                    }
                }
            }
            JSONArray names = o.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    collectMedia(o.opt(k), out);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectMedia(a.opt(i), out);
        }
    }

    public static String findFullText(JSONObject root) {
        String[] best = {""};
        findFullTextRec(root, best);
        return best[0];
    }
    private static void findFullTextRec(Object node, String[] best) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String t = o.optString("full_text", "");
            if (t.length() > best[0].length()) best[0] = t;
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) findFullTextRec(o.opt(names.optString(i)), best);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) findFullTextRec(a.opt(i), best);
        }
    }

    public static String[] findUser(JSONObject root, String hintUser) {
        ArrayList<String[]> candidates = new ArrayList<>();
        findUserRec(root, candidates);
        if (candidates.isEmpty()) return null;
        if (hintUser != null) {
            for (String[] c : candidates) if (hintUser.equalsIgnoreCase(c[1])) return c;
        }
        return candidates.get(0);
    }
    private static void findUserRec(Object node, ArrayList<String[]> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("screen_name") && o.has("name")) {
                String sn = o.optString("screen_name", "");
                String nm = o.optString("name", "");
                if (!sn.isEmpty()) out.add(new String[]{nm, sn});
            }
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) findUserRec(o.opt(names.optString(i)), out);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) findUserRec(a.opt(i), out);
        }
    }

    private static String findCreatedAt(JSONObject root) {
        String[] found = {null};
        findCreatedRec(root, found);
        return found[0];
    }
    private static void findCreatedRec(Object node, String[] found) {
        if (found[0] != null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("created_at") && o.has("full_text")) { found[0] = o.optString("created_at", null); return; }
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) findCreatedRec(o.opt(names.optString(i)), found);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) findCreatedRec(a.opt(i), found);
        }
    }

    static String parseTwitterDate(String createdAt) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US);
            Date d = in.parse(createdAt);
            if (d != null) return new SimpleDateFormat("yyyyMMdd", Locale.US).format(d);
        } catch (Exception ignored) {}
        return null;
    }

    static String todayStr() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    // ---------- 图片原图地址 ----------

    static String toOrigUrl(String url) {
        if (url == null || !url.contains("pbs.twimg.com/media/")) return url;
        String base = url;
        String query = "";
        int q = url.indexOf('?');
        if (q >= 0) { base = url.substring(0, q); query = url.substring(q + 1); }

        String format = null;
        for (String pair : query.split("&")) {
            if (pair.startsWith("format=")) format = pair.substring("format=".length());
        }
        int slash = base.lastIndexOf('/');
        int dot = base.lastIndexOf('.');
        if (dot > slash) {
            String ext = base.substring(dot + 1).toLowerCase(Locale.US);
            if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp")) {
                format = ext.equals("jpeg") ? "jpg" : ext;
                base = base.substring(0, dot);
            }
        }
        if (format == null || format.isEmpty()) format = "jpg";
        return base + "?format=" + format + "&name=orig";
    }

    /** pbs 图片地址转成预览小图（name=thumb）；不是 pbs 地址就原样返回 */
    static String toThumbUrl(String url) {
        if (url == null || !url.contains("pbs.twimg.com/media/")) return url == null ? "" : url;
        String base = url;
        String query = "";
        int q = url.indexOf('?');
        if (q >= 0) { base = url.substring(0, q); query = url.substring(q + 1); }
        String format = null;
        for (String pair : query.split("&")) {
            if (pair.startsWith("format=")) format = pair.substring("format=".length());
        }
        int slash = base.lastIndexOf('/');
        int dot = base.lastIndexOf('.');
        if (dot > slash) {
            String ext = base.substring(dot + 1).toLowerCase(Locale.US);
            if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("webp")) {
                format = ext.equals("jpeg") ? "jpg" : ext;
                base = base.substring(0, dot);
            }
        }
        if (format == null || format.isEmpty()) format = "jpg";
        return base + "?format=" + format + "&name=thumb";
    }

    // ---------- 快速通道：FxTwitter 公开接口响应体解析（v1.0.2） ----------

    /**
     * 解析 GET https://api.fxtwitter.com/status/{id} 的响应体。
     * 结构：tweet.author{name,screen_name}、tweet.text、tweet.created_timestamp（秒）
     * 或 tweet.created_at（字符串）、tweet.media.all[]{type,url,thumbnail_url,width,height,formats/variants}。
     * 没解析出媒体返回 null（调用方转 WebView 兜底）。
     */
    public static TweetInfo parseFxBody(String body, String tweetId, String hintUser) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject tweet = root.optJSONObject("tweet");
            if (tweet == null) return null;

            TweetInfo info = new TweetInfo();
            info.tweetId = tweetId;

            JSONObject author = tweet.optJSONObject("author");
            if (author != null) {
                info.authorName = author.optString("name", "");
                info.screenName = author.optString("screen_name", "");
            }
            if (info.screenName == null || info.screenName.isEmpty()) {
                info.screenName = hintUser != null ? hintUser : "";
            }
            if (info.authorName == null || info.authorName.isEmpty()) info.authorName = info.screenName;

            info.text = tweet.optString("text", "");

            long ts = tweet.optLong("created_timestamp", 0);
            if (ts > 0) {
                info.dateStr = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ts * 1000L));
            } else {
                String created = tweet.optString("created_at", "");
                String d = created.isEmpty() ? null : parseTwitterDate(created);
                if (d != null) info.dateStr = d;
            }
            if (info.dateStr == null || info.dateStr.isEmpty()) info.dateStr = todayStr();

            JSONObject mediaObj = tweet.optJSONObject("media");
            if (mediaObj == null) return null;
            JSONArray all = mediaObj.optJSONArray("all");
            if (all == null) {
                // 没有 all 时退而求其次：photos + videos 合并
                all = new JSONArray();
                JSONArray photos = mediaObj.optJSONArray("photos");
                JSONArray videos = mediaObj.optJSONArray("videos");
                if (photos != null) for (int i = 0; i < photos.length(); i++) all.put(photos.opt(i));
                if (videos != null) for (int i = 0; i < videos.length(); i++) all.put(videos.opt(i));
            }
            LinkedHashMap<String, TweetInfo.Media> found = new LinkedHashMap<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject item = all.optJSONObject(i);
                if (item == null) continue;
                String type = item.optString("type", "");
                if ("photo".equals(type) || "image".equals(type)) {
                    String u = item.optString("url", "");
                    if (u.isEmpty()) continue;
                    String orig = toOrigUrl(u);
                    if (!found.containsKey(orig)) {
                        TweetInfo.Media m = new TweetInfo.Media(orig, "photo", toThumbUrl(u));
                        String d = sizeDetail(item);
                        if (!d.isEmpty()) m.detail = d;
                        found.put(orig, m);
                    }
                } else if ("video".equals(type) || "gif".equals(type)) {
                    String best = pickBestVariantUrl(item);
                    if (best == null || best.isEmpty() || best.contains(".m3u8")) continue;
                    if (!found.containsKey(best)) {
                        TweetInfo.Media m = new TweetInfo.Media(best, "video",
                                item.optString("thumbnail_url", ""));
                        String d = sizeDetail(item);
                        if (!d.isEmpty()) m.detail = d;
                        found.put(best, m);
                    }
                }
            }
            if (found.isEmpty()) return null;
            info.media.addAll(found.values());
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从视频条目的 variants/formats 数组里挑码率/分辨率最高的一档；都没有就用条目自己的 url */
    private static String pickBestVariantUrl(JSONObject item) {
        String best = null;
        long bestScore = -1;
        for (String key : new String[]{"variants", "formats"}) {
            JSONArray arr = item.optJSONArray(key);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                String u = null;
                long score = 0;
                if (o instanceof String) {
                    u = (String) o;
                } else if (o instanceof JSONObject) {
                    JSONObject v = (JSONObject) o;
                    String ct = v.optString("content_type", "");
                    if (!ct.isEmpty() && !ct.contains("mp4")) continue; // 跳过 m3u8 等流格式
                    u = v.optString("url", "");
                    score = v.optLong("bitrate", 0);
                    if (score <= 0) {
                        int w = v.optInt("width", 0), h = v.optInt("height", 0);
                        score = (long) w * (long) h;
                    }
                }
                if (u == null || u.isEmpty() || u.contains(".m3u8")) continue;
                if (score > bestScore) { bestScore = score; best = u; }
            }
        }
        if (best != null) return best;
        return item.optString("url", "");
    }

    private static String sizeDetail(JSONObject item) {
        int w = item.optInt("width", 0), h = item.optInt("height", 0);
        return (w > 0 && h > 0) ? (w + "x" + h) : "";
    }
}
