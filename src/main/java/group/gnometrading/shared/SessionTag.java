package group.gnometrading.shared;

import group.gnometrading.resources.Properties;

/**
 * What a session's order ids start with, so ids stay unique across sessions although each OMS counts its orders
 * from 1: the session's id, or for a local run without one, its start time.
 */
public final class SessionTag {

    private SessionTag() {}

    public static String of(final Properties properties) {
        if (properties.hasProperty("session.id")) {
            return properties.getStringProperty("session.id");
        }
        return "t" + Long.toString(System.currentTimeMillis(), Character.MAX_RADIX);
    }
}
