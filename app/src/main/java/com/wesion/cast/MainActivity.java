package com.wesion.cast;

import static com.wesion.cast.RaopServer.RAOP_STATE_SETUP;
import static com.wesion.cast.RaopServer.RAOP_STATE_TEARDOWN;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.TextureView;
import android.view.View;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;

public class MainActivity extends Activity implements RaopCallBack, WifiP2pManager.ChannelListener,
        WifiP2pManager.PeerListListener, WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {
    public static String TAG = "WesionCast";

    private AirPlayServer mAirPlayServer;
    private RaopServer mRaopServer;
    private DNSNotify mDNSNotify;
    private TextureView mTextureView;
    private RelativeLayout mVideoLayout;
    private RelativeLayout mLogoLayout;

    public static final String HRESOLUTION_DISPLAY = "display_resolution_hd";
    public static final String WIFI_P2P_IP_ADDR_CHANGED_ACTION = "android.net.wifi.p2p.IPADDR_INFORMATION";
    public static final String WIFI_P2P_PEER_IP_EXTRA = "IP_EXTRA";
    public static final String WIFI_P2P_PEER_MAC_EXTRA = "MAC_EXTRA";
    public static final int WFD_DEFAULT_PORT = 7236;
    public static final int WFD_MAX_THROUGHPUT = 50;
    public boolean mForceStopScan = false;
    public boolean mManualInitWfdSession = false;
    private WifiP2pManager manager = null;
    private boolean isWifiP2pEnabled = false;
    private String mPort;
    private String mIP;
    private Handler mHandler = new Handler();
    private IntentFilter intentFilter = new IntentFilter();
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver mReceiver = null;
    private boolean retryChannel = false;
    private WifiP2pDevice mDevice = null;
    private ArrayList<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();

    private final Runnable startSearchRunnable = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.Q)
        @Override
        public void run() {
            if (!mForceStopScan) {
                Log.e(TAG, "startSearchRunnable.");
                startSearch();
            }
        }
    };

    private final Runnable searchAgainRunnable = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.Q)
        @Override
        public void run() {
            if (!mForceStopScan && peers.isEmpty()) {
                Log.e(TAG, "searchAgainRunnable, no peers, search again.");
                startSearch();
            }
        }
    };

    private class WiFiDirectBroadcastReceiver extends BroadcastReceiver {
        private String mWfdMac;
        private String mWfdPort;
        private boolean mWfdIsConnected = false;
        private boolean mSinkIsConnected = false;

        @RequiresApi(api = Build.VERSION_CODES.Q)
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            WifiP2pWfdInfo wfdInfo = null;

            Log.d(TAG, "onReceive action:" + action);
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    isWifiP2pEnabled = true;
                } else {
                    isWifiP2pEnabled = false;
                    peers.clear();
                }
                Log.d(TAG, "P2P state changed - " + state);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (manager != null && !mForceStopScan) {
                    Log.d(TAG, "requestPeers!!!!");
                    manager.requestPeers(channel, (WifiP2pManager.PeerListListener) MainActivity.this);
                }
                Log.d(TAG, "P2P peers changed");
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (manager == null) {
                    return;
                }

                NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                WifiP2pInfo p2pInfo = (WifiP2pInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO);
                WifiP2pGroup p2pGroup = (WifiP2pGroup) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);

                if (networkInfo.isConnected()) {
                    mWfdIsConnected = true;
                    if (p2pGroup.isGroupOwner() == true) {
                        Log.d(TAG, "I am GO GO GO");

                        WifiP2pDevice device = null;
                        for (WifiP2pDevice c : p2pGroup.getClientList()) {
                            device = c;
                            break;
                        }

                        if (device != null) {
                            wfdInfo = device.getWfdInfo();
                            if (wfdInfo != null) {
                                mWfdPort = String.valueOf(wfdInfo.getControlPort());
                                mWfdMac = device.deviceAddress;
                            }

                            Log.d(TAG, "mWfdMac= " + mWfdMac + " mWfdPort " + mWfdPort + " p2pGroup.getInterface= " + p2pGroup.getInterface());
                            ArpTableAnalytical.getMatchedIp(mWfdMac, p2pGroup.getInterface(),
                                    new ArpTableAnalytical.ArpTableMatchListener() {
                                        @Override
                                        public void onMatched(String peerIp) {
                                            Log.d(TAG, "getMatchedIp, onMatched, peerIp= " + peerIp);
                                            startMiracast(peerIp, mWfdPort);
                                        }

                                        @Override
                                        public void onNotMatched() {
                                            Log.d(TAG, "getMatchedIp, onNotMatched");
                                        }
                                    });
                        }

                    } else {
                        Log.d(TAG, "I am GC GC GC");
                        WifiP2pDevice device = p2pGroup.getOwner();
                        if (device != null) {
                            wfdInfo = device.getWfdInfo();
                            if (wfdInfo != null) {
                                mWfdPort = String.valueOf(wfdInfo.getControlPort());
                                Log.d(TAG, "mWfdPort:" + mWfdPort);
                                if (mWfdPort.equals(WFD_DEFAULT_PORT)) {
                                    startMiracast(p2pInfo.groupOwnerAddress.getHostAddress(), mWfdPort);
                                } else {
                                    Log.d(TAG, "use default port");
                                    startMiracast(p2pInfo.groupOwnerAddress.getHostAddress(), Integer.toString(WFD_DEFAULT_PORT));
                                }
                            } else {
                                Log.d(TAG, "wfdInfo is null");
                            }
                        } else {
                            Log.d(TAG, "device is null");
                        }
                    }
                    mSinkIsConnected = false;
                } else {
                    mWfdIsConnected = false;
                    peers.clear();
                    Log.d(TAG, "ForceStopScan = " + mForceStopScan);
                    if (!mForceStopScan) {
                        startSearch();
                    }
                }

            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                peers.clear();
                setDevice((WifiP2pDevice) intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE));
            } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                int discoveryState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                if (discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                    if (!mForceStopScan) {
                        startSearchTimer();
                    }
                }
                Log.d(TAG, "Discovery state changed: " + discoveryState + " (1:stop, 2:start)");
            } else if (MainActivity.WIFI_P2P_IP_ADDR_CHANGED_ACTION.equals(action)) {
                String ipaddr = intent.getStringExtra(MainActivity.WIFI_P2P_PEER_IP_EXTRA);
                String macaddr = intent.getStringExtra(MainActivity.WIFI_P2P_PEER_MAC_EXTRA);
                Log.d(TAG, "ipaddr is " + ipaddr + "  macaddr is " + macaddr);
                if (ipaddr != null && macaddr != null) {
                    if (mWfdIsConnected) {
                        if (!mSinkIsConnected) {
                            if ((mWfdMac.substring(0, 11)).equals(macaddr.substring(0, 11))) {
                                Log.d(TAG, "wfdMac:" + mWfdMac + ", macaddr:" + macaddr + " is mate!!");
                                startMiracast(ipaddr, mWfdPort);
                                mSinkIsConnected = true;
                            } else {
                                Log.d(TAG, "wfdMac:" + mWfdMac + ", macaddr:" + macaddr + " is unmate!!");
                            }
                        }
                    }
                }
            }
        }
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
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "====onCreate=====");
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setAttributes(lp);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideNavigationBar(MainActivity.this);

        mTextureView = findViewById(R.id.airplaySurface);
        mVideoLayout = (RelativeLayout) findViewById(R.id.videoLayout);
        mLogoLayout = (RelativeLayout) findViewById(R.id.logoLayout);

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        intentFilter.addAction(WIFI_P2P_IP_ADDR_CHANGED_ACTION);

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), this);
        mReceiver = new WiFiDirectBroadcastReceiver();

        mAirPlayServer = new AirPlayServer();
        mRaopServer = new RaopServer(mTextureView, mVideoLayout, MainActivity.this);
        mDNSNotify = new DNSNotify();
        startServer();
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "====onResume=====");
        if (mReceiver != null) {
            registerReceiver(mReceiver, intentFilter, Context.RECEIVER_EXPORTED);
            Log.d(TAG, "register p2p Receiver success!");
        }

        changeRole(true);
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "removeGroup Success");
            }

            @Override
            public void onFailure(int reasonCode) {
                Log.d(TAG, "removeGroup Failure");
            }
        });
        peers.clear();
        tryDiscoverPeers();

        startDNSNotify();
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "====onPause=====");
        changeRole(false);
        if (mReceiver != null) {
            unregisterReceiver(mReceiver);
        }
        stopPeerDiscovery();

        stopDNSNotify();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "====onDestroy====");
        changeRole(false);
        isWifiP2pEnabled = false;
        peers.clear();
        stopServer();
    }

    private void startDNSNotify() {
        String deviceName = Build.MODEL;
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (!TextUtils.isEmpty(androidId)) {
            Log.d(TAG, "androidId:" + androidId);
            deviceName = deviceName + "_" + androidId.substring(0, 4);
        }

        mDNSNotify.setDeviceName(deviceName);
        int airplayPort = mAirPlayServer.getPort();
        if (airplayPort != 0) {
            mDNSNotify.registerAirplay(airplayPort);
        }

        int raopPort = mRaopServer.getPort();
        if (raopPort != 0) {
            mDNSNotify.registerRaop(raopPort);
        }
        Log.d(TAG, "airplayPort = " + airplayPort + ", raopPort = " + raopPort);
    }

    private void stopDNSNotify() {
        mDNSNotify.stop();
    }

    private void startServer() {
        mAirPlayServer.startServer();
        mRaopServer.startServer();
    }

    private void stopServer() {
        mAirPlayServer.stopServer();
        mRaopServer.stopServer();
    }

    @Override
    public void raopStateCallBack(int state) {
        synchronized (mRaopServer) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (state == RAOP_STATE_SETUP) {
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                mLogoLayout.setVisibility(View.INVISIBLE);
                            }
                        }, 500);
                        //mLogoLayout.setVisibility(View.INVISIBLE);
                    } else if (state == RAOP_STATE_TEARDOWN) {
                        mLogoLayout.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }

    @Override
    public void onChannelDisconnected() {
        Log.d(TAG, "onChannelDisconnected");
        if (manager != null && !retryChannel) {
            Log.d(TAG, "Channel lost. Trying again");
            peers.clear();
            retryChannel = true;
            manager.initialize(this, getMainLooper(), this);
        } else {
            Log.d(TAG, "Severe! Channel is probably lost premanently. Try Disable/Re-Enable P2P.");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    public void onPeersAvailable(WifiP2pDeviceList devicelist) {
        Log.d(TAG, "===onPeersAvailable===:" + devicelist.getDeviceList().size());
        peers.clear();
        peers.addAll(devicelist.getDeviceList());
        for (int i = 0; i < peers.size(); i++) {
            if (!peers.get(i).getWfdInfo().isEnabled()) {
                Log.d(TAG, "peerDevice:" + peers.get(i).deviceName + " is not a wfd device");
                continue;
            }
            if (!peers.get(i).getWfdInfo().isSessionAvailable()) {
                Log.d(TAG, "peerDevice:" + peers.get(i).deviceName + " is an unavailable wfd session");
                continue;
            }

            if ((WifiP2pDevice.INVITED == peers.get(i).status) || (WifiP2pDevice.CONNECTED == peers.get(i).status)) {
                cancelSearchTimer();
            }

            Log.d(TAG, "peerDevice:" + peers.get(i).deviceName + ", status:" + peers.get(i).status + " (0-CONNECTED, 1-INVITED, 2-FAILED, 3-AVAILABLE, 4-UNAVAILABLE)");
        }

        if (!mForceStopScan) {
            mHandler.postDelayed(searchAgainRunnable, 5000);
        }
    }

    private String describeWifiP2pDevice(WifiP2pDevice device) {
        return device != null ? device.toString().replace('\n', ',') : "null";
    }

    @SuppressLint("MissingPermission")
    private void requestPeers() {
        manager.requestPeers(channel, new WifiP2pManager.PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peerLists) {
                Log.d(TAG, "Received list of peers, size: " + peerLists.getDeviceList().size());
                peers.clear();
                for (WifiP2pDevice device : peerLists.getDeviceList()) {
                    Log.d(TAG, "  " + describeWifiP2pDevice(device));
                    peers.add(device);
                }

                for (int i = 0; i < peers.size(); i++) {
                    Log.d(TAG, "onPeersAvailable peerDevice:" + peers.get(i).deviceName + ", status:" + peers.get(i).status + " (0-CONNECTED,3-AVAILABLE)");
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void tryDiscoverPeers() {
        Log.d(TAG, "tryDiscoverPeers......");
        mForceStopScan = false;
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Discover peers succeed.Requesting peers now.");
                requestPeers();
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Discover peers failed with reason " + reason + ".");
            }
        });
    }

    private void stopPeerDiscovery() {
        Log.d(TAG, "stopPeerDiscovery......");
        mForceStopScan = true;
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Stop peer discovery succeed.");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Stop peer discovery failed with reason " + reason + ".");
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void changeRole(boolean isSink) {
        WifiP2pWfdInfo wfdInfo = new WifiP2pWfdInfo();
        if (isSink) {
            wfdInfo.setEnabled(true);
            wfdInfo.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
            wfdInfo.setSessionAvailable(true);
            wfdInfo.setControlPort(WFD_DEFAULT_PORT);
            wfdInfo.setMaxThroughput(WFD_MAX_THROUGHPUT);
        } else {
            wfdInfo.setEnabled(false);
        }

        manager.setWfdInfo(channel, wfdInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Successfully set WFD info.");
            }

            @Override
            public void onFailure(int reason) {
                Log.d(TAG, "Failed to set WFD info with reason " + reason + ".");
                mHandler.postDelayed(() -> {
                    changeRole(true);
                }, 500);
            }
        });
    }

    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        Log.d(TAG, "onConnectionInfoAvailable info:" + info);
    }

    public void onGroupInfoAvailable(WifiP2pGroup group) {
        if (group != null) {
            Log.d(TAG, "onGroupInfoAvailable true : " + group);
        } else {
            Log.d(TAG, "onGroupInfoAvailable false");
        }
    }

    public void startMiracast(String ip, String port) {
        mPort = port;
        mIP = ip;
        if (mManualInitWfdSession) {
            Log.d(TAG, "waiting startMiracast");
            return;
        }
        Log.d(TAG, "start miracast");
        Intent intent = new Intent(MainActivity.this, SinkActivity.class);
        Bundle bundle = new Bundle();
        bundle.putString(SinkActivity.KEY_PORT, mPort);
        bundle.putString(SinkActivity.KEY_IP, mIP);
        bundle.putBoolean(HRESOLUTION_DISPLAY, true);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    public void startSearchTimer() {
        Log.d(TAG, " startSearchTimer 5s");
        mHandler.postDelayed(startSearchRunnable, 5000);
    }

    public void cancelSearchTimer() {
        Log.d(TAG, " cancelSearchTimer");
        mHandler.removeCallbacks(startSearchRunnable);
        Log.d(TAG, " cancel searchAgainRunnable");
        mHandler.removeCallbacks(searchAgainRunnable);
    }

    public void setDevice(WifiP2pDevice device) {
        mDevice = device;
        if ((WifiP2pDevice.CONNECTED == mDevice.status) || (WifiP2pDevice.INVITED == mDevice.status)) {
            cancelSearchTimer();
        }
        Log.d(TAG, "localDevice name:" + mDevice.deviceName + ", status:" + mDevice.status + " (0-CONNECTED,3-AVAILABLE)");
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public void startSearch() {
        Log.d(TAG, "startSearch wifiP2pEnabled:" + isWifiP2pEnabled);
        if (mHandler.hasCallbacks(startSearchRunnable)) {
            cancelSearchTimer();
        }

        if (!isWifiP2pEnabled) {
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this, android.Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "discoverPeers init success");
            }

            @Override
            public void onFailure(int reasonCode) {
                Log.d(TAG, "discoverPeers init failure, reasonCode:" + reasonCode);
            }
        });
    }
}


