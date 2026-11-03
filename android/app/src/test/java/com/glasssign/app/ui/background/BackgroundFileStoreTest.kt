package com.glasssign.app.ui.background

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Private-copy write, cleanup and failure recovery, with no image decoder involved.
 *
 * A full decode needs Android, so the stored bytes here are only a valid header plus filler. That is enough
 * for what this class owns: the atomic two-stage write, the size cap, the "this is not an image" rejection,
 * and the two failure modes that would otherwise leave the page blank forever (an interrupted write and a
 * truncated target). Directories are asserted with [listNames] because `assertEquals(listOf(), directory.listFiles())`
 * would compare a List against an Array and fail for the wrong reason.
 */
class BackgroundFileStoreTest {
    @get:Rule val temp = TemporaryFolder()

    /** Valid JPEG header plus filler: 16 bytes, long enough for the 12-byte sniff to decide on content. */
    private fun jpeg(vararg tail: Byte): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(12) + tail

    private fun listNames(directory: File): List<String> = directory.list()?.sorted().orEmpty()

    /** A fresh directory per call: `TemporaryFolder.newFolder("files")` returns the same child when a name repeats. */
    private fun newDirectory(): File = temp.newFolder("files-" + System.nanoTime())

    @Test fun savedImageSurvivesASecondStoreInstance() {
        val directory = newDirectory()
        val bytes = jpeg(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val length = BackgroundFileStore(directory).save(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(bytes.size.toLong(), length)
        val reopened = BackgroundFileStore(directory)
        assertTrue(reopened.isValid())
        assertArrayEquals(bytes, reopened.customFile.readBytes())
        assertEquals(listOf("page.img"), listNames(directory))
    }

    @Test fun nonImageBytesAreRejectedAndNothingIsKept() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val text = "this is a note, not a wallpaper or an image".toByteArray()
        val failure = runCatching { store.save(ByteArrayInputStream(text), text.size.toLong()) }.exceptionOrNull()
        assertTrue(failure is BackgroundError)
        assertFalse("a rejected write must not keep the image", store.customFile.exists())
        assertEquals(emptyList<String>(), listNames(directory))
    }

    @Test fun oversizedStreamIsCutOffAtTheCapWhileCopying() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val bytes = jpeg() + ByteArray(BackgroundLimits.maxSourceBytes.toInt() + 64)
        val failure = runCatching { store.save(ByteArrayInputStream(bytes), bytes.size.toLong()) }
        assertTrue(failure.exceptionOrNull() is BackgroundError)
        assertFalse("a rejected write must not keep the image", store.customFile.exists())
        assertEquals(emptyList<String>(), listNames(directory))
    }

