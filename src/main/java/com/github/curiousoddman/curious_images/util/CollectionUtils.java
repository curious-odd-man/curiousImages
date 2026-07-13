package com.github.curiousoddman.curious_images.util;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@UtilityClass
public class CollectionUtils {
    public static Map<Long, Integer> getIdToIndexMap(List<PhotoRecord> photos) {
        Map<Long, Integer> map = new HashMap<>(photos.size() * 2);
        for (int i = 0; i < photos.size(); i++) {
            PhotoRecord photoRecord = photos.get(i);
            map.put(photoRecord.getId(), i);
        }
        return map;
    }
}
