/**
 * ScaleActivity monitors a USB HID Point of Sale
 * weight scale device.  It interprets the scale data
 * packet and displays the resulting weight on the screen.
 * 
 * USB HID POS Specification:
 * http://www.usb.org/developers/devclass_docs/pos1_02.pdf
 */

package com.examples.usb.scalemonitor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;

public class ScaleActivity extends Activity implements Runnable {

    private static final String TAG = "ScaleMonitor";

    private TextView mWeight, mStatus;
    private WakeLock mWakeLock;
    
    private UsbManager mUsbManager;
    private UsbDevice mDevice;
    private UsbDeviceConnection mConnection;
    private UsbEndpoint mEndpointIntr;

    // USB HIS POS Constants
    private static final int UNITS_MILLIGRAM = 0x01;
    private static final int UNITS_GRAM = 0x02;
    private static final int UNITS_KILOGRAM = 0x03;
    private static final int UNITS_CARAT = 0x04;
    private static final int UNITS_TAEL = 0x05;
    private static final int UNITS_GRAIN = 0x06;
    private static final int UNITS_PENNYWEIGHT = 0x07;
    private static final int UNITS_MTON = 0x08;
    private static final int UNITS_ATON = 0x09;
    private static final int UNITS_TROYOUNCE = 0x0A;
    private static final int UNITS_OUNCE = 0x0B;
    private static final int UNITS_POUND = 0x0C;

