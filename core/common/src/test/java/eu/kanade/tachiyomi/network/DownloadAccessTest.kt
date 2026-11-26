package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class DownloadAccessTest {

    @Test
    fun pesanEaccessDikenali() {
        isUnreadableFileFailure("open failed: EACCES (Permission denied)") shouldBe true
    }

    @Test
    fun pesanEpermDikenali() {
        isUnreadableFileFailure("open failed: EPERM (Operation not permitted)") shouldBe true
    }

    @Test
    fun kegagalanLainTidakDikenali() {
        isUnreadableFileFailure("Unable to resolve host api.mangadex.org") shouldBe false
        isUnreadableFileFailure("No content reader found for response type") shouldBe false
        isUnreadableFileFailure(null) shouldBe false
    }

    @Test
    fun kataEaccessDiDalamKalimatJaringanTidakDikenali() {
        // Regexnya menuntut bentuk errno Android "E<NAME> (", bukan sekadar kata kebetulan.
        isUnreadableFileFailure("failed to connect to EACCES.example.com/93.184.216.34:443") shouldBe false
    }
}
