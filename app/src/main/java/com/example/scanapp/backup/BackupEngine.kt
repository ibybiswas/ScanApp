package com.example.scanapp.backup

import android.content.Context
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupEngine {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val PREFS_NAME = "backup_rotation_prefs"
    private const val KEY_LAST_MSG_ID = "last_msg_id"

    fun createBackup(context: Context, outputFile: File, password: String?) {
        val tempZip = File(context.cacheDir, "backup_tmp.zip")
        if (tempZip.exists()) tempZip.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->
            val dbFile = context.getDatabasePath("scanapp.db")
            if (dbFile.exists()) {
                zos.putNextEntry(ZipEntry("scanapp.db"))
                dbFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            val scansDir = File(context.filesDir, "scans")
            if (scansDir.exists()) {
                scansDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val relativePath = file.relativeTo(context.filesDir).path
                        zos.putNextEntry(ZipEntry(relativePath))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }

        if (!password.isNullOrEmpty()) {
            encryptFile(tempZip, outputFile, password)
            tempZip.delete()
        } else {
            tempZip.renameTo(outputFile)
        }
    }

    fun restoreBackup(context: Context, backupFile: File, password: String?) {
        val tempZip = File(context.cacheDir, "restore_tmp.zip")
        if (tempZip.exists()) tempZip.delete()

        if (!password.isNullOrEmpty()) {
            decryptFile(backupFile, tempZip, password)
        } else {
            backupFile.copyTo(tempZip, overwrite = true)
        }

        File(context.filesDir, "scans").deleteRecursively()

        ZipInputStream(BufferedInputStream(FileInputStream(tempZip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val destFile = File(context.filesDir, entry.name)
                if (entry.name == "scanapp.db") {
                    val dbTarget = context.getDatabasePath("scanapp.db")
                    dbTarget.parentFile?.mkdirs()
                    dbTarget.outputStream().use { zis.copyTo(it) }
                } else {
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        tempZip.delete()
    }

    private fun encryptFile(inputFile: File, outputFile: File, password: CharSequence) {
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(16).also { random.nextBytes(it) }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val spec = PBEKeySpec(password.toString().toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, ALGORITHM)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        }

        FileOutputStream(outputFile).use { fos ->
            fos.write(salt)
            fos.write(iv)
            CipherOutputStream(fos, cipher).use { cos -> inputFile.inputStream().use { it.copyTo(cos) } }
        }
    }

    private fun decryptFile(inputFile: File, outputFile: File, password: CharSequence) {
        FileInputStream(inputFile).use { fis ->
            val salt = ByteArray(16).also { fis.read(it) }
            val iv = ByteArray(16).also { fis.read(it) }

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(password.toString().toCharArray(), salt, ITERATIONS, KEY_LENGTH)
            val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, ALGORITHM)

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            }

            CipherInputStream(fis, cipher).use { cis -> outputFile.outputStream().use { cis.copyTo(it) } }
        }
    }

    /**
     * Uploads the file to the channel, extracts the new message ID, and deletes the older backup message if it exists.
     */
    fun uploadToTelegramAndRotate(context: Context, token: String, chatId: String, file: File) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastMsgId = prefs.getString(KEY_LAST_MSG_ID, null)

        val boundary = "===ScanAppBoundary==="
        val LINE_FEED = "\r\n"
        val url = URL("https://api.telegram.org/bot$token/sendDocument")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            useCaches = false
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            requestMethod = "POST"
        }

        connection.outputStream.use { output ->
            PrintWriter(OutputStreamWriter(output, "UTF-8"), true).use { writer ->
                writer.append("--$boundary").append(LINE_FEED)
                writer.append("Content-Disposition: form-data; name=\"chat_id\"").append(LINE_FEED)
                writer.append(LINE_FEED).append(chatId).append(LINE_FEED)

                writer.append("--$boundary").append(LINE_FEED)
                writer.append("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"").append(LINE_FEED)
                writer.append("Content-Type: application/octet-stream").append(LINE_FEED)
                writer.append(LINE_FEED).flush()

                file.inputStream().use { it.copyTo(output) }
                output.flush()

                writer.append(LINE_FEED)
                writer.append("--$boundary--").append(LINE_FEED).flush()
            }
        }

        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_OK) {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            
            // Basic manual JSON parsing of message_id to avoid heavy library overheads
            val msgIdMarker = "\"message_id\":"
            if (response.contains(msgIdMarker)) {
                val start = response.indexOf(msgIdMarker) + msgIdMarker.length
                var end = start
                while (end < response.length && (response[end].isDigit() || response[end] == ' ')) {
                    end++
                }
                val newMsgId = response.substring(start, end).trim()
                
                // Store the new message identifier node
                prefs.edit().putString(KEY_LAST_MSG_ID, newMsgId).apply()

                // Delete the old backup if it exists to maintain standard rotation limits
                if (!lastMsgId.isNullOrBlank()) {
                    try {
                        deleteTelegramMessage(token, chatId, lastMsgId)
                    } catch (e: Exception) {
                        // Log failure but don't break current deployment updates
                        e.printStackTrace()
                    }
                }
            }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw IOException("Telegram Server Error (HTTP $status): $error")
        }
    }

    private fun deleteTelegramMessage(token: String, chatId: String, messageId: String) {
        val url = URL("https://api.telegram.org/bot$token/deleteMessage?chat_id=$chatId&message_id=$messageId")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Failed to clean up message node: ${connection.responseCode}")
        }
    }
}
