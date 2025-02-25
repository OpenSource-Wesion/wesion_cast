package com.wesion.cast;

import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.media.AudioManager;
import android.media.MediaPlayer;


public class SinkActivity extends Activity {
    public static final String TAG = "SinkActivity";
    public static final String KEY_IP = "ip";
    public static final String KEY_PORT = "port";
    private String mIP;
    private String mPort;
    private boolean mMiracastRunning = false;
    private SurfaceView mSurfaceView;
    private MediaPlayer mMediaPlayer;
    private static final int CMD_MIRACAST_FINISHVIEW = 1;
    private static final int CMD_MIRACAST_EXIT = 2;
    private static final int CMD_MIRACAST_START = 11;
    private static final int CMD_MIRACAST_STOP = 12;

    private Handler mMiracastHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CMD_MIRACAST_START: {
                    try {
                        Bundle data = msg.getData();
                        String ip = data.getString(KEY_IP);
                        String port = data.getString(KEY_PORT);
                        String wfdUrl = "wfd://" + ip + ":" + port;
                        mMediaPlayer = new MediaPlayer();
                        mMediaPlayer.setDataSource(wfdUrl);
                        mMediaPlayer.prepareAsync();
                        mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                            public boolean onError(MediaPlayer mp, int what, int extra) {
                                Log.e(TAG, "Receive a error mp is" + mp + " what is " + what + " extra is " + extra);
                                stopMiracast(true);
                                finishView();
                                return true;
                            }
                        });
                        mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                            public void onPrepared(MediaPlayer mp) {
                                mp.start();
                                mp.setDisplay(mSurfaceView.getHolder());
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }
                case CMD_MIRACAST_STOP: {
                    mMediaPlayer.stop();
                    mMediaPlayer.release();
                    break;
                }
                default:
                    break;
            }
        }
    };

    private Handler mSessionHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
        Log.d(TAG, "SessionHandler msg.what = " + msg.what);
        switch (msg.what) {
            case CMD_MIRACAST_FINISHVIEW:
                Window window = getWindow();
                WindowManager.LayoutParams wl = window.getAttributes();
                wl.alpha = 0.0f;
                window.setAttributes(wl);
                Intent homeIntent = new Intent(SinkActivity.this, MainActivity.class);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                SinkActivity.this.startActivity(homeIntent);
                SinkActivity.this.finish();
                break;
            case CMD_MIRACAST_EXIT:
                unregisterReceiver(mReceiver);
                stopMiracast(true);
                break;
            default:
                break;
        }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
            NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            Log.d(TAG, "P2P connection changed isConnected:" + networkInfo.isConnected() + " mMiracastRunning:" + mMiracastRunning);
            if (!networkInfo.isConnected() && mMiracastRunning) {
                stopMiracast(true);
                finishView();
            }
        }
        }
    };

    private void finishView() {
        Log.e(TAG, "finishView");
        Message msg = Message.obtain();
        msg.what = CMD_MIRACAST_FINISHVIEW;
        mSessionHandler.sendMessage(msg);
    }

    private void hideNavigationBar(Activity activity) {
        View decorView = activity.getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);
        ActionBar actionBar = activity.getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setAttributes(lp);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sink);
        hideNavigationBar(SinkActivity.this);

        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mSurfaceView = (SurfaceView) findViewById(R.id.wifiDisplaySurface);
        mSurfaceView.getHolder().addCallback(new SurfaceCallback());
        mSurfaceView.getHolder().setKeepScreenOn(true);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        mPort = bundle.getString(KEY_PORT);
        mIP = bundle.getString(KEY_IP);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        registerReceiver(mReceiver, intentFilter);
    }

    protected void onDestroy() {
        super.onDestroy();
        if (mSessionHandler != null) {
            mSessionHandler.removeCallbacksAndMessages(null);
        }
        mSessionHandler = null;
        Log.d(TAG, "Sink Activity destory");
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "sink activity onResume");
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSessionHandler != null) {
            Message msg = Message.obtain();
            msg.what = CMD_MIRACAST_EXIT;
            mSessionHandler.sendMessage(msg);
        }
        Log.d(TAG, "sink activity onPause");
    }

    public void startMiracast(String ip, String port) {
        Log.d(TAG, "start miracast isRunning:" + mMiracastRunning + " IP:" + ip + ":" + port);
        mMiracastRunning = true;
        Message msg = Message.obtain();
        msg.what = CMD_MIRACAST_START;
        Bundle data = msg.getData();
        data.putString(KEY_IP, ip);
        data.putString(KEY_PORT, port);
        if (mMiracastHandler != null) {
            mMiracastHandler.sendMessage(msg);
        }
    }

    public void stopMiracast(boolean owner) {
        Log.d(TAG, "stop miracast running:" + mMiracastRunning + ", owner:" + owner);
        if (mMiracastRunning) {
            try {
                Message msg = Message.obtain();
                msg.what = CMD_MIRACAST_STOP;
                if (mMiracastHandler != null) {
                    mMiracastHandler.sendMessage(msg);
                }
                mMiracastRunning = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        mIP = null;
        mPort = null;
    }

    private class SurfaceCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // TODO Auto-generated method stub
            Log.d(TAG, "surfaceChanged");
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "surfaceCreated");
            if (TextUtils.isEmpty(mIP)) {
                finishView();
                return;
            }

            if (mMiracastRunning == false) {
                startMiracast(mIP, mPort);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // TODO Auto-generated method stub
            Log.d(TAG, "surfaceDestroyed");
            if (mMiracastRunning) {
                stopMiracast(true);
            }
        }
    }

}
