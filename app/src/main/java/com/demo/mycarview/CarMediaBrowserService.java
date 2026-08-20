package com.demo.mycarview;

import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;

import java.util.Collections;
import java.util.List;

/**
 * Android Auto media entry point.
 *
 * The car host renders the browsing/playback UI. This service only exposes a
 * safe media-session bridge to the currently alive CarView phone session.
 */
public class CarMediaBrowserService extends MediaBrowserService {
    private static final String ROOT_ID = "carview_root";
    private static final String ITEM_CURRENT = "carview_current_session";

    private MediaSession mediaSession;
    private boolean playing;

    @Override public void onCreate() {
        super.onCreate();

        mediaSession = new MediaSession(this, "CarViewAA-Car");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                sendCommand(BrowserKeepAliveService.CMD_PLAY);
            }

            @Override public void onPause() {
                sendCommand(BrowserKeepAliveService.CMD_PAUSE);
            }

            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (ITEM_CURRENT.equals(mediaId)) {
                    sendCommand(BrowserKeepAliveService.CMD_PLAY);
                }
            }

            @Override public void onFastForward() {
                sendCommand(BrowserKeepAliveService.CMD_FORWARD);
            }

            @Override public void onRewind() {
                sendCommand(BrowserKeepAliveService.CMD_REWIND);
            }

            @Override public void onStop() {
                sendCommand(BrowserKeepAliveService.CMD_PAUSE);
            }
        });

        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "CarView AA")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Phiên phát trên điện thoại")
                .build());
        mediaSession.setActive(true);
        setSessionToken(mediaSession.getSessionToken());
        updatePlaybackState(false);
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(ROOT_ID, null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaItem>> result) {
        if (!ROOT_ID.equals(parentId)) {
            result.sendResult(Collections.emptyList());
            return;
        }

        MediaDescription description = new MediaDescription.Builder()
                .setMediaId(ITEM_CURRENT)
                .setTitle("CarView AA")
                .setSubtitle("Điều khiển phiên đang phát trên điện thoại")
                .build();
        result.sendResult(Collections.singletonList(
                new MediaItem(description, MediaItem.FLAG_PLAYABLE)));
    }

    private void sendCommand(String command) {
        boolean accepted = false;
        if (getApplication() instanceof SessionResumeApplication) {
            accepted = ((SessionResumeApplication) getApplication()).dispatchPlaybackCommand(command);
        }

        if (BrowserKeepAliveService.CMD_PLAY.equals(command)) {
            playing = accepted;
        } else if (BrowserKeepAliveService.CMD_PAUSE.equals(command)) {
            playing = false;
        }
        updatePlaybackState(playing);
    }

    private void updatePlaybackState(boolean isPlaying) {
        long actions = PlaybackState.ACTION_PLAY |
                PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE |
                PlaybackState.ACTION_FAST_FORWARD |
                PlaybackState.ACTION_REWIND |
                PlaybackState.ACTION_STOP |
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID;

        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(
                        isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        isPlaying ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build());
    }

    @Override public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
