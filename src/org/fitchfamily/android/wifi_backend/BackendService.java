package org.fitchfamily.android.wifi_backend;

/*
 *  WiFi Backend for Unified Network Location
 *  Copyright (C) 2014  Tod Fitch
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.microg.nlp.api.LocationBackendService;
import org.microg.nlp.api.LocationHelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import org.fitchfamily.android.wifi_backend.WifiReceiver.WifiReceivedCallback;

public class BackendService extends LocationBackendService {
    private static final String TAG = configuration.TAG_PREFIX + "backend-service";
    private final static int DEBUG = configuration.DEBUG;

    private static final int MIN_SIGNAL_LEVEL = -200;

    private samplerDatabase sDb;
    private WifiReceiver wifiReceiver;
    private boolean networkAllowed;
    private WiFiSamplerService collectorService;

    @Override
    protected void onOpen() {
        if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "onOpen()");

        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        configuration.fillFromPrefs(sharedPrefs);
        sharedPrefs.registerOnSharedPreferenceChangeListener(configuration.listener);

        sDb = samplerDatabase.getInstance(this);

        if (wifiReceiver == null) {
            wifiReceiver = new WifiReceiver(this, new WifiDBResolver());
        }
        registerReceiver(wifiReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        bindService(new Intent(this, WiFiSamplerService.class), mConnection, Context.BIND_AUTO_CREATE);
        if (collectorService == null) {
            if (DEBUG >= configuration.DEBUG_SPARSE) Log.d(TAG, "No collectorService?\n");
        }
    }

    @Override
    protected void onClose() {
        if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "onClose()");
        unregisterReceiver(wifiReceiver);
        unbindService(mConnection);

        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(configuration.listener);
        wifiReceiver = null;
    }

    @Override
    protected Location update() {
        if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "update()");

        if (wifiReceiver != null) {
            if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "update(): Starting scan for WiFi APs");
            wifiReceiver.startScan();
        } else {
            if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "update(): no wifiReceiver???");
        }
        return null;
    }

    private class WifiDBResolver implements WifiReceivedCallback {

        @Override
        public void process(List<Bundle> foundBssids) {

            if (foundBssids == null || foundBssids.isEmpty()) {
                return;
            }
            if (sDb != null) {

                Set<Location> locations = new HashSet<Location>(foundBssids.size());

                for (Bundle extras : foundBssids) {
                    Location result = sDb.ApLocation(extras.getString(configuration.EXTRA_MAC_ADDRESS));
                    if (result != null) {
                        result.setExtras(extras);
                        locations.add(result);
                    }
                }

                if (locations.isEmpty()) {
                    if (DEBUG >= configuration.DEBUG_SPARSE) Log.d(TAG, "WifiDBResolver.process(): No APs with known locations");
                    return;
                }

                // Find largest group of AP locations. If we don't have at
                // least two near each other then we don't have enough
                // information to get a good location.
                locations = culledAPs(locations);
                if (locations.size() < 2) {
                    if (DEBUG >= configuration.DEBUG_SPARSE) Log.d(TAG, "WifiDBResolver.process(): Insufficient number of WiFi hotspots to resolve location");
                    return;
                }

                Location avgLoc = weightedAverage("wifi", locations);

                if (avgLoc == null) {
                    Log.e(TAG, "Averaging locations did not work.");
                    return;
                }
                report(avgLoc);
            }
        }
    }

    // Stuff for binding to (basically starting) background AP location
    // collection
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className,
                IBinder binder) {
            if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "mConnection.ServiceConnection()");
            WiFiSamplerService.MyBinder b = (WiFiSamplerService.MyBinder) binder;
            collectorService = b.getService();
        }
        public void onServiceDisconnected(ComponentName className) {
            if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "mConnection.onServiceDisconnected()");
            collectorService = null;
        }
    };

    private static boolean locationCompatibleWithGroup(Location location,
                                                       Set<Location> locGroup,
                                                       double accuracy) {
        boolean result = true;
        for (Location other : locGroup) {
            double testDistance = (location.distanceTo(other) -
                                   location.getAccuracy() -
                                   other.getAccuracy());
            if (DEBUG >= configuration.DEBUG_VERBOSE)
                Log.d(TAG, "locationCompatibleWithGroup():"+
                           " To other=" + location.distanceTo(other) +
                           " this.acc=" + location.getAccuracy() +
                           " other.acc=" + other.getAccuracy() +
                           " testDist=" + testDistance);
            if (testDistance > accuracy)
                result = false;
        }
        if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "locationCompatibleWithGroup(): accuracy=" + accuracy + " result=" + result);
        return result;
    }

    private static Set<Set<Location>> divideInGroups(Collection<Location> locations,
                                                     double accuracy) {
        Set<Set<Location>> bins = new HashSet<Set<Location>>();
        for (Location location : locations) {
            boolean used = false;
            for (Set<Location> locGroup : bins) {
                if (locationCompatibleWithGroup(location, locGroup, accuracy)) {
                    locGroup.add(location);
                    used = true;
                }
            }
            if (!used) {
                if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "divideInGroups(): Creating new group");
                Set<Location> locGroup = new HashSet<Location>();
                locGroup.add(location);
                bins.add(locGroup);
            }
        }
        return bins;
    }

    //
    // The collector service attempts to detect and not report moved/moving APs.
    // But it can't be perfect. This routine looks at all the APs and returns the
    // largest subset (group) that are within a reasonable distance of one another.
    //
    // The hope is that a single moved/moving AP that is seen now but whose
    // location was detected miles away can be excluded from the set of APs
    // we use to determine where the phone is at this moment.
    //
    // We do this by creating collections of APs where all the APs in a group
    // are within a plausible distance of one another. A single AP may end up
    // in multiple groups. When done, we return the largest group.
    public Set<Location> culledAPs(Collection<Location> locations) {
        Set<Set<Location>> locationGroups = divideInGroups(locations,
                                                           configuration.apMovedThreshold);
        List<Set<Location>> clsList = new ArrayList<Set<Location>>(locationGroups);
        Collections.sort(clsList, new Comparator<Set<Location>>() {
            @Override
            public int compare(Set<Location> lhs, Set<Location> rhs) {
                return rhs.size() - lhs.size();
            }
        });

        if (DEBUG >= configuration.DEBUG_VERBOSE) {
            int i = 1;
            for (Set<Location> set : clsList) {
                Log.d(TAG, "culledAPs(): group[" + i + "] = "+set.size());
                i++;
            }
        }
        if (!clsList.isEmpty())
            return clsList.get(0);
        else
            return null;
    }

    private int getSignalLevel(Location location) {
        return Math.abs(location.getExtras().getInt(configuration.EXTRA_SIGNAL_LEVEL) -
                MIN_SIGNAL_LEVEL);
    }

    // estimated range is based on the signal level and estimated coverage radius
    // of the AP. Basically get a linear percentage (display type) version of the
    // received signal strength and multiply that times the range. Could all be
    // done in one expression but want to make it clear.
    private double estRange(Location location) {
        int dBm = location.getExtras().getInt(configuration.EXTRA_SIGNAL_LEVEL);
        double sigPercent = WifiManager.calculateSignalLevel(dBm, 100)/100.0;
        double apRange = Math.min(location.getAccuracy(), configuration.apAssumedAccuracy);
        return sigPercent * apRange;
    }

    public Location weightedAverage(String source, Collection<Location> locations) {
        Location rslt = null;

        if (locations == null || locations.size() == 0) {
            return null;
        }
        int num = locations.size();
        double totalWeight = 0;
        double latitude = 0;
        double longitude = 0;
        double accuracy = 0;
        double altitudeWeight = 0;
        double altitude = 0;

        for (Location value : locations) {
            if (value != null) {
                String bssid = value.getExtras().getString(configuration.EXTRA_MAC_ADDRESS);

                // We weight our average based on the estimated range to
                // each AP with closer APs being given higher weighting.
                double estRng = estRange(value);
                double wgt = 1/estRng;
                if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG,
                                String.format("Using with weight=%f mac=%s signal=%d accuracy=%f " +
                                "estRng=%f latitude=%f longitude=%f",
                                wgt, bssid,
                                value.getExtras().getInt(configuration.EXTRA_SIGNAL_LEVEL),
                                value.getAccuracy(), estRng, value.getLatitude(),
                                value.getLongitude()));

                latitude += (value.getLatitude() * wgt);
                longitude += (value.getLongitude() * wgt);
                accuracy += (value.getAccuracy() * wgt);
                if (value.hasAltitude()) {
                    altitude += value.getAltitude() * wgt;
                    altitudeWeight += wgt;
                }
                totalWeight += wgt;
            }
        }
        latitude = latitude / totalWeight;
        longitude = longitude / totalWeight;
        accuracy = accuracy / totalWeight;
        altitude = altitude / totalWeight;

        Bundle extras = new Bundle();
        extras.putInt("AVERAGED_OF", num);
        if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG, "Location est (lat="+ latitude + ", lng=" + longitude + ", acc=" + accuracy);
        if (altitudeWeight > 0) {
            rslt = LocationHelper.create(source,
                          latitude,
                          longitude ,
                          altitude,
                          (float)accuracy,
                          extras);
        } else {
            rslt = LocationHelper.create(source,
                          latitude,
                          longitude,
                          (float)accuracy,
                          extras);
        }

        rslt.setAccuracy(guessAccuracy( rslt, locations));
        rslt.setTime(System.currentTimeMillis());
        if (DEBUG >= configuration.DEBUG_SPARSE) Log.d(TAG, rslt.toString());
        return rslt;
    }

    // The geometry of overlapping circles is hard to compute but overlapping
    // boxes are easy. So we will see pretend each AP has a square coverage area
    // and then find the smallest box covered by all the APs. That should bound
    // our probable location.
    //
    // If our database is wrong about coverage radius values then we could end up
    // with disjoint boxes. In that case we will bail out and simply return the
    // weighted average accuracy measurement done when we computed the location.
    public float guessAccuracy( Location rslt, Collection<Location> locations) {
        double maxX = rslt.getAccuracy();
        double minX = -maxX;
        double maxY = maxX;
        double minY = -maxY;
        if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG,
                                                       String.format("guessAccuracy(): maxX=%f minX=%s maxY=%f minY=%f ",
                                                       maxX, minX, maxY, minY));
        for (Location value : locations) {
            final double rng = rslt.distanceTo(value);
            final double brg = rslt.bearingTo(value);
            final double rad = Math.toRadians(brg);
            final double dx = Math.cos(rad) * rng;
            final double dy = Math.sin(rad) * rng;
            final double r = value.getAccuracy();
            if (r < rng) {
                if (DEBUG >= configuration.DEBUG_SPARSE) Log.d(TAG, "guessAccuracy(): distance("+rng+") greater than coverage("+r+")");
                return rslt.getAccuracy();
            }

            if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG,
                                                           String.format("guessAccuracy(): Bearing=%f Range=%s dx=%f dy=%f radius=%f",
                                                           brg, rng, dx, dy, r));
            maxX = Math.min(maxX, (dx+r));
            minX = Math.max(minX, (dx-r));
            maxY = Math.min(maxY, (dy+r));
            minY = Math.max(minY, (dy-r));
            if (DEBUG >= configuration.DEBUG_VERBOSE) Log.d(TAG,
                                                           String.format("guessAccuracy(): maxX=%f minX=%s maxY=%f minY=%f ",
                                                           maxX, minX, maxY, minY));
        }
        double guess = Math.max(Math.abs(maxX),Math.abs(minX));
        guess = Math.max(guess,Math.abs(maxY));
        guess = Math.max(guess,Math.abs(minY));
        if (DEBUG >= configuration.DEBUG_SPARSE) Log.d(TAG, "revised accuracy from " + rslt.getAccuracy() + " to " + guess);
        return (float)guess;
    }
}
