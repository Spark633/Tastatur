package de.spark633.tastatur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max

/** Hintergrundbild: wird beim Auswählen verkleinert und in der App gespeichert. */
object Background {

    private const val MAX_SIZE = 1440

    fun file(context: Context) = File(context.filesDir, "hintergrund.jpg")

    /** Bild aus der Galerie übernehmen. true = erfolgreich. */
    fun import(context: Context, uri: Uri): Boolean = try {
        val cr = context.contentResolver
        // Größe ermitteln, dann passend verkleinert laden
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Bild nicht lesbar")

        // Handyfotos richtig herum drehen
        val rotation = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) { 0f }

        val scale = MAX_SIZE.toFloat() / max(bmp.width, bmp.height)
        if (rotation != 0f || scale < 1f) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0f) m.postRotate(rotation)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        file(context).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Prefs(context).bgVersion = System.currentTimeMillis()
        true
    } catch (_: Exception) {
        false
    }

    fun remove(context: Context) {
        file(context).delete()
        Prefs(context).bgVersion = 0L
    }

    fun load(context: Context): Bitmap? {
        val f = file(context)
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }
}
