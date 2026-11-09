package com.muse.xdownloader;

import java.util.ArrayList;
import java.util.List;

/** 一条推文解析出来的结果 */
public class TweetInfo {
    public static class Media {
        public String url;
        /** "photo" 或 "video"（动图在 X 上也是 mp4 视频） */
        public String type;
        /** 预览图地址（视频封面 / 图片小图）；拿不到就为空字符串，界面上用占位图顶上 */
        public String thumbUrl = "";
        /** 可选的补充说明，如分辨率 "1280x720"；拿不到就为空字符串 */
        public String detail = "";
        public Media(String url, String type) { this.url = url; this.type = type; }
        public Media(String url, String type, String thumbUrl) {
            this.url = url; this.type = type;
            if (thumbUrl != null) this.thumbUrl = thumbUrl;
        }
        public boolean isVideo() { return "video".equals(type); }
    }

    public String tweetId = "";
    public String authorName = "";
    public String screenName = "";
    public String text = "";
    /** yyyyMMdd，用来给文件名做日期；取不到推文日期时用下载当天 */
    public String dateStr = "";
    /** 非空时是给用户看的提醒（例如视频只有流地址下不了，但图片还能下） */
    public String warning = "";
    public List<Media> media = new ArrayList<>();
}
