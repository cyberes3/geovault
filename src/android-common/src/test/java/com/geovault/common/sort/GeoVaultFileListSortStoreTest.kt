package com.geovault.common.sort

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultFileListSortStoreTest {
    private lateinit var store: GeoVaultFileListSortStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = GeoVaultFileListSortStore.getInstance(context)
        store.putBlocking(GeoVaultFileListSortScope.DATA_FILES, GeoVaultFileListSortMode.DEFAULT)
        store.putBlocking(
            GeoVaultFileListSortScope.COORDINATE_SYSTEMS,
            GeoVaultFileListSortMode.DEFAULT,
        )
    }

    @Test
    fun `scopes persist independently`() {
        store.putBlocking(GeoVaultFileListSortScope.DATA_FILES, GeoVaultFileListSortMode.MODIFIED_NEWEST)
        store.putBlocking(
            GeoVaultFileListSortScope.COORDINATE_SYSTEMS,
            GeoVaultFileListSortMode.NAME_Z_TO_A,
        )
        assertEquals(GeoVaultFileListSortMode.MODIFIED_NEWEST, store.getBlocking(GeoVaultFileListSortScope.DATA_FILES))
        assertEquals(
            GeoVaultFileListSortMode.NAME_Z_TO_A,
            store.getBlocking(GeoVaultFileListSortScope.COORDINATE_SYSTEMS),
        )
    }

    @Test
    fun `observe emits stored mode`() = runBlocking {
        store.put(GeoVaultFileListSortScope.DATA_FILES, GeoVaultFileListSortMode.MODIFIED_OLDEST)
        assertEquals(
            GeoVaultFileListSortMode.MODIFIED_OLDEST,
            store.observe(GeoVaultFileListSortScope.DATA_FILES).first(),
        )
    }

    @Test
    fun `fromStored falls back to default for unknown value`() {
        assertEquals(GeoVaultFileListSortMode.DEFAULT, GeoVaultFileListSortMode.fromStored("not_a_mode"))
    }

    @Test
    fun `DEFAULT is file name A to Z`() {
        assertEquals(GeoVaultFileListSortMode.NAME_A_TO_Z, GeoVaultFileListSortMode.DEFAULT)
    }

    @Test
    fun `getBlocking returns default for both scopes`() {
        assertEquals(GeoVaultFileListSortMode.DEFAULT, store.getBlocking(GeoVaultFileListSortScope.DATA_FILES))
        assertEquals(
            GeoVaultFileListSortMode.DEFAULT,
            store.getBlocking(GeoVaultFileListSortScope.COORDINATE_SYSTEMS),
        )
    }
}
