package com.demo.mycarview;

import android.webkit.WebView;

/** Best-effort YouTube mobile ad skipper for the embedded WebView. */
final class YoutubeAdSkipper {
    private YoutubeAdSkipper() {}

    static void run(WebView web) {
        if (web == null) return;
        String url = web.getUrl();
        if (url == null || !(url.contains("youtube.com") || url.contains("youtu.be"))) return;

        String js = "(function(){" +
                "try{" +
                "var h=(location.hostname||'').toLowerCase();" +
                "if(h.indexOf('youtube.com')<0&&h.indexOf('youtu.be')<0)return 'not-youtube';" +
                "var clicked=0;" +
                "var sels=['button.ytp-ad-skip-button','button.ytp-ad-skip-button-modern','.ytp-ad-skip-button button','.ytp-ad-skip-button','.ytp-ad-overlay-close-button','button[aria-label*=\\\"Skip\\\"]','button[aria-label*=\\\"skip\\\"]','button[aria-label*=\\\"Bỏ qua\\\"]','button[aria-label*=\\\"Bo qua\\\"]'];" +
                "for(var i=0;i<sels.length;i++){var ns=document.querySelectorAll(sels[i]);for(var j=0;j<ns.length;j++){var b=ns[j];if(b&&b.offsetParent!==null){try{b.click();clicked++;}catch(e){}}}}" +
                "var v=document.querySelector('video');" +
                "var p=document.querySelector('.html5-video-player.ad-showing,.ad-showing');" +
                "if(p&&v){" +
                "if(window.__cvAdActive!==true){window.__cvAdActive=true;window.__cvWasMuted=!!v.muted;}" +
                "try{v.muted=true;}catch(e){}" +
                "try{if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.25){v.currentTime=Math.max(v.currentTime,v.duration-0.15);}}catch(e){}" +
                "try{if(v.paused){var r=v.play();if(r&&r.catch)r.catch(function(){});}}catch(e){}" +
                "}else if(window.__cvAdActive===true){" +
                "try{if(v)v.muted=!!window.__cvWasMuted;}catch(e){}" +
                "window.__cvAdActive=false;" +
                "}" +
                "var hide=['.ytp-ad-overlay-container','ytm-companion-ad-renderer','ytm-promoted-sparkles-web-renderer','ytm-display-ad-renderer','ytm-ad-slot-renderer'];" +
                "for(var k=0;k<hide.length;k++){var xs=document.querySelectorAll(hide[k]);for(var q=0;q<xs.length;q++){try{xs[q].style.display='none';}catch(e){}}}" +
                "return clicked+':' + (p?'ad':'content');" +
                "}catch(e){return 'err';}" +
                "})()";
        try {
            web.evaluateJavascript(js, null);
        } catch (Throwable ignored) {
        }
    }
}
