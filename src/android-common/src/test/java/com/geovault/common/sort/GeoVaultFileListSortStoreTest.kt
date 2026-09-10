package com.geovault.common.sort

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.common.settings.FileListSortDocument
import com.geovault.common.settings.FileListSortV1ToV2Migration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    @Test
    fun v1DocumentMigratesNamedFieldsToScopeKeys() {
        val migrated = FileListSortV1ToV2Migration.migrate(
            buildJsonObject {
                put("dataFilesSort", GeoVaultFileListSortMode.MODIFIED_NEWEST.name)
                put("coordinateSystemsSort", GeoVaultFileListSortMode.NAME_Z_TO_A.name)
            },
        )
        val modes = migrated["modesByScope"]?.jsonObject
        requireNotNull(modes)
        assertEquals(
            GeoVaultFileListSortMode.MODIFIED_NEWEST.name,
            modes[FileListSortDocument.SCOPE_DATA_FILES]?.jsonPrimitive?.content,
        )
        assertEquals(
            GeoVaultFileListSortMode.NAME_Z_TO_A.name,
            modes[FileListSortDocument.SCOPE_COORDINATE_SYSTEMS]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun stringScopeKeysRoundTrip() {
        store.putBlocking("data_files", GeoVaultFileListSortMode.MODIFIED_OLDEST)
        assertEquals(
            GeoVaultFileListSortMode.MODIFIED_OLDEST,
            store.getBlocking("data_files"),
        )
    }
}
