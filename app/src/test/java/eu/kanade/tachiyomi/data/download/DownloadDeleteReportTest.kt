package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    private fun chapterWithLeftover(fileName: String, path: String, leftover: UniFile?): UniFile {
        val parent = mockk<UniFile>(relaxed = true)
        every { parent.findFile(any()) } returns leftover
        val chapter = mockk<UniFile>(relaxed = true)
        every { chapter.filePath } returns path
        every { chapter.name } returns fileName
        every { chapter.parentFile } returns parent
        return chapter
    }

    private fun leftover(path: String, goneOnDelete: Boolean): UniFile {
        val file = mockk<UniFile>(relaxed = true)
        every { file.filePath } returns path
        every { file.exists() } returns !goneOnDelete
        return file
    }

    @Test
    fun `sisa direktori halaman dari unduhan yang berhenti ikut terhapus`() {
        val tmp = leftover("/x/Ch1_tmp", goneOnDelete = true)
        val chapter = chapterWithLeftover("Ch1", "/x/Ch1", tmp)

        provider().deleteAndReportRemaining(listOf(chapter)).shouldBeEmpty()

        verify { tmp.delete() }
    }

    @Test
    fun `sisa arsip setengah jadi dicari dengan nama bab plus suffix`() {
        val parent = mockk<UniFile>(relaxed = true)
        val tmp = leftover("/x/Y.cbz_tmp", goneOnDelete = true)
        every { parent.findFile("Y.cbz_tmp") } returns tmp
        val archive = mockk<UniFile>(relaxed = true)
        every { archive.name } returns "Y.cbz"
        every { archive.parentFile } returns parent

        provider().deleteAndReportRemaining(listOf(archive)).shouldBeEmpty()

        verify { tmp.delete() }
    }

    @Test
    fun `sisa yang tidak bisa dihapus dilaporkan lewat pathnya`() {
        val tmp = leftover("/x/Ch2_tmp", goneOnDelete = false)
        val chapter = chapterWithLeftover("Ch2", "/x/Ch2", tmp)

        provider().deleteAndReportRemaining(listOf(chapter)) shouldBe listOf("/x/Ch2_tmp")
    }

    @Test
    fun `berkas tanpa nama atau direktori induk tidak membuat error`() {
        val root = mockk<UniFile>(relaxed = true)
        every { root.name } returns null
        every { root.parentFile } returns null

        provider().deleteAndReportRemaining(listOf(root)).shouldBeEmpty()
    }
}
