package com.github.curiousoddman.curious_images.util;

import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@UtilityClass
public class CollectionUtils {
    public static <T> Map<Long, Integer> getIdToIndexMap(List<T> objects, Function<T, Long> idExtractor) {
        Map<Long, Integer> map = new HashMap<>(objects.size() * 2);
        for (int i = 0; i < objects.size(); i++) {
            T object = objects.get(i);
            map.put(idExtractor.apply(object), i);
        }
        return map;
    }
}
