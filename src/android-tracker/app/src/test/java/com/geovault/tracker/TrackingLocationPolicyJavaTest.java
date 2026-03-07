package com.geovault.tracker;

import android.location.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import kotlin.Pair;

import static org.junit.Assert.*;

/**
 * Java unit tests for TrackingLocationPolicy to ensure interoperability
 * and coverage of new tracking features.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TrackingLocationPolicyJavaTest {

    private Location createLocation(double lat, double lon, long time, Float accuracy, Float speed) {
        return createLocation(lat, lon, time, accuracy, speed, null);
    }

    private Location createLocation(double lat, double lon, long time, Float accuracy, Float speed, Double altitude) {
        Location loc = new Location("gps");
        loc.setLatitude(lat);
        loc.setLongitude(lon);
        loc.setTime(time);
        if (accuracy != null)
            loc.setAccuracy(accuracy);
        if (speed != null)
            loc.setSpeed(speed);
        if (altitude != null) {
            loc.setAltitude(altitude);
        }
        return loc;
    }

    @Test
    public void testJumpFilteringFromJava() {
        Location a = createLocation(0.0, 0.0, 0, null, null);
        Location b = createLocation(1.0, 1.0, 1000, null, null); // 1.0 deg in 1s is massive

        assertTrue("Should detect a jump for unrealistic speed", TrackingLocationPolicy.isJump(a, b));
    }

    @Test
    public void testSmoothingFromJava() {
        Location a = createLocation(0.0, 0.0, 0, null, null);
        Location b = createLocation(1.0, 1.0, 1000, null, null);

        Location smoothed = TrackingLocationPolicy.smooth(a, b, 0.5f);
        assertEquals(0.5, smoothed.getLatitude(), 0.000001);
        assertEquals(0.5, smoothed.getLongitude(), 0.000001);
    }

    @Test
    public void testSpeedAwareStationaryFromJava() {
        Location a = createLocation(0.0, 0.0, 0, null, 0f);
        Location b = createLocation(0.00001, 0.00001, 15000, null, 10f); // Fast speed but tiny distance

        // stationaryUpdate(last, current, distanceFilter, currentConsecutive,
        // significantMotionOnly)
        Pair<Integer, Boolean> result = TrackingLocationPolicy.stationaryUpdate(a, b, 10f, 1, true);

        assertEquals("Stationary count should reset to 0 because of speed", 0, (int) result.getFirst());
        assertFalse("Should not pause if moving fast", result.getSecond());
    }

    @Test
    public void testAccuracyFilterFromJava() {
        Location loc = createLocation(0, 0, 0, 80f, null);
        assertFalse("Should discard if accuracy > filter", TrackingLocationPolicy.acceptByAccuracy(loc, 50f));
        assertTrue("Should accept if accuracy <= filter", TrackingLocationPolicy.acceptByAccuracy(loc, 100f));

        Location noAcc = new Location("gps");
        assertTrue("Should accept if no accuracy is present", TrackingLocationPolicy.acceptByAccuracy(noAcc, 50f));
    }

    @Test
    public void testSmoothWithAltitude() {
        Location a = createLocation(0.0, 0.0, 0, null, null, 100.0);
        Location b = createLocation(1.0, 1.0, 1000, null, null, 200.0);

        Location smoothed = TrackingLocationPolicy.smooth(a, b, 0.5f);
        assertEquals(0.5, smoothed.getLatitude(), 0.000001);
        assertEquals(0.5, smoothed.getLongitude(), 0.000001);
        assertEquals(150.0, smoothed.getAltitude(), 0.000001);
    }

    @Test
    public void testStationaryDetailed() {
        Location a = createLocation(0.0, 0.0, 0, null, 0f);

        // Case 1: significantMotionOnly = false
        Pair<Integer, Boolean> res1 = TrackingLocationPolicy.stationaryUpdate(a, a, 10f, 0, false);
        assertEquals(0, (int) res1.getFirst());
        assertFalse(res1.getSecond());

        // Case 2: reset counter if moved
        Location moved = createLocation(0.1, 0.1, 1000, null, 0f);
        Pair<Integer, Boolean> res2 = TrackingLocationPolicy.stationaryUpdate(a, moved, 10f, 2, true);
        assertEquals("Reset counter because distance exceeded", 0, (int) res2.getFirst());

        // Case 3: increment and pause
        Pair<Integer, Boolean> res3 = TrackingLocationPolicy.stationaryUpdate(a, a, 10f, 0, true);
        assertEquals(1, (int) res3.getFirst());
        Pair<Integer, Boolean> res4 = TrackingLocationPolicy.stationaryUpdate(a, a, 10f, 1, true);
        assertEquals(2, (int) res4.getFirst());
        Pair<Integer, Boolean> res5 = TrackingLocationPolicy.stationaryUpdate(a, a, 10f, 2, true);
        assertEquals(3, (int) res5.getFirst());
        assertTrue("Should pause after 3 stationary points", res5.getSecond());
    }

    @Test
    public void testLocationRequestIntervalFromSec() {
        Pair<Long, Long> result = TrackingLocationPolicy.locationRequestIntervalFromSec(60L);
        assertEquals(60000L, (long) result.getFirst());
        assertEquals(30000L, (long) result.getSecond());
    }

    @Test
    public void testIsJumpEdgeCases() {
        Location a = createLocation(0.0, 0.0, 1000, null, null);
        Location b = createLocation(0.0, 0.0, 500, null, null); // Time went backwards

        assertFalse("Should not be jump if time diff <= 0", TrackingLocationPolicy.isJump(a, b));
        assertFalse("Should not be jump if last location is null", TrackingLocationPolicy.isJump(null, a));
    }
}
