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
@Config(sdk = 28)
public class TrackingLocationPolicyJavaTest {

    private Location createLocation(double lat, double lon, long time, Float accuracy, Float speed) {
        Location loc = new Location("gps");
        loc.setLatitude(lat);
        loc.setLongitude(lon);
        loc.setTime(time);
        if (accuracy != null)
            loc.setAccuracy(accuracy);
        if (speed != null)
            loc.setSpeed(speed);
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
    }
}
