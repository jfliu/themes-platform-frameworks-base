/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.InterfaceConfiguration;
import android.net.INetworkManagementEventObserver;
import android.os.INetworkManagementService;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import java.util.ArrayList;
import java.util.StringTokenizer;
import android.provider.Settings;
import android.content.ContentResolver;
import android.database.ContentObserver;

import java.io.File;
import java.io.FileReader;
import java.lang.IllegalStateException;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @hide
 */
class NetworkManagementService extends INetworkManagementService.Stub {

    private static final String TAG = "NetworkManagmentService";

    class NetdResponseCode {
        public static final int InterfaceListResult       = 110;
        public static final int TetherInterfaceListResult = 111;
        public static final int TetherDnsFwdTgtListResult = 112;
        public static final int TtyListResult             = 113;

        public static final int TetherStatusResult        = 210;
        public static final int IpFwdStatusResult         = 211;
        public static final int InterfaceGetCfgResult     = 213;
    }

    /**
     * Binder context for this service
     */
    private Context mContext;

    /**
     * connector object for communicating with netd
     */
    private NativeDaemonConnector mConnector;

    private ArrayList<INetworkManagementEventObserver> mObservers;

    /**
     * Constructs a new NetworkManagementService instance
     *
     * @param context  Binder context for this service
     */
    public NetworkManagementService(Context context) {
        mContext = context;

        mObservers = new ArrayList<INetworkManagementEventObserver>();

        mConnector = new NativeDaemonConnector(
                new NetdCallbackReceiver(), "netd", 10, "NetdConnector");
        Thread thread = new Thread(mConnector, NativeDaemonConnector.class.getName());
        thread.start();
    }

    public void registerObserver(INetworkManagementEventObserver obs) {
        Log.d(TAG, "Registering observer");
        mObservers.add(obs);
    }

    public void unregisterObserver(INetworkManagementEventObserver obs) {
        Log.d(TAG, "Unregistering observer");
        mObservers.remove(mObservers.indexOf(obs));
    }

