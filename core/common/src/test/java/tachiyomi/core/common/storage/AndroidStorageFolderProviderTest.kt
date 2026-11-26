package tachiyomi.core.common.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File
import java.nio.file.Files

/**
 * Scoped storage (API 30+) menolak folder milik aplikasi sendiri di storage publik: membuat direktori
 * masih boleh, membuat file ditolak MediaProvider dengan EPERM. Karena itu folder publik hanya boleh
 * dipakai kalau benar-benar bisa ditulisi; kalau tidak, default jatuh ke dir eksternal privat yang
 * selalu bisa tanpa izin.
 */
@Execution(ExecutionMode.CONCURRENT)
class AndroidStorageFolderProviderTest {

    @Test
    fun folderPublikDipakaiSaatBerhasilDibuat() {
        val root = Files.createTempDirectory("yomu-storage").toFile()
        val publik = File(root, "Mihon")
        val privat = File(root, "private")

        pickBaseDir(publik, privat) shouldBe publik
    }

    @Test
    fun folderPublikYangSudahAdaDanBisaDitulisTetapDipakai() {
        val publik = Files.createTempDirectory("yomu-storage-publik").toFile()
        val privat = File(Files.createTempDirectory("yomu-storage-privat").toFile(), "Mihon")

        pickBaseDir(publik, privat) shouldBe publik
    }

    /**
     * Sisa instalasi lama dengan uid berbeda, atau Android 11+ tanpa all-files access: folder publik
     * tidak bisa dibuat atau tidak bisa ditulisi.
     */
    @Test
    fun folderPrivatDipakaiSaatFolderPublikTidakBisaDibuat() {
        val penghalang = Files.createTempFile("yomu-storage", ".txt").toFile()
        val publik = File(penghalang, "Mihon")
        val privat = File(Files.createTempDirectory("yomu-storage-privat").toFile(), "Mihon")

        pickBaseDir(publik, privat) shouldBe privat
    }
}
