package com.demo.mycarview;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class AdBlocker {
    private static final Set<String> BLOCKED_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "adservice.google.com.vn",
            "securepubads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com",
            "ads.pubmatic.com",
            "adsrvr.org",
            "adnxs.com",
            "criteo.com",
            "criteo.net",
            "scorecardresearch.com"
    ));

    private AdBlocker() {}

    static WebResourceResponse intercept(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.US);
            if (!isBlocked(host)) return null;
            return new WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    new ByteArrayInputStream(new byte[0]));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isBlocked(String host) {
        for (String blocked : BLOCKED_HOSTS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
        }
        return false;
    }
}