    /**
     * Notify our observers of an interface link status change
     */
    private void notifyInterfaceLinkStatusChanged(String iface, boolean link) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceLinkStatusChanged(iface, link);
            } catch (Exception ex) {
                Log.w(TAG, "Observer notifier failed", ex);
            }
        }
    }

    /**
     * Notify our observers of an interface addition.
     */
    private void notifyInterfaceAdded(String iface) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceAdded(iface);
            } catch (Exception ex) {
                Log.w(TAG, "Observer notifier failed", ex);
            }
        }
    }

    /**
     * Notify our observers of an interface removal.
     */
    private void notifyInterfaceRemoved(String iface) {
        for (INetworkManagementEventObserver obs : mObservers) {
            try {
                obs.interfaceRemoved(iface);
            } catch (Exception ex) {
                Log.w(TAG, "Observer notifier failed", ex);
            }
        }
    }


    //
    // Netd Callback handling
    //

    class NetdCallbackReceiver implements INativeDaemonConnectorCallbacks {
        public void onDaemonConnected() {
            new Thread() {
                public void run() {
                    // XXX: Run some tests
                }
            }.start();
        }
        public boolean onEvent(int code, String raw, String[] cooked) {
           return false;
        }
    }

    private static int stringToIpAddr(String addrString) throws UnknownHostException {
        try {
            String[] parts = addrString.split("\\.");
            if (parts.length != 4) {
                throw new UnknownHostException(addrString);
            }

            int a = Integer.parseInt(parts[0])      ;
            int b = Integer.parseInt(parts[1]) <<  8;
            int c = Integer.parseInt(parts[2]) << 16;
            int d = Integer.parseInt(parts[3]) << 24;

            return a | b | c | d;
        } catch (NumberFormatException ex) {
            throw new UnknownHostException(addrString);
        }
    }

    public static String intToIpString(int i) {
        return ((i >> 24 ) & 0xFF) + "." + ((i >> 16 ) & 0xFF) + "." + ((i >>  8 ) & 0xFF) + "." +
               (i & 0xFF);
    }

    //
    // INetworkManagementService members
    //

    public String[] listInterfaces() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        return mConnector.doListCommand("interface list", NetdResponseCode.InterfaceListResult);
    }

    public InterfaceConfiguration getInterfaceConfig(String iface) throws IllegalStateException {
        String rsp = mConnector.doCommand("interface getcfg " + iface).get(0);
        Log.d(TAG, String.format("rsp <%s>", rsp));

        // Rsp: 213 xx:xx:xx:xx:xx:xx yyy.yyy.yyy.yyy zzz.zzz.zzz.zzz [flag1 flag2 flag3]
        StringTokenizer st = new StringTokenizer(rsp);

        try {
            int code = Integer.parseInt(st.nextToken(" "));
            if (code != NetdResponseCode.InterfaceGetCfgResult) {
                throw new IllegalStateException(
                    String.format("Expected code %d, but got %d",
                            NetdResponseCode.InterfaceGetCfgResult, code));
            }
        } catch (NumberFormatException nfe) {
            throw new IllegalStateException(
                    String.format("Invalid response from daemon (%s)", rsp));
        }

        InterfaceConfiguration cfg = new InterfaceConfiguration();
        cfg.hwAddr = st.nextToken(" ");
        try {
            cfg.ipAddr = stringToIpAddr(st.nextToken(" "));
        } catch (UnknownHostException uhe) {
            Log.e(TAG, "Failed to parse ipaddr", uhe);
            cfg.ipAddr = 0;
        }

        try {
            cfg.netmask = stringToIpAddr(st.nextToken(" "));
        } catch (UnknownHostException uhe) {
            Log.e(TAG, "Failed to parse netmask", uhe);
            cfg.netmask = 0;
        }
        cfg.interfaceFlags = st.nextToken("]");
        Log.d(TAG, String.format("flags <%s>", cfg.interfaceFlags));
        return cfg;
    }

    public void setInterfaceConfig(
            String iface, InterfaceConfiguration cfg) throws IllegalStateException {
        String cmd = String.format("interface setcfg %s %s %s", iface,
                intToIpString(cfg.ipAddr), intToIpString(cfg.netmask), cfg.interfaceFlags);
        mConnector.doCommand(cmd);
    }

    public void shutdown() {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires SHUTDOWN permission");
        }

        Log.d(TAG, "Shutting down");
    }

    public boolean getIpForwardingEnabled() throws IllegalStateException{
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp = mConnector.doCommand("ipfwd status");

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.IpFwdStatusResult) {
                // 211 Forwarding <enabled/disabled>
                if (tok.length !=2) {
                    throw new IllegalStateException(
                            String.format("Malformatted list entry '%s'", line));
                }
                if (tok[2].equals("enabled"))
                    return true;
                return false;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void setIpForwardingEnabled(boolean enable) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(String.format("ipfwd %sable", (enable ? "en" : "dis")));
    }

    public void startTethering(String dhcpRangeStart, String dhcpRangeEnd)
             throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(String.format("tether start %s %s", dhcpRangeStart, dhcpRangeEnd));
    }

    public void stopTethering() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand("tether stop");
    }

    public boolean isTetheringStarted() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");

        ArrayList<String> rsp = mConnector.doCommand("tether status");

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == NetdResponseCode.TetherStatusResult) {
                // XXX: Tethering services <started/stopped> <TBD>...
                if (tok[2].equals("started"))
                    return true;
                return false;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public void tetherInterface(String iface) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand("tether interface add " + iface);
    }

    public void untetherInterface(String iface) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand("tether interface remove " + iface);
    }

    public String[] listTetheredInterfaces() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        return mConnector.doListCommand(
                "tether interface list", NetdResponseCode.TetherInterfaceListResult);
    }

    public void setDnsForwarders(String[] dns) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        try {
            String cmd = "tether dns set ";
            for (String s : dns) {
                cmd += InetAddress.getByName(s).toString() + " ";
            }
            mConnector.doCommand(cmd);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Error resolving dns name", e);
        }
    }

    public String[] getDnsForwarders() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        return mConnector.doListCommand(
                "tether dns list", NetdResponseCode.TetherDnsFwdTgtListResult);
    }

    public void enableNat(String internalInterface, String externalInterface)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(
                String.format("nat enable %s %s", internalInterface, externalInterface));
    }

    public void disableNat(String internalInterface, String externalInterface)
            throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(
                String.format("nat disable %s %s", internalInterface, externalInterface));
    }

    public String[] listTtys() throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE, "NetworkManagementService");
        return mConnector.doListCommand("list_ttys", NetdResponseCode.TtyListResult);
    }

    public void attachPppd(String tty, String localAddr, String remoteAddr)
            throws IllegalStateException {
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
            mConnector.doCommand(String.format("pppd attach %s %s %s", tty,
                    InetAddress.getByName(localAddr).toString(),
                    InetAddress.getByName(localAddr).toString()));
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Error resolving addr", e);
        }
    }

    public void detachPppd(String tty) throws IllegalStateException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE, "NetworkManagementService");
        mConnector.doCommand(String.format("pppd detach %s", tty));
    }
}
