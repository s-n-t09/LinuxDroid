package io.linuxdroid.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsPathPolicyTest {
    @Test
    fun acceptsAlpineEtcMtabLink() {
        assertTrue(RootfsPathPolicy.isSafeRelativeSymlink("etc/mtab", "../proc/mounts"))
    }

    @Test
    fun acceptsNestedAlpineCrontabLink() {
        assertTrue(RootfsPathPolicy.isSafeRelativeSymlink("var/spool/cron/crontabs", "../../../etc/crontabs"))
    }

    @Test
    fun acceptsSiblingLink() {
        assertTrue(RootfsPathPolicy.isSafeRelativeSymlink("usr/share/apk/keys/aarch64/key", "../key"))
    }

    @Test
    fun rejectsLinkThatEscapesRootfs() {
        assertFalse(RootfsPathPolicy.isSafeRelativeSymlink("etc/mtab", "../../host-file"))
    }

    @Test
    fun rejectsDeepTraversalThatEscapesRootfs() {
        assertFalse(RootfsPathPolicy.isSafeRelativeSymlink("usr/bin/tool", "../../../../host-file"))
    }
}
