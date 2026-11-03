package com.lazith.lovedsongs;

import org.json.JSONObject;

/** One song. The JSON it is saved as is the same one the PC page uses, so syncing just works. */
final class Song {
    String id, title = "Untitled", artist = "Unknown artist", cover, type = "audio/mpeg";
    boolean explicit;
    double duration;      // seconds
    long addedAt, updatedAt;
    String fileName = ""; // only while importing

    static Song parse(String id, String json) {
        try {
            JSONObject o = new JSONObject(json);
            Song s = new Song();
            s.id = id;
            s.title = o.optString("title", "Untitled");
            s.artist = o.optString("artist", "");
            s.cover = o.isNull("cover") ? null : o.optString("cover", null);
            if (s.cover != null && s.cover.length() < 32) s.cover = null;
            s.explicit = o.optBoolean("explicit", false);
            s.duration = o.optDouble("duration", 0);
            if (Double.isNaN(s.duration)) s.duration = 0;
            s.addedAt = o.optLong("addedAt", 0);
            s.updatedAt = o.optLong("updatedAt", s.addedAt);
            s.type = o.optString("type", "audio/mpeg");
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    byte[] toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("artist", artist);
        o.put("cover", cover == null ? JSONObject.NULL : cover);
        o.put("explicit", explicit);
        o.put("duration", duration);
        o.put("addedAt", addedAt);
        o.put("updatedAt", updatedAt);
        o.put("type", type);
        return o.toString().getBytes("UTF-8");
    }
}
