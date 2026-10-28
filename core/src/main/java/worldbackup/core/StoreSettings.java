package worldbackup.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Machine-wide settings, kept in the store's {@code store.json} so every server and save shares one cap. */
public final class StoreSettings {
    public long maxTotalMB = 10000;
    public long minFreeMB = 2000;
    public int warnAtPercent = 90;

    public long maxTotalBytes() {
        return maxTotalMB * 1024L * 1024L;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", 1L);
        m.put("maxTotalMB", maxTotalMB);
        m.put("minFreeMB", minFreeMB);
        m.put("warnAtPercent", (long) warnAtPercent);
        return m;
    }

    public static StoreSettings fromJson(Map<String, Object> m) {
        StoreSettings s = new StoreSettings();
        s.maxTotalMB = Json.num(m, "maxTotalMB", s.maxTotalMB);
        s.minFreeMB = Json.num(m, "minFreeMB", s.minFreeMB);
        s.warnAtPercent = (int) Json.num(m, "warnAtPercent", s.warnAtPercent);
        return s;
    }
}
