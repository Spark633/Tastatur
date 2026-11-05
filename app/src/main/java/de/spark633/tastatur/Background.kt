package de.spark633.tastatur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * Hintergrundbilder: werden beim Auswählen verkleinert und in der App gespeichert.
 * Die letzten [MAX_HISTORY] Bilder bleiben erhalten und lassen sich wieder wählen.
 */
object Background {

    private const val MAX_SIZE = 1440
    private const val MAX_HISTORY = 5

    private fun dir(context: Context) = File(context.filesDir, "hintergruende").apply { mkdirs() }

    /** Früher gab es nur eine Datei: in den Verlauf übernehmen. */
    private fun migrate(context: Context) {
        val old = File(context.filesDir, "hintergrund.jpg")
        if (!old.exists()) return
        val name = "bg_${System.currentTimeMillis()}.jpg"
        if (old.renameTo(File(dir(context), name))) {
            val prefs = Prefs(context)
            if (prefs.bgVersion != 0L) prefs.bgFile = name
        }
    }

    /** Alle gespeicherten Bilder, neueste zuerst (Dateinamen). */
    fun history(context: Context): List<String> {
        migrate(context)
        return dir(context).listFiles { f -> f.name.endsWith(".jpg") }
            ?.sortedByDescending { it.lastModified() }?.map { it.name } ?: emptyList()
    }

    fun file(context: Context, name: String) = File(dir(context), name)

    /** Bild aus der Galerie übernehmen und als Hintergrund setzen. true = erfolgreich. */
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
        val name = "bg_${System.currentTimeMillis()}.jpg"
        file(context, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        select(context, name)
        trim(context)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Bild aus einer Sicherung unter seinem alten Namen übernehmen (Profile zeigen weiter darauf).
     * Gibt es den Namen schon mit anderem Inhalt, bekommt das Bild einen neuen Namen.
     */
    fun importNamed(context: Context, name: String, data: ByteArray): String {
        var n = name
        val f = file(context, n)
        if (!Regex("bg_\\d+\\.jpg").matches(n) || (f.exists() && !f.readBytes().contentEquals(data))) {
            var t = System.currentTimeMillis()
            while (file(context, "bg_$t.jpg").exists()) t++
            n = "bg_$t.jpg"
        }
        val out = file(context, n)
        if (!out.exists()) out.writeBytes(data)
        out.setLastModified(System.currentTimeMillis())
        return n
    }

    /** Nach dem Laden einer Sicherung: Verlauf wieder auf [MAX_HISTORY] kürzen. */
    fun trimHistory(context: Context) = trim(context)

    /** Bild aus einer Sicherung übernehmen (JPEG-Daten). */
    fun importBytes(context: Context, data: ByteArray): String {
        val name = "bg_${System.currentTimeMillis()}.jpg"
        file(context, name).writeBytes(data)
        trim(context)
        return name
    }

    /** Ein gespeichertes Bild als Hintergrund setzen. */
    fun select(context: Context, name: String) {
        val prefs = Prefs(context)
        prefs.rememberImageSettings() // Werte des bisherigen Bildes sichern
        prefs.bgFile = name
        prefs.loadImageSettings(name) // eigene Werte des Bildes (Abdunkeln, Zoom, Ausschnitt)
        file(context, name).setLastModified(System.currentTimeMillis()) // nach vorne im Verlauf
        prefs.bgVersion = System.currentTimeMillis()
    }

    /** Hintergrund ausschalten; das Bild bleibt im Verlauf. */
    fun remove(context: Context) {
        val prefs = Prefs(context)
        prefs.bgFile = ""
        prefs.bgVersion = 0L
    }

    /** Bild aus dem Verlauf löschen (ist es aktiv, wird der Hintergrund ausgeschaltet). */
    fun delete(context: Context, name: String) {
        if (Prefs(context).bgFile == name) remove(context)
        file(context, name).delete()
        Prefs(context).forgetImageSettings(name)
    }

    /** Älteste Bilder löschen; das aktive und in Profilen benutzte bleiben. */
    private fun trim(context: Context) {
        val keep = Profiles(context).usedBackgrounds() + Prefs(context).bgFile
        val all = history(context)
        all.drop(MAX_HISTORY).filter { it !in keep }.forEach {
            file(context, it).delete()
            Prefs(context).forgetImageSettings(it)
        }
    }

    fun current(context: Context): File? {
        migrate(context)
        val name = Prefs(context).bgFile
        if (name.isEmpty()) return null
        return file(context, name).takeIf { it.exists() }
    }

    fun load(context: Context): Bitmap? = current(context)?.let { BitmapFactory.decodeFile(it.path) }

    /**
     * Sichtbarer Bildausschnitt: füllt die Fläche (wie "centerCrop"), mit Zoom
     * und Position (0..1 waagerecht/senkrecht).
     */
    fun sourceRect(bmpW: Int, bmpH: Int, w: Float, h: Float, zoom: Float, posX: Float, posY: Float): android.graphics.Rect {
        val scale = max(w / bmpW, h / bmpH) * zoom.coerceAtLeast(1f)
        val srcW = w / scale
        val srcH = h / scale
        val left = (bmpW - srcW) * posX.coerceIn(0f, 1f)
        val top = (bmpH - srcH) * posY.coerceIn(0f, 1f)
        return android.graphics.Rect(left.toInt(), top.toInt(), (left + srcW).toInt(), (top + srcH).toInt())
    }

    /** Kleines Vorschaubild für die Auswahl. */
    fun thumbnail(context: Context, name: String, targetPx: Int): Bitmap? {
        val f = file(context, name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