    @Test fun declaredSizeIsOnlyAHint() {
        val store = BackgroundFileStore(temp.newFolder("files"))
        val bytes = jpeg(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        // A provider that reports -1 (stream) must still import; the cap is enforced on bytes written.
        assertEquals(bytes.size.toLong(), store.save(ByteArrayInputStream(bytes), -1L))
        assertTrue(store.isValid())
    }

    @Test fun unreadableStreamReportsACopyErrorNotACrash() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("grant released")
        }
        val failure = runCatching { store.save(broken, 1024) }.exceptionOrNull()
        assertTrue(failure is BackgroundError)
        assertFalse("a rejected write must not keep the image", store.customFile.exists())
        assertEquals(emptyList<String>(), listNames(directory))
    }

    @Test fun interruptedWriteIsCleanedUpAndTheOldImageKept() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val first = jpeg(1, 2, 3, 4, 5, 6, 7, 8)
        store.save(ByteArrayInputStream(first), first.size.toLong())
        // Simulate a process killed mid-copy: a stray temp file with junk next to the good image.
        File(directory, "page.tmp").writeBytes(byteArrayOf(0, 0, 0))
        assertEquals(listOf("page.img", "page.tmp"), listNames(directory))
        assertTrue(store.recover())
        assertEquals("the previous background must survive", first.size, store.customFile.length().toInt())
        assertEquals(listOf("page.img"), listNames(directory))
    }

    @Test fun truncatedStoredImageIsDroppedAndReportsNoBackground() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val bytes = jpeg(1, 2, 3, 4, 5, 6, 7, 8)
        store.save(ByteArrayInputStream(bytes), bytes.size.toLong())
        store.customFile.writeBytes(bytes.copyOf(4))
        assertFalse("a 4-byte header is not a valid stored image", store.isValid())
        assertFalse("recovery says the preference must fall back", store.recover())
        assertFalse("recovery must delete the broken copy", store.customFile.exists())
        assertEquals(emptyList<String>(), listNames(directory))
    }

    @Test fun clearDeletesBothTheCopyAndAnyTemp() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val bytes = jpeg(1, 2, 3, 4)
        store.save(ByteArrayInputStream(bytes), bytes.size.toLong())
        File(directory, "page.tmp").writeBytes(byteArrayOf(1))
        store.delete()
        assertEquals(emptyList<String>(), listNames(directory))
    }

    @Test fun headerSniffAcceptsRealFormatsRejectsOthers() {
        val jpegHeader = jpeg()
        assertTrue(BackgroundFileStore.isImageHeader(jpegHeader, jpegHeader.size))
        assertTrue(BackgroundFileStore.isImageHeader(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8), 16))
        assertTrue(BackgroundFileStore.isImageHeader("RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray(), 16))
        assertTrue(BackgroundFileStore.isImageHeader("GIF89a".toByteArray() + ByteArray(10), 16))
        assertTrue(BackgroundFileStore.isImageHeader("BM".toByteArray() + ByteArray(14), 16))
        assertTrue(BackgroundFileStore.isImageHeader(byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(8), 16))
        // A short text file is rejected on length, a longer one on content.
        assertFalse(BackgroundFileStore.isImageHeader("plain text note".toByteArray(), 15))
        assertFalse(BackgroundFileStore.isImageHeader("plain text note that is long".toByteArray(), 27))
        assertFalse(BackgroundFileStore.isImageHeader(ByteArray(32), 0))
    }

    @Test fun storageStaysInsideTheBackgroundDirectory() {
        val files = newDirectory()
        val store = BackgroundFileStore.inFilesDir(files)
        val bytes = jpeg(1, 2, 3)
        store.save(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(File(files, "background"), store.customFile.parentFile)
        assertEquals(listOf("page.img"), listNames(store.customFile.parentFile))
    }

    @Test fun stagingNeverTouchesTheImageInUse() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val kept = jpeg(1, 2, 3, 4, 5, 6, 7, 8)
        store.save(ByteArrayInputStream(kept), kept.size.toLong())
        val staged = store.stage(ByteArrayInputStream(jpeg(9, 9, 9)), -1)
        assertTrue("the staged file is a temp copy", staged.name.endsWith(".tmp"))
        assertTrue("the live image is untouched until commit", kept.contentEquals(store.customFile.readBytes()))
        staged.delete()
    }

    @Test fun anAbandonedStageIsDiscardedWithoutTouchingTheImage() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val kept = jpeg(1, 2, 3, 4, 5, 6, 7, 8)
        store.save(ByteArrayInputStream(kept), kept.size.toLong())
        store.stage(ByteArrayInputStream(jpeg(7, 7)), -1)
        store.deleteStaged()
        assertTrue("a rejected import keeps the previous wallpaper", kept.contentEquals(store.customFile.readBytes()))
        assertEquals(listOf("page.img"), listNames(directory))
    }

    @Test fun commitRefusesAnEmptyOrMissingStage() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val kept = jpeg(1, 2, 3, 4, 5, 6, 7, 8)
        store.save(ByteArrayInputStream(kept), kept.size.toLong())
        val empty = File(directory, "page.tmp").apply { writeBytes(ByteArray(0)) }
        val failure = runCatching { store.commit(empty) }.exceptionOrNull()
        assertTrue("an unvalidated stage must be refused", failure is BackgroundError)
        assertTrue(kept.contentEquals(store.customFile.readBytes()))
    }

    /**
     * A valid stage replaces the image. On a filesystem where `renameTo` refuses an existing target the
     * commit fails loudly instead of deleting the live file and copying after (a failed copy would then
     * leave no wallpaper at all), so the old image must still be intact in that case.
     */
    @Test fun aValidStageIsPromotedOrTheOldImageIsKept() {
        val directory = newDirectory()
        val store = BackgroundFileStore(directory)
        val kept = jpeg(1, 2, 3)
        store.save(ByteArrayInputStream(kept), 3L)
        val replacement = jpeg(4, 5, 6, 7, 8, 9, 10, 11)
        val staged = store.stage(ByteArrayInputStream(replacement), replacement.size.toLong())
        val failure = runCatching { store.commit(staged) }.exceptionOrNull()
        val current = store.customFile.readBytes()
        assertTrue(
            "either the new image is in place or the old one survived",
            current.contentEquals(replacement) || current.contentEquals(kept),
        )
        if (failure != null) {
            assertTrue("a failed promotion reports a copy error", failure is BackgroundError)
            assertTrue("a failed promotion keeps the old image", kept.contentEquals(current))
        }
    }
}





