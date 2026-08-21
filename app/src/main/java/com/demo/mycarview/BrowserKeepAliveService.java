package com.demo.mycarview;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.SystemClock;

public class BrowserKeepAliveService extends Service {
    static final String ACTION_COMMAND = "com.demo.mycarview.BACKGROUND_COMMAND";
    static final String ACTION_UPDATE_STATE = "com.demo.mycarview.BACKGROUND_STATE";
    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_PLAYING = "playing";

    static final String CMD_PLAY = "play";
    static final String CMD_PAUSE = "pause";
    static final String CMD_TOGGLE = "toggle";
    static final String CMD_REWIND = "rewind";
    static final String CMD_FORWARD = "forward";
    static final String CMD_STOP = "stop";

    private static final String CHANNEL_ID = "carview_background";
    private static final int NOTIFICATION_ID = 1201;

    private MediaSession mediaSession;
    private boolean playing;

    @Override public void onCreate() {
        super.onCreate();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                "Phát nền CarView AA",
                NotificationManager.IMPORTANCE_LOW));

        mediaSession = new MediaSession(this, "CarViewAA");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() {
                playing = true;
                dispatchToPlayer(CMD_PLAY);
                refreshNotification();
            }

            @Override public void onPause() {
                playing = false;
                dispatchToPlayer(CMD_PAUSE);
                refreshNotification();
            }

            @Override public void onRewind() {
                dispatchToPlayer(CMD_REWIND);
            }

            @Override public void onFastForward() {
                dispatchToPlayer(CMD_FORWARD);
            }

            @Override public void onStop() {
                playing = false;
                dispatchToPlayer(CMD_PAUSE);
                stopSelf();
            }
        });
        mediaSession.setActive(true);
        updatePlaybackState();

        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_COMMAND.equals(action)) {
                handleCommand(intent.getStringExtra(EXTRA_COMMAND));
            } else if (ACTION_UPDATE_STATE.equals(action)) {
                playing = intent.getBooleanExtra(EXTRA_PLAYING, playing);
                refreshNotification();
            }
        }
        return START_NOT_STICKY;
    }

    private void handleCommand(String command) {
        if (command == null) return;
        switch (command) {
            case CMD_PLAY:
                playing = true;
                dispatchToPlayer(CMD_PLAY);
                break;
            case CMD_PAUSE:
                playing = false;
                dispatchToPlayer(CMD_PAUSE);
                break;
            case CMD_TOGGLE:
                playing = !playing;
                dispatchToPlayer(playing ? CMD_PLAY : CMD_PAUSE);
                break;
            case CMD_REWIND:
                dispatchToPlayer(CMD_REWIND);
                break;
            case CMD_FORWARD:
                dispatchToPlayer(CMD_FORWARD);
                break;
            case CMD_STOP:
                playing = false;
                dispatchToPlayer(CMD_PAUSE);
                stopSelf();
                return;
            default:
                return;
        }
        refreshNotification();
    }

    private void dispatchToPlayer(String command) {
        if (getApplication() instanceof SessionResumeApplication) {
            ((SessionResumeApplication) getApplication()).dispatchPlaybackCommand(command);
        }
    }

    private void updatePlaybackState() {
        long actions = PlaybackState.ACTION_PLAY |
                PlaybackState.ACTION_PAUSE |
                PlaybackState.ACTION_PLAY_PAUSE |
                PlaybackState.ACTION_REWIND |
                PlaybackState.ACTION_FAST_FORWARD |
                PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(
                        playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        playing ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build());
    }

    private PendingIntent commandIntent(String command, int requestCode) {
        Intent i = new Intent(this, BrowserKeepAliveService.class)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_COMMAND, command);
        return PendingIntent.getService(
                this,
                requestCode,
                i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this,
                10,
                open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action rewind = new Notification.Action.Builder(
                android.R.drawable.ic_media_rew,
                "-10s",
                commandIntent(CMD_REWIND, 21))
                .build();
        Notification.Action toggle = new Notification.Action.Builder(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "Tạm dừng" : "Phát",
                commandIntent(CMD_TOGGLE, 22))
                .build();
        Notification.Action forward = new Notification.Action.Builder(
                android.R.drawable.ic_media_ff,
                "+10s",
                commandIntent(CMD_FORWARD, 23))
                .build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CarView AA")
                .setContentText(playing ? "Đang phát video trong nền" : "Phiên video sẵn sàng")
                .setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(rewind)
                .addAction(toggle)
                .addAction(forward)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private void refreshNotification() {
        if (mediaSession == null) return;
        updatePlaybackState();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // The WebView belongs to MainActivity. Once the user explicitly removes
        // the task there is no media surface left to control, so avoid leaving a
        // phantom foreground notification behind.
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
