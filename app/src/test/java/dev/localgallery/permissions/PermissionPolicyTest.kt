package dev.localgallery.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun api29Through32RequireLegacyStoragePermission() {
        listOf(29, 30, 31, 32).forEach { api ->
            assertEquals(PhotoAccess.FULL, PermissionPolicy.evaluate(api, true, false, false))
            assertEquals(PhotoAccess.DENIED, PermissionPolicy.evaluate(api, false, true, true))
        }
    }

    @Test
    fun api33RequiresFullImagePermissionAndIgnoresSelectedFlag() {
        assertEquals(PhotoAccess.FULL, PermissionPolicy.evaluate(33, false, true, false))
        assertEquals(PhotoAccess.FULL, PermissionPolicy.evaluate(33, false, true, true))
        assertEquals(PhotoAccess.DENIED, PermissionPolicy.evaluate(33, false, false, true))
        assertEquals(PhotoAccess.DENIED, PermissionPolicy.evaluate(33, false, false, false))
    }

    @Test
    fun api34AndLaterDistinguishFullPartialAndDenied() {
        listOf(34, 35, 36).forEach { api ->
            assertEquals(PhotoAccess.FULL, PermissionPolicy.evaluate(api, false, true, false))
            assertEquals(PhotoAccess.FULL, PermissionPolicy.evaluate(api, false, true, true))
            assertEquals(PhotoAccess.PARTIAL, PermissionPolicy.evaluate(api, false, false, true))
            assertEquals(PhotoAccess.DENIED, PermissionPolicy.evaluate(api, false, false, false))
        }
    }
}
