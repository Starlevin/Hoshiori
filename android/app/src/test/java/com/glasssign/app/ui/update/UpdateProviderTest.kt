package com.glasssign.app.ui.update

import android.content.pm.PackageManager
import com.glasssign.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateProviderTest {
    @Test fun updatesAndPluginsHaveDistinctProviderComponents() {
        val app=RuntimeEnvironment.getApplication()
        val update=app.packageManager.resolveContentProvider(app.packageName+".updates.fileprovider",PackageManager.GET_META_DATA)!!
        val plugin=app.packageManager.resolveContentProvider(app.packageName+".plugins.fileprovider",0)!!
        assertEquals(UpdateFileProvider::class.java.name,update.name)
        assertNotEquals(plugin.name,update.name)
        assertFalse(update.exported)
        assertTrue(update.grantUriPermissions)
        assertEquals(R.xml.update_download_paths,update.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
    }
}
