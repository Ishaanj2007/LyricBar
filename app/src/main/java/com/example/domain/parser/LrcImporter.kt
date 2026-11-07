package com.example.domain.parser

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

sealed class LrcImportException(override val message: String, override val cause: Throwable? = null) : IllegalArgumentException(message, cause) {
    class FileTooLarge(val currentSize: Long = 0L, val maxSize: Long = LrcImporter.MAX_LRC_FILE_SIZE_BYTES) :
        LrcImportException("File is too large. Maximum allowed size is 1 MB")

    class EmptyFile(message: String = "Lyric file is empty") :
        LrcImportException(message)

    class UnsupportedFormat(message: String = "Unsupported file format. Please select an .lrc file") :
        LrcImportException(message)

    class UnsupportedEncoding(message: String = "Unsupported or unreadable text encoding", cause: Throwable? = null) :
        LrcImportException(message, cause)

    class FileAccessFailed(message: String = "Unable to access or open the selected file", cause: Throwable? = null) :
        LrcImportException(message, cause)

    class PermissionDenied(message: String = "File access permission denied", cause: Throwable? = null) :
        LrcImportException(message, cause)

    class StorageFailed(message: String = "Failed to save imported lyrics", cause: Throwable? = null) :
        LrcImportException(message, cause)

    class NoLyricsFound(message: String = "No valid lyrics found in file") :
        LrcImportException(message)

    class MetadataOnly(message: String = "File contains only metadata tags with no valid lyrics") :
        LrcImportException(message)

    class InvalidTimestamp(message: String = "Invalid timestamp format in lyric file") :
        LrcImportException(message)
}

object LrcImporter {

    const val MAX_LRC_FILE_SIZE_BYTES = 1024 * 1024L // 1 MB limit

    /**
     * Reads and decodes an LRC file from a ContentResolver URI with strict size protection,
     * stream bounds checking, and encoding detection (UTF-8, UTF-8 BOM, UTF-16LE, UTF-16BE).
     */
    fun readLrcFromUri(contentResolver: ContentResolver, uri: Uri): String {
        // 1. Fast metadata check via ContentResolver query if available
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    val reportedSize = cursor.getLong(sizeIndex)
                    if (reportedSize > MAX_LRC_FILE_SIZE_BYTES) {
                        throw LrcImportException.FileTooLarge(reportedSize, MAX_LRC_FILE_SIZE_BYTES)
                    }
                }
            }
        } catch (e: LrcImportException) {
            throw e
        } catch (e: SecurityException) {
            throw LrcImportException.PermissionDenied("File access permission denied", e)
        } catch (_: Exception) {
            // Non-critical metadata query error; fall through to bounded stream reading
        }

        // 2. Open input stream with security & I/O error handling
        val inputStream: InputStream = try {
            contentResolver.openInputStream(uri)
                ?: throw LrcImportException.FileAccessFailed("Unable to access or open the selected file")
        } catch (e: SecurityException) {
            throw LrcImportException.PermissionDenied("File access permission denied", e)
        } catch (e: LrcImportException) {
            throw e
        } catch (e: Exception) {
            throw LrcImportException.FileAccessFailed("Unable to access or open the selected file", e)
        }

        return inputStream.use { stream ->
            readLrcFromStream(stream)
        }
    }

    /**
     * Bounded stream reader that enforces MAX_LRC_FILE_SIZE_BYTES, detects encodings,
     * strips BOMs, preserves Unicode combining characters (e.g. Hindi, CJK), and detects binary content.
     */
    fun readLrcFromStream(stream: InputStream, maxBytes: Long = MAX_LRC_FILE_SIZE_BYTES): String {
        val buffer = ByteArray(8192)
        val byteOutput = java.io.ByteArrayOutputStream()
        var totalBytesRead = 0L

        try {
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead == -1) break
                totalBytesRead += bytesRead
                if (totalBytesRead > maxBytes) {
                    throw LrcImportException.FileTooLarge(totalBytesRead, maxBytes)
                }
                byteOutput.write(buffer, 0, bytesRead)
            }
        } catch (e: LrcImportException) {
            throw e
        } catch (e: Exception) {
            throw LrcImportException.FileAccessFailed("Error reading lyric file: ${e.message}", e)
        }

        val rawBytes = byteOutput.toByteArray()
        if (rawBytes.isEmpty()) {
            throw LrcImportException.EmptyFile("Lyric file is empty")
        }

        val decodedText = decodeBytesSafely(rawBytes)

        if (decodedText.isBlank()) {
            throw LrcImportException.EmptyFile("Lyric file is empty")
        }

        if (decodedText.contains('\u0000')) {
            throw LrcImportException.UnsupportedFormat("Unsupported file: binary files are not supported.")
        }

        if (LrcParser.isTtml(decodedText)) {
            throw LrcImportException.UnsupportedFormat("Unsupported format: TTML is not supported. Please use LRC.")
        }

        return decodedText
    }

    /**
     * Decodes raw byte array with automatic charset and BOM detection:
     * - UTF-8 with BOM (EF BB BF)
     * - UTF-16 Big Endian with BOM (FE FF)
     * - UTF-16 Little Endian with BOM (FF FE)
     * - UTF-8 standard (strict decoder with ISO-8859-1 fallback)
     * Always strips leading \uFEFF BOM from resulting text while preserving all valid Unicode combining marks.
     */
    fun decodeBytesSafely(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // Check BOM
        val text: String = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            // UTF-8 with BOM
            String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        } else if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
        ) {
            // UTF-16 Big Endian
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        } else if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
        ) {
            // UTF-16 Little Endian
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        } else {
            // No BOM: try UTF-8 with strict error reporting to catch malformed bytes
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) {
                // If invalid UTF-8 byte sequence, try ISO-8859-1 fallback
                try {
                    String(bytes, StandardCharsets.ISO_8859_1)
                } catch (e: Exception) {
                    throw LrcImportException.UnsupportedEncoding("Unsupported or unreadable text encoding", e)
                }
            }
        }

        // Remove any residual leading BOM character
        return text.removePrefix("\uFEFF").trim()
    }
}
