package com.axonbase.common;

import java.time.Instant;

/** Observabilidade mínima do AxonBase. */
public final class Log {

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    private final String name;
    private final Level level;

    private Log(String name, Level level) {
        this.name = name;
        this.level = level;
    }

    public static Log get(String name) {
        return new Log(name, Level.INFO);
    }

    public static Log get(Class<?> type) {
        return get(type.getName());
    }

    public void debug(String msg) {
        log(Level.DEBUG, msg);
    }

    public void info(String msg) {
        log(Level.INFO, msg);
    }

    public void warn(String msg) {
        log(Level.WARN, msg);
    }

    public void error(String msg) {
        log(Level.ERROR, msg);
    }

    private void log(Level lvl, String msg) {
        if (lvl.ordinal() < level.ordinal()) {
            return;
        }
        System.err.printf("%s %s [%s] %s%n", Instant.now(), lvl, name, msg);
    }
}