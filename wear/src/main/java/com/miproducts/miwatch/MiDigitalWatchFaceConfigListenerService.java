package com.miproducts.miwatch;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;
import com.miproducts.miwatch.utilities.Consts;
import com.miproducts.miwatch.utilities.SettingsManager;

/**
 * Created by larry on 7/2/15.
 */
public class MiDigitalWatchFaceConfigListenerService extends WearableListenerService
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {

private static final String TAG = "DigitalListenerService";

    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "onConnected: " + bundle);

    }


    @Override
    public void onConnectionSuspended(int cause) {
        Log.d(TAG, "onConnectionSuspended: " + cause);

    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.d(TAG, "onConnectionFailed: " + result);

    }
    private static final String WEARABLE_DATA_PATH = "/wearable_data";
    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        log("onDataChanged");
        SettingsManager sm = new SettingsManager(getApplicationContext());

        DataMap dataMap;

        try{
            for (DataEvent event : dataEventBuffer) {
                dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

                Log.d("Grab for Watch", "DataMap received on watch: " + dataMap);

                if(dataMap.getInt(Consts.KEY_BROADCAST_DEGREE,0) != 0){
                    log("value was not 0");
                    // save the new temperature
                    sm.writeToPreferences(Consts.DEGREE_REFRESH, dataMap.getInt(Consts.KEY_BROADCAST_DEGREE, 0));

                    // tell degrees to update if available (otherwise update next time we come
                    // back) - tell WatchFaceService Really who will inform degrees
                    Intent intentTellDegreeToRefresh = new Intent(Consts.BROADCAST_DEGREE);
                    intentTellDegreeToRefresh.putExtra(Consts.KEY_BROADCAST_DEGREE, 0);
                    sendBroadcast(intentTellDegreeToRefresh);
                }else {
                    log("value came back as 0");
                }


            }
        }finally {
            dataEventBuffer.close();
        }

    }


    private void updateCount(int y) {
        log("grabbed this update: " + y);
    }


    public void log(String s) {
        Log.d("Listener", s);
    }


}
