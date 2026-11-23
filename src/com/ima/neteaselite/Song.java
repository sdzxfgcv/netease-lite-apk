package com.ima.neteaselite;

/** 歌曲信息。mid 保存网易云歌曲 id，albumMid 保存封面图片地址。 */
public class Song {
    public String mid = "";
    public String name = "";
    public String singer = "";
    public String albumMid = "";
    public int interval = 0;

    public Song() {}

    @Override
    public String toString() {
        return name + " - " + singer;
    }
}
