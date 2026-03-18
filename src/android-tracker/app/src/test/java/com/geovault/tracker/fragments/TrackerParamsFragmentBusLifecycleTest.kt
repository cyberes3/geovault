package com.geovault.tracker.fragments

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.geovault.tracker.pipeline.TrackPointBus
import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TrackerParamsFragmentBusLifecycleTest {
    @After
    fun tearDown() {
        TrackPointBus.resetForTests()
    }

    class BusCollectorFragment : Fragment() {
        var trackerId: String = ""
        var streamCollectionJob: Job? = null
        val seen = mutableListOf<Pair<Double, Double>>()

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            return FrameLayout(requireContext())
        }

        override fun onStart() {
            super.onStart()
            if (streamCollectionJob?.isActive == true) return
            streamCollectionJob = viewLifecycleOwner.lifecycleScope.launch {
                TrackPointBus.events.collect { event ->
                    if (event.trackId != trackerId) return@collect
                    seen.add(event.lat to event.lon)
                }
            }
        }

        override fun onStop() {
            super.onStop()
            streamCollectionJob?.cancel()
            streamCollectionJob = null
        }
    }

    @Test
    fun busReplay_updatesAfterStopResume_withoutEmulator() {
        val trackerId = "params-lifecycle-${System.nanoTime()}"
        val fragment = BusCollectorFragment().apply { this.trackerId = trackerId }

        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller.get()
        val containerId = View.generateViewId()
        activity.setContentView(FrameLayout(activity).apply { id = containerId })
        activity.supportFragmentManager.beginTransaction()
            .replace(containerId, fragment)
            .commitNow()
        assertNotNull(fragment.view)

        val first = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackerId,
            lon = 20.123456,
            lat = 10.654321,
            timestampMs = System.currentTimeMillis()
        )
        TrackPointBus.publish(first)
        drainMainLooperUntil {
            fragment.seen.contains(10.654321 to 20.123456)
        }
        assertTrue(fragment.seen.contains(10.654321 to 20.123456))

        controller.pause().stop()
        val whileStopped = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = trackerId,
            lon = 40.333333,
            lat = 30.222222,
            timestampMs = System.currentTimeMillis() + 1000L
        )
        TrackPointBus.publish(whileStopped)

        controller.start().resume().visible()
        drainMainLooperUntil {
            fragment.seen.contains(30.222222 to 40.333333)
        }
        assertTrue(fragment.seen.contains(30.222222 to 40.333333))
    }

    private fun drainMainLooperUntil(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
        }
    }
}
