package com.glasssign.app.ui.update

import java.io.File
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
        val update=app.packageManager.resolveContentProvider(app.packageName+".updates.fileprovider",0)!!
        val plugin=app.packageManager.resolveContentProvider(app.packageName+".plugins.fileprovider",0)!!
        assertEquals(UpdateFileProvider::class.java.name,update.name)
        assertNotEquals(plugin.name,update.name)
        assertFalse(update.exported)
        assertTrue(update.grantUriPermissions)
        val uri=UpdateIntents(app).installView(File(UpdateCache.dir(app.cacheDir),"fixture.apk")).data!!
        assertEquals(app.packageName+".updates.fileprovider",uri.authority)
        assertTrue(uri.path!!.startsWith("/app_updates/"))
    }
}
