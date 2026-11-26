package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * [DownloadProvider.deleteAndReportRemaining] mengembalikan path yang tetap ada setelah percobaan hapus,
 * supaya aksi "Hapus" bisa menyebutkan kegagalannya alih-alih diam. Sisa instalasi lama (uid berbeda)
 * tidak bisa dihapus oleh aplikasi dan dulu hilang tanpa pesan apa pun.
 */
@Execution(ExecutionMode.CONCURRENT)
class DownloadDeleteReportTest {

    private fun provider(): DownloadProvider {
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        return DownloadProvider(mockk(relaxed = true), mockk(relaxed = true), preferences)
    }

    private fun file(path: String, deleted: Boolean, stillThere: Boolean): UniFile =
        mockk<UniFile>(relaxed = true) {
            every { delete() } returns deleted
            every { exists() } returns stillThere
            every { filePath } returns path
        }

    @Test
    fun `berkas yang benar-benar hilang tidak dilaporkan`() {
        provider().deleteAndReportRemaining(listOf(file("/x/Y.cbz", deleted = true, stillThere = false)))
            .shouldBeEmpty()
    }

    @Test
    fun `berkas milik uid lama dilaporkan dengan pathnya`() {
        provider().deleteAndReportRemaining(listOf(file("/x/Y.cbz", deleted = false, stillThere = true))) shouldBe
            listOf("/x/Y.cbz")
    }

    @Test
    fun `kabar sukses pun tidak dipercaya kalau berkasnya masih ada`() {
        // Direktori bab yang masih berisi halaman: penyedia berkas bisa mengembalikan true
        // sementara entry-nya tetap ada, jadi keberhasilannya dicek ulang, tidak dipercaya.
        provider().deleteAndReportRemaining(listOf(file("/x/Ch1", deleted = true, stillThere = true))) shouldBe
            listOf("/x/Ch1")
    }

    @Test
    fun `hanya berkas yang tersisa yang dilaporkan, urut seperti permintaan`() {
        val gone = file("/x/A.cbz", deleted = true, stillThere = false)
        val stuck = file("/x/B.cbz", deleted = false, stillThere = true)
        provider().deleteAndReportRemaining(listOf(gone, stuck)) shouldBe listOf("/x/B.cbz")
    }
}
