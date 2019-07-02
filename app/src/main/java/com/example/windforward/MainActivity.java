package com.example.windforward;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
//import android.view.Menu;
//import android.view.MenuItem;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    // TODO: Enable switching update rate for wind unit
    // TODO: Scan and select Calypso wind unit, (force by button, else try reconnecting to last unit)
    // TODO: Store last unit adress
    // TODO: Program resets when screen is rotated, fix that.
    // TODO: Remove unused pages/views/buttons
    // TODO: Cleanup on exit
    // TODO: Try reconnect if bt disconnects before active deconnection.
    // TODO: compass calibration table.
    // TODO: true heading back from opencpn? (corrected for deviation)
    // TODO: settings, max data age.


    // TODO: Access should be syncronized
    EditText sensorDeviceAddress;

    private double cogT = 0.0; // Last received COG (degrees true north)
    private double sogMS = 0.0; // Last received SOG (meters/second)
    private double sogKPH = 0.0; // Last received SOG (kilometers/hour)
    long gpsTimestamp = 0;
    final long limitGpsTimestamp = 60*1000; // ms, data not used if older than this

    double appWndDir = 0.0; // apparent wind angle (degrees rel. heading)
    double appWndSpd = 0.0; // apparent wind speed m/s
    int airTemp = 0; // Temperature, C
    long windTempTimestamp = 0;
    final long limitWindTempTimestamp = 10*1000; // ms, data not used if older than this

    double hdg = 0.0; // heading, degrees (should be true north TODO: true north and deviation correction)
    int heel = 0; // heel in degrees, positive to starboard
    int pitch = 0; // pitch in degrees, positive nose up
    long hdgHeelPitchTimestamp = 0;
    final long limitHdgHeelPitchTimestamp = 10*1000; // ms, data not used if older than this

    int updateRate = 4; //Hz (1,4,8)
    int oldUpdateRate = -1; //Hz
    int compassEnable = 1; // 0 or 1
    int oldCompassEnable = -1;

    public class CompassLUT {
        // Headings as indicated by compass
        // Assumed strictly monotonically increasing, with wrap-arounds at the ends (cover entire range 0-360)
        int[] compassHdgs = {330 - 360, 50, 160, 230, 330, 50 + 360};

        // Corresponding true magnetic headings (or true north if including)
        //int[] magHdgs = {340 - 360, 50, 150, 230, 340, 50 + 360};
        int[] magHdgs = {330 - 360, 50, 160, 230, 330, 50 + 360};

        public int correct(int compassHdg) {
            int preHdg = fixRange(compassHdg);
            int idx = 1;
            while (preHdg > compassHdgs[idx])
                ++idx;
            float frac = (1.0f * (compassHdgs[idx] - preHdg)) / (1.0f * (compassHdgs[idx] - compassHdgs[idx - 1]));
            float correctedHdg = frac * magHdgs[idx - 1] + (1.0f - frac) * magHdgs[idx];
            return (fixRange(Math.round(correctedHdg)));
        }

        private int fixRange(int hdg) {
            while (hdg < 0) hdg += 360;
            while (hdg >= 360) hdg -= 360;
            return hdg;
        }
    }
    CompassLUT compassLUT = new CompassLUT();

    Timer tmrSend = new Timer();
    Timer tmrRecv = new Timer();
    Timer tmrDataFreshness = new Timer();

    //String btStatusMsgLog = "";
    enum MsgDestination {
        btStatus,
        btData,
        netRecvStatus,
        netTransmStatus,
    }
    long lastBt;
    long lastNetRecv;
    long lastNetTransm;

    public class UpdateFreshnessTask extends TimerTask {
        @Override
        public void run() {
            final long currentTime = android.os.SystemClock.elapsedRealtime();
            final long btAge = currentTime-lastBt;
            final long netRecvAge = currentTime-lastNetRecv;
            final long netTransmAge = currentTime-lastNetTransm;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ((TextView) findViewById(R.id.tvBLEtime)).setText(
                            ageToString(btAge)
                    );
                    ((TextView) findViewById(R.id.tvNetworkRecvTime)).setText(
                            ageToString(netRecvAge)
                    );
                    ((TextView) findViewById(R.id.tvNetworkTransmTime)).setText(
                            ageToString(netTransmAge)
                    );
                }
            });
        }

        private String ageToString(long age)
        {
            if (age >= 2*3600*1000) {
                return Long.toString(age/3600000) + " hours ago";
            }
            if (age >= 2*60*1000) {
                return Long.toString(age/60000) + " minutes ago";
            }
            if (age > 2*1000) {
                return Long.toString(age/1000) + " seconds ago";
            }
            return "active";
        }
    }

    void initTimeStamps() {
        lastBt = android.os.SystemClock.elapsedRealtime();
        lastNetRecv = android.os.SystemClock.elapsedRealtime();
        lastNetTransm = android.os.SystemClock.elapsedRealtime();

        tmrDataFreshness.schedule(new UpdateFreshnessTask(), 1000, 500);
    }

    synchronized public void setUiMsg(final MsgDestination dest, final String msg) {
        TextView destTV = null;
        switch (dest) {
            case btStatus:
                destTV = (TextView) findViewById(R.id.tvBLEstatus);
                lastBt = android.os.SystemClock.elapsedRealtime();
                break;

            case btData:
                destTV = (TextView) findViewById(R.id.tvBLEdevices);
                lastBt = android.os.SystemClock.elapsedRealtime();
                break;

            case netRecvStatus:
                destTV = (TextView) findViewById(R.id.tvNetRecv);
                lastNetRecv = android.os.SystemClock.elapsedRealtime();
                break;

            case netTransmStatus:
                destTV = (TextView) findViewById(R.id.tvNetTransm);
                lastNetTransm = android.os.SystemClock.elapsedRealtime();
                break;

            default:
                break;
        }
        if (null != destTV)
        {
            final TextView finalDestination = destTV;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finalDestination.setText(msg);
                }
            });
        }
    }

    static String appendNMEAchecksum(String initMessage) {
        int len = initMessage.length();
        byte[] msgBytes = initMessage.getBytes();
        int chksum = 0;
        for (int i = 1; i < len; ++i)
        {
            chksum = chksum ^ msgBytes[i];
        }

        return initMessage + "*" + Integer.toHexString(chksum) + "\n";
    }

    public class ReceiverTask extends TimerTask {
        private DatagramSocket sock;
        private byte[] buffer = new byte[2048];
        private DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        public ReceiverTask() {
            try {
                InetAddress local = InetAddress.getByName("127.0.0.1");
                sock = new DatagramSocket(12246, local);
            } catch (Exception e) {
                setUiMsg(MsgDestination.netRecvStatus, "Receiver failed to start: " + e.toString());
            }
        }

        @Override
        public void run() {
            try {
                sock.receive(packet);
                String msgcat = new String(buffer, 0, packet.getLength());
                String[] msgs = msgcat.split("$");
                String printmsg = "";
                for (int idx = 0; idx < msgs.length; ++idx)
                {
//                    if (msgs[idx].length() > 5 && msgs[idx].substring(2,5).equals("PGG")) {
                        if (msgs[idx].length() > 5 && msgs[idx].substring(3,6).equals("VTG")) {
                            String[] parts = msgs[idx].split(",");
                            if (parts.length > 5 && parts[1].length() > 0 && parts[5].length() > 0) {
                                cogT = Double.parseDouble(parts[1]);
                                sogMS = Double.parseDouble(parts[5]) * 1852.0 / 3600.0;

                                gpsTimestamp = android.os.SystemClock.elapsedRealtime();
                                printmsg = "Received:   COG: " + cogT + " deg     SOG: " + sogMS + " m/s";
                                setUiMsg(MsgDestination.netRecvStatus, printmsg);
                            } else {
                                sogMS = 0.0;
                            }
                            sogKPH = sogMS * 3.6;
                        }
                }

                packet.setLength(buffer.length);
            } catch (Exception e) {
                setUiMsg(MsgDestination.netRecvStatus, "Reception fail: " + e.toString());
            }
        }
    }

    public class UpdateTask extends TimerTask {
        @Override
        public void run() {
            final long now = android.os.SystemClock.elapsedRealtime();
            final boolean gpsValid = (now - gpsTimestamp) < limitGpsTimestamp;
            final boolean appWindValid = (now - windTempTimestamp) < limitWindTempTimestamp;
            final boolean hdgHeelPitchValid = (now - hdgHeelPitchTimestamp) < limitHdgHeelPitchTimestamp;

            double cogHdgDelta = cogT - hdg; // COG relative heading

            // Following in a heading-up coordinate system (y-axis)
            double vbX = sogMS * Math.sin(Math.toRadians(cogHdgDelta)); // boat velocity in boat coordinate system (m/s)
            double vbY = sogMS * Math.cos(Math.toRadians(cogHdgDelta));
            double wAppX = appWndSpd * Math.sin(Math.toRadians(appWndDir)); // Apparent wind in boat coordinate system (m/s)
            double wAppY = appWndSpd * Math.cos(Math.toRadians(appWndDir));
            double wTrueHdgX = wAppX - vbX; // True wind in boat coordinate system (m/s)
            double wTrueHdgY = wAppY - vbY;

            double trueWndSpd = Math.sqrt(wTrueHdgX * wTrueHdgX + wTrueHdgY * wTrueHdgY); // True wind speed (m/s)
            double trueWndDir = Math.toDegrees(Math.atan2(wTrueHdgX, wTrueHdgY)); // True wind relative true heading
            if (trueWndDir < 0) trueWndDir += 360.0;

            double trueWndDirRelNorth = trueWndDir + hdg;
            if (trueWndDirRelNorth >= 360.0) trueWndDirRelNorth -= 360.0;


            try {
                //String messageHDG = appendNMEAchecksum("$HCHDG," + hdg + ",,,7.1,W");
                //String messageHDG = appendNMEAchecksum("$HCHDG," + hdg + ",,,,");
                String messageHDG = appendNMEAchecksum("$HCHDM," + hdg + ",M");
                //String messageHDG = appendNMEAchecksum("$HCHDT," + hdg + ",T");

                String messageMWVapp = appendNMEAchecksum("$WIMWV," + appWndDir + ",R," + appWndSpd + ",M,A");
                String messageMWVtrue = appendNMEAchecksum("$WIMWV," + trueWndDir + ",T," + trueWndSpd + ",M,A");
                String messageMWD = appendNMEAchecksum("$WIMWD," + trueWndDirRelNorth + ",T,,M,,N," + trueWndSpd + ",M");
                String messageXDR = appendNMEAchecksum("$IIXDR," +
                        "C," + airTemp + ",C,ENV_OUTAIR_T," +
                        "A," + pitch + ",D,PTCH," +
                        "A," + heel + ",D,ROLL");
                String messageXDRonlyTemp = appendNMEAchecksum("$IIXDR," +
                        "C," + airTemp + ",C,ENV_OUTAIR_T");

                String logMsg = "Sent ";

                int server_port = 12245;
                InetAddress local = InetAddress.getByName("127.0.0.1");
                DatagramSocket s = new DatagramSocket();
                String myAddr = s.getLocalAddress().toString();
                int msg_length;
                byte[] message;
                DatagramPacket p;

                if (appWindValid) {
                    msg_length = messageMWVapp.length();
                    message = messageMWVapp.getBytes();
                    p = new DatagramPacket(message, msg_length, local, server_port);
                    s.send(p);

                    logMsg += "AppWind ";
                }

                if (hdgHeelPitchValid) {
                    msg_length = messageHDG.length();
                    message = messageHDG.getBytes();
                    p = new DatagramPacket(message, msg_length, local, server_port);
                    s.send(p);

                    msg_length = messageXDR.length();
                    message = messageXDR.getBytes();
                    p = new DatagramPacket(message, msg_length, local, server_port);
                    s.send(p);

                    logMsg += "HDG AirTemp Heel Pitch ";

                    if (gpsValid && appWindValid) {
                        msg_length = messageMWVtrue.length();
                        message = messageMWVtrue.getBytes();
                        p = new DatagramPacket(message, msg_length, local, server_port);
                        s.send(p);

                        msg_length = messageMWD.length();
                        message = messageMWD.getBytes();
                        p = new DatagramPacket(message, msg_length, local, server_port);
                        s.send(p);

                        logMsg += "\n True wind angle " + trueWndDir + " degrees";
                        logMsg += "\n True wind direction " + trueWndDirRelNorth + " degrees";
                        logMsg += "\n True wind speed " + trueWndSpd + " m/s";
                    }
                }
                else if (appWindValid) { // No heel and pitch info, send only temperature
                    msg_length = messageXDR.length();
                    message = messageXDR.getBytes();
                    p = new DatagramPacket(message, msg_length, local, server_port);
                    s.send(p);
                    logMsg += "AirTemp ";
                }

                if (logMsg.length() > 5)
                    setUiMsg(MsgDestination.netTransmStatus, logMsg);
                else
                    setUiMsg(MsgDestination.netTransmStatus, "Nothing to send, base data too old.");
            } catch (Exception e) {
                setUiMsg(MsgDestination.netTransmStatus, "Transmit failed.");
            }
        }
    }

    public void onClickStop(View view) {
        try {
            tmrSend.cancel();
            tmrRecv.cancel();

            setUiMsg(MsgDestination.netRecvStatus, "Receiver stopped.");
            setUiMsg(MsgDestination.netTransmStatus, "Transmitter stopped.");
        } catch (Exception e) {
            setUiMsg(MsgDestination.netRecvStatus, "Receiver stop failed: " + e.toString());
            setUiMsg(MsgDestination.netTransmStatus, "Transmitter stop failed: " + e.toString());
        }

        if (null != sensorGatt) {
            setUiMsg(MsgDestination.btStatus, "BT: Stopping...");
            sensorGatt.disconnect();
        }
    }

    public int extractCmpId(java.util.UUID uuid)
    {
        return (int)((uuid.getMostSignificantBits() & 0xffff00000000L) >> 32);
    }

    public class SensorScanCallback extends ScanCallback
    {
        String devicesList;
        Context mConnectContext;

        SensorScanCallback(Context connectContext) {
            mConnectContext = connectContext;
            devicesList = "Devices:\n";
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            //Callback when batch results are delivered.
            devicesList += " Batch results,";
            setUiMsg(MsgDestination.btData, devicesList);
        }

        @Override
        public void onScanFailed(int errorCode) {
            //
            devicesList += " failed("+errorCode+"),";
            setUiMsg(MsgDestination.btData, devicesList);
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            // Callback when a BLE advertisement has been found.
            // TODO: print device name, check for general calypso bt devices, not just this specific one.
            String targetAddr = sensorDeviceAddress.getText().toString().toUpperCase().trim();
            if (result.getDevice().getAddress().contentEquals(targetAddr)) {
                devicesList += "\nFound " + result.getDevice().getAddress();
                sensorGatt = result.getDevice().connectGatt(mConnectContext, true, sensorGattCallback);
                setUiMsg(MsgDestination.btStatus, "BT: Connecting... (may take minutes)");
                leScanner.stopScan(this);
            }
            else
                devicesList += ".";
            setUiMsg(MsgDestination.btData, devicesList);
        }
    }

    BluetoothManager bluetoothManager;
    BluetoothAdapter bluetoothAdapter;
    SensorScanCallback sensorScanCallback;
    public BluetoothLeScanner leScanner;
    BluetoothGatt sensorGatt;

    String tmpString = "not yet!";

    public class SensorGattCallback extends BluetoothGattCallback {
        View mView;
        SensorGattCallback(View view) {
            mView = view;
        }

        BluetoothGattCharacteristic chCompassEnable = null;
        BluetoothGattCharacteristic chUpdateRate = null;
        BluetoothGattDescriptor descDataNotifyEnable = null;

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            switch (newState) {
                case BluetoothGatt.STATE_DISCONNECTED:
                    setUiMsg(MsgDestination.btStatus, "BT: State disconnected");
                    break;

                case BluetoothGatt.STATE_CONNECTING:
                    setUiMsg(MsgDestination.btStatus, "BT: State connecting...");
                    break;

                case BluetoothGatt.STATE_CONNECTED:
                    if (!gatt.discoverServices())
                        setUiMsg(MsgDestination.btStatus, "BT: State connected, failed to discover services");
                    else
                        setUiMsg(MsgDestination.btStatus, "BT: State connected, discovering services...");
                    break;

                case BluetoothGatt.STATE_DISCONNECTING:
                    setUiMsg(MsgDestination.btStatus, "BT: State disconnecting...");
                    break;

                default:
                    setUiMsg(MsgDestination.btStatus, "BT: State unknown");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            setUiMsg(MsgDestination.btStatus, "BT: Services discovered.");
            boolean notifyActivated = false;
            String uuids = "Services:";
            List<BluetoothGattService> lServ = gatt.getServices();
            for (BluetoothGattService serv : lServ) {
                uuids += "\nS " + Integer.toHexString(extractCmpId(serv.getUuid()));
                //uuids += "\nS " + serv.getUuid().toString();
                if (0x180d == extractCmpId(serv.getUuid())) {
                    List<BluetoothGattCharacteristic> lCharas = serv.getCharacteristics();
                    for (BluetoothGattCharacteristic chara : lCharas) {
                        uuids += "\n    C " + Integer.toHexString(extractCmpId(chara.getUuid()));
                        switch (extractCmpId(chara.getUuid())) {
                            case 0x2a39: //Main data notify, store Descriptor to activate notify
                                gatt.setCharacteristicNotification(chara, true);
                                if (1 == chara.getDescriptors().size() && 0x2902 == extractCmpId(chara.getDescriptors().get(0).getUuid())) {
                                    descDataNotifyEnable = chara.getDescriptors().get(0);
                                }
                                break;

                            case 0xa002: // Wind speed data output rate, RW (0x1, 0x4, 0x8)
                                chUpdateRate = chara;
                                break;

                            case 0xA003: // Active clinometer/compass, RW (0x0-off, 0x1-on)
                                chCompassEnable = chara;
                                break;

                            default:
                                break;
                        }
                    }
                }
            }

            if (null != descDataNotifyEnable){
                descDataNotifyEnable.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                notifyActivated = gatt.writeDescriptor(descDataNotifyEnable);
            }

            setUiMsg(MsgDestination.btData, uuids);
            if (notifyActivated) setUiMsg(MsgDestination.btStatus, "BT: State connected, subscribed.");
            else setUiMsg(MsgDestination.btStatus, "BT: State connected, unable to subscribe.");
        }

        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (0x2a39 == extractCmpId(characteristic.getUuid())) {
                byte[] valData = characteristic.getValue();
                String dataview = "0x2a39 data:\n";
                for (byte ch : valData)
                    dataview += Integer.toHexString(ch & 0xff) + " ";

                if (10 == valData.length) {
                    double appWindSpeedMS = ((valData[1] & 0xff) * 256 + (valData[0] & 0xff)) * 0.01;
                    int appWindAngleDeg = (valData[3] & 0xff) * 256 + (valData[2] & 0xff);
                    int batteryLevelPerc = (valData[4] & 0xff) * 10;
                    int airTempC = (valData[5] & 0xff) - 100;
                    int rollDeg = (valData[6] & 0xff) - 90; // positive to port
                    int pitchDeg = (valData[7] & 0xff) - 90;
                    int compassHdgDeg = 359 - ((valData[9] & 0xff) * 256 + (valData[8] & 0xff));

                    appWndDir = appWindAngleDeg; // apparent wind angle (degrees rel. heading)
                    appWndSpd = appWindSpeedMS; // apparent wind speed m/s
                    airTemp = airTempC; // Temperature, C
                    dataview += "\n Apparent wind speed: " + Double.toString(appWindSpeedMS) + " m/s";
                    dataview += "\n Apparent wind angle: " + Integer.toString(appWindAngleDeg) + " degrees";
                    dataview += "\n Battery: " + Integer.toString(batteryLevelPerc) + "%";
                    dataview += "\n Air temperature: " + Integer.toString(airTempC) + " C";
                    windTempTimestamp = android.os.SystemClock.elapsedRealtime();

                    if ((valData[6] & 0xff) != 0 || (valData[7] & 0xff) != 0) {
                        // Pitch and roll data available, heading also
                        heel = -rollDeg; // heel in degrees, positive to starboard
                        pitch = pitchDeg; // pitch in degrees, positive nose up
                        hdg = compassLUT.correct(compassHdgDeg); // heading, degrees (should be true north)
                        dataview += "\n Roll: " + Integer.toString(rollDeg) + " degrees";
                        dataview += "\n Pitch: " + Integer.toString(pitchDeg) + " degrees";
                        dataview += "\n Compass heading: " + Integer.toString(compassHdgDeg) + " degrees, corrected " + Double.toString(hdg);
                        hdgHeelPitchTimestamp = android.os.SystemClock.elapsedRealtime();
                    } else {
                        dataview += "\n Roll: N/A";
                        dataview += "\n Pitch: N/A";
                        dataview += "\n Compass heading: N/A";
                    }
                }

                setUiMsg(MsgDestination.btData, dataview);
            } else {
                setUiMsg(MsgDestination.btData, "Notify on " + Integer.toHexString(extractCmpId(characteristic.getUuid())));
            }

            // Update states
            if(compassEnable != oldCompassEnable && (compassEnable == 1 || compassEnable == 0)) {
                oldCompassEnable = compassEnable;
                boolean compassActivated = false;
                if (null != chCompassEnable) {
                    BluetoothGattCharacteristic chara = chCompassEnable;
                    byte[] val = new byte[1];
                    val[0] = (byte) compassEnable;
                    chara.setValue(val);
                    compassActivated = gatt.writeCharacteristic(chara);
                    if (compassActivated)
                        setUiMsg(MsgDestination.btStatus, "BT: State connected, sent compass enable state.");
                    else
                        setUiMsg(MsgDestination.btStatus, "BT: State connected, unable to send compass enable state.");
                } else
                    setUiMsg(MsgDestination.btStatus, "BT: State connected, compass enable characteristic not found.");
            }
            else if(updateRate != oldUpdateRate && (updateRate == 1 || updateRate == 4)) {
                oldUpdateRate = updateRate;
                boolean updateRateUpdated = false;
                if (null != chUpdateRate) {
                    BluetoothGattCharacteristic chara = chUpdateRate;
                    byte[] val = new byte[1];
                    val[0] = (byte) updateRate;
                    chara.setValue(val);
                    updateRateUpdated = gatt.writeCharacteristic(chara);
                    if (updateRateUpdated)
                        setUiMsg(MsgDestination.btStatus, "BT: State connected, sent new update rate.");
                    else
                        setUiMsg(MsgDestination.btStatus, "BT: State connected, unable to send update rate.");
                } else
                    setUiMsg(MsgDestination.btStatus, "BT: State connected, update rate characteristic not found.");
            }
        }

        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

        }

        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {

        }

        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            //setUiMsg(MsgDestination.btStatus, "Wrote chara " + Integer.toHexString(extractCmpId(characteristic.getUuid())));
        }

        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            //setUiMsg(MsgDestination.btStatus, "Wrote desc " + Integer.toHexString(extractCmpId(descriptor.getUuid())));
        }

    }
    SensorGattCallback sensorGattCallback;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        initTimeStamps();

        // Load address of device to connect to
        sensorDeviceAddress = (EditText) findViewById(R.id.etCalAddr);
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        String conAddr = prefs.getString("CalypsoDeviceAddress", "AA:AA:AA:AA:AA:AA");
        sensorDeviceAddress.setText(conAddr);

        // Start transmitter timer
        try {
            tmrSend.schedule(new UpdateTask(), 1000, 250);
            // TODO: Trigger when data received?

            setUiMsg(MsgDestination.netTransmStatus, "Transmit started.");
        } catch (Exception e) {
            setUiMsg(MsgDestination.netTransmStatus, "Transmit start failed: " + e.toString());
        }

        // Start receiver timer
        try {
            tmrRecv.schedule(new ReceiverTask(), 1000, 5);

            setUiMsg(MsgDestination.netRecvStatus, "Receiver started.");
        } catch (Exception e) {
            setUiMsg(MsgDestination.netRecvStatus, "Receiver start failed: " + e.toString());
        }

        // Try setup bluetooth
        bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        setUiMsg(MsgDestination.btStatus, "BT: Starting...");
        setUiMsg(MsgDestination.btData, "");

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            //int REQUEST_ENABLE_BT = 47;
            //Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            //startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            setUiMsg(MsgDestination.btStatus, "BT: Disabled");
            setUiMsg(MsgDestination.btData, "Enable Bluetooth and restart app.");
            return;
        }

        if (bluetoothAdapter.isDiscovering())
        {
            setUiMsg(MsgDestination.btStatus, "BT: Already discovering.");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                != PackageManager.PERMISSION_GRANTED) {
            setUiMsg(MsgDestination.btStatus, "BT: Missing Bluetooth permission.");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
                != PackageManager.PERMISSION_GRANTED) {
            setUiMsg(MsgDestination.btStatus, "BT: Missing Bluetooth discover permission.");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            setUiMsg(MsgDestination.btStatus, "BT: Location permission must be granted to discover BLE devices, allow positioning for this app among the app settings and restart the app.");
            return;
        }

        sensorScanCallback = new SensorScanCallback(this);
        sensorGattCallback = new SensorGattCallback(this.findViewById(R.id.toolbar));

        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        setUiMsg(MsgDestination.btStatus, "BT: Scanning... (may take minutes)");
        setUiMsg(MsgDestination.btData, "Make sure that location is activated on the phone, it is required to find BLE devices.");
        leScanner.startScan(sensorScanCallback);

        // Setup handling of switches
        Switch toggleFreq = (Switch) findViewById(R.id.swHighFreq);
        toggleFreq.setChecked(true);
        toggleFreq.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    updateRate = 4;
                } else {
                    updateRate = 1;
                }
            }
        });
        Switch toggleCompass = (Switch) findViewById(R.id.swCompass);
        toggleCompass.setChecked(true);
        toggleCompass.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    compassEnable = 1;
                } else {
                    compassEnable = 0;
                }
            }
        });

    }

    @Override
    protected void onStop() {
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("CalypsoDeviceAddress", sensorDeviceAddress.getText().toString());
        editor.commit();

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /*@Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    } */
}
