package com.axonbase.server;

import java.io.InputStream;
import java.util.Properties;

/** Versão real do build, vinda do recurso filtrado pelo Maven ({@code axonbase-build.properties}). */
public final class BuildInfo {

    private static final String VERSION = load();

    private BuildInfo() {
    }

    public static String version() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/axonbase-build.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version", "").trim();
                if (!v.isEmpty() && !v.startsWith("${")) {
                    return v;
                }
            }
        } catch (java.io.IOException ignored) {
            // cai no valor de desenvolvimento
        }
        return "dev";
    }
}
