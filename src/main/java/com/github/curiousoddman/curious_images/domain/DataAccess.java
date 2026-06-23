package com.github.curiousoddman.curious_images.domain;

import com.github.curiousoddman.curious_images.domain.user.prefs.UserPrefKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Record1;
import org.jooq.impl.DefaultDSLContext;
import org.springframework.stereotype.Component;

import static com.github.curiousoddman.curious_images.dbobj.Tables.USER_PREFERENCES;
import static org.jooq.impl.DSL.val;


@Slf4j
@Component
@RequiredArgsConstructor
public class DataAccess {
    private final DefaultDSLContext dsl;

    public void setUserPref(UserPrefKey key, String value) {
        dsl.mergeInto(USER_PREFERENCES)
           .using(dsl.selectOne())
           .on(USER_PREFERENCES.PREF_KEY.eq(key.getKey()))
           .whenMatchedThenUpdate()
           .set(USER_PREFERENCES.PREF_VALUE, val(value))
           .whenNotMatchedThenInsert(USER_PREFERENCES.PREF_KEY, USER_PREFERENCES.PREF_VALUE)
           .values(val(key.getKey()), val(value))
           .execute();

        log.debug("Saved pref [{}] = {}", key.getKey(), value);
    }

    public String getUserPref(UserPrefKey key, String defaultValue) {
        Record1<String> record = dsl
                .select(USER_PREFERENCES.PREF_VALUE)
                .from(USER_PREFERENCES)
                .where(USER_PREFERENCES.PREF_KEY.eq(key.getKey()))
                .fetchOne();

        return record != null ? record.value1() : defaultValue;
    }
}