    private static final int STATUS_FAULT = 0x01;
    private static final int STATUS_STABLEZERO = 0x02;
    private static final int STATUS_INMOTION = 0x03;
    private static final int STATUS_STABLE = 0x04;
    private static final int STATUS_UNDERZERO = 0x05;
    private static final int STATUS_OVERLIMIT = 0x06;
    private static final int STATUS_NEEDCAL = 0x07;
    private static final int STATUS_NEEDZERO = 0x08;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.launcher);
        mWeight = (TextView) findViewById(R.id.text_weight);
        mStatus = (TextView) findViewById(R.id.text_status);

        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
    }

    @Override
    public void onResume() {
        super.onResume();

        //Obtain a WakeLock so the screen is on while using the application
        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG);
        mWakeLock.acquire();
        
        Intent intent = getIntent();
        String action = intent.getAction();
        //Check if this was launched from a device attached intent, and
        //obtain the device instance if it was
        UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            setDevice(device);
        } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
            if (mDevice != null && mDevice.equals(device)) {
                setDevice(null);
            }
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if(mWakeLock != null) mWakeLock.release();
    }
    
    /*
     * Methods to update the UI with the weight value read
     * from the USB scale.
     */
    
    private void setWeightColor(int status) {
        switch(status) {
        case STATUS_FAULT:
        case STATUS_UNDERZERO:
            mWeight.setTextColor(Color.RED);
            break;
        case STATUS_STABLE:
        case STATUS_STABLEZERO:
            mWeight.setTextColor(Color.GREEN);
            break;
        default:
            mWeight.setTextColor(Color.WHITE);
            break;
        }
    }
    
    // Value is the number of oz. times 100
    private static final int POUNDS_PER_OZ = 160; // 16lb per 0.1oz
    private void updateWeightPounds(int weight) {
        String result;

        if (weight >= POUNDS_PER_OZ) {
            float pounds = weight / POUNDS_PER_OZ;
            float remainder = (weight % POUNDS_PER_OZ) / 10.0f;
            result = String.format("%.0f lb, %.1f oz", pounds, remainder);
        } else {
            float ounces = weight / 10.0f;
            result = String.format("%.1f oz", ounces);
        }

        mWeight.setText(result);
    }

    // Value is the number of grams
    private static final int GRAMS_PER_KG = 1000;
    private void updateWeightGrams(int weight) {
        String result;

        if (weight >= GRAMS_PER_KG) {
            float kg = weight / GRAMS_PER_KG;
            float remainder = (weight % GRAMS_PER_KG);
            result = String.format("%.0f kg, %.1f g", kg, remainder);
        } else {
            float grams = weight;
            result = String.format("%.0f g", grams);
        }

        mWeight.setText(result);
    }

    /*
     * Enumerate the endpoints and interfaces on the connected
     * device to find the Interrupt endpoint we need to poll
     * for the scale data.
     */
    private void setDevice(UsbDevice device) {
        Log.d(TAG, "setDevice " + device);
        if (device.getInterfaceCount() != 1) {
            Log.e(TAG, "could not find interface");
            return;
        }
        UsbInterface intf = device.getInterface(0);
        // device should have one endpoint
        if (intf.getEndpointCount() != 1) {
            Log.e(TAG, "could not find endpoint");
            return;
        }
        // endpoint should be of type interrupt
        UsbEndpoint ep = intf.getEndpoint(0);
        if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
            Log.e(TAG, "endpoint is not interrupt type");
            return;
        }
        mDevice = device;
        mEndpointIntr = ep;
        if (device != null) {
            UsbDeviceConnection connection = mUsbManager.openDevice(device);
            if (connection != null && connection.claimInterface(intf, true)) {
                //Start the polling thread
                Toast.makeText(this, "open SUCCESS", Toast.LENGTH_SHORT).show();
                mConnection = connection;
                Thread thread = new Thread(null, this, "ScaleMonitor");
                thread.start();
            } else {
                Toast.makeText(this, "open FAIL", Toast.LENGTH_SHORT).show();
                mConnection = null;
            }
        }
    }

    private static final int MSG_STATUS = 100;
    private static final int MSG_DATA = 101;
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_STATUS:
                if (msg.obj != null) {
                    String status = (String) msg.obj;
                    mStatus.setText(status);
                }
                break;
            case MSG_DATA:
                byte[] data = (byte[]) msg.obj;
                //Always 0x03 for a data report
                byte reportId = data[0];

                //Maps to a status constant defined for HID POS
                byte status = data[1];
                setWeightColor(status);
                
                //Maps to a units constant defined for HID POS
                int units = (data[2] & 0xFF);

                //Scaling applied to the weight value, if any
                byte scaling = data[3];
                
                //Two byte value representing the weight itself
                int weight = (data[5] & 0xFF) << 8;
                weight += (data[4] & 0xFF);

                switch (units) {
                case UNITS_GRAM:
                    updateWeightGrams(weight);
                    break;
                case UNITS_OUNCE:
                    updateWeightPounds(weight);
                    break;
                default:
                    mWeight.setText("---");
                    break;
                }
                break;
            default:
                break;
            }
        }
    };

    public void postStatusMessage(String message) {
        mHandler.sendMessage(Message.obtain(mHandler, MSG_STATUS, message));
    }

    public void postWeightData(byte[] data) {
        mHandler.sendMessage(Message.obtain(mHandler, MSG_DATA, data));
    }

    private void getStatusReport(UsbDeviceConnection connection) {
        int requestType = 0xA1; // 1010 0001b
        int request = 0x01; //HID GET_REPORT
        int value = 0x0101; //Input report, ID = 1
        int index = 0; //Interface 0
        int length = 3;

        byte[] buffer = new byte[length];
        connection.controlTransfer(requestType, request, value, index, buffer, length, 2000);
    }

    private void setZeroScale(UsbDeviceConnection connection) {
        int requestType = 0x21; // 0010 0001b
        int request = 0x09; //HID SET_REPORT
        int value = 0x0302; //Feature report, ID = 2
        int index = 0; //Interface 0
        int length = 2;

        byte[] buffer = new byte[length];
        //TODO: Fill buffer with control data
        connection.controlTransfer(requestType, request, value, index, buffer, length, 2000);
    }

    /*
     * Runnable block to poll the interrupt endpoint on the scale.
     * This will return a Scale Data Report, which we pass on to
     * the Handler for parsing.
     */
    @Override
    public void run() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        UsbRequest request = new UsbRequest();
        request.initialize(mConnection, mEndpointIntr);
        while (true) {
            // queue a request on the interrupt endpoint
            request.queue(buffer, 8);
            // wait for new data
            if (mConnection.requestWait() == request) {
                byte[] raw = new byte[6];
                buffer.get(raw);
                buffer.clear();

                String data = "";
                for (int i = 0; i < raw.length; i++) {
                    data += String.format("%02x", raw[i] & 0xFF).toUpperCase();
                    data += " ";
                }
                postStatusMessage("Raw Data = " + data);

                postWeightData(raw);

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
            } else {
                Log.e(TAG, "requestWait failed, exiting");
                break;
            }
        }
    }
}