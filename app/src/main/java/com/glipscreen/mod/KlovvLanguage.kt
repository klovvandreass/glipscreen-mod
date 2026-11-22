package com.glipscreen.mod

import android.content.Context

object KlovvLanguage {
    private val id = mapOf(
        "Screen recording made simple" to "Perekaman layar jadi lebih mudah",
        "SCREEN RECORDER" to "PEREKAM LAYAR",
        "Ready to capture" to "Siap merekam",
        "Record gameplay, tutorials, or anything on your screen." to "Rekam gameplay, tutorial, atau apa pun di layar.",
        "●  Ready to record" to "●  Siap merekam",
        "Start recording" to "Mulai merekam",
        "Stop / cancel" to "Hentikan / batalkan",
        "Countdown cancelled" to "Hitung mundur dibatalkan",
        "Stopping recording…" to "Menghentikan rekaman…",
        "Quick settings" to "Pengaturan cepat",
        "Your preferred recording setup." to "Atur preferensi rekamanmu.",
        "Resolution" to "Resolusi",
        "Frame rate" to "Kecepatan frame",
        "Video quality" to "Kualitas video",
        "Video codec" to "Codec video",
        "My recordings" to "Rekaman saya",
        "View and manage saved videos" to "Lihat dan kelola video tersimpan",
        "Open" to "Buka",
        "Simple controls. Clear recordings." to "Kontrol sederhana. Rekaman tersusun rapi.",
        "Settings" to "Pengaturan",
        "Keep your recording setup organized." to "Atur preferensi rekaman dengan mudah.",
        "VIDEO" to "VIDEO",
        "Recording quality and performance" to "Kualitas dan performa rekaman",
        "Choose the capture settings you want to use." to "Pilih pengaturan perekaman yang ingin digunakan.",
        "Start countdown" to "Hitung mundur mulai",
        "No countdown" to "Tanpa hitung mundur",
        "3 seconds" to "3 detik",
        "5 seconds" to "5 detik",
        "10 seconds" to "10 detik",
        "AUDIO" to "AUDIO",
        "Choose what should be captured." to "Pilih sumber audio yang ingin direkam.",
        "Internal audio" to "Audio internal",
        "Microphone" to "Mikrofon",
        "Internal + microphone" to "Audio internal + mikrofon",
        "GENERAL" to "UMUM",
        "A clean and straightforward screen recorder." to "Aplikasi perekam layar yang simpel dan mudah digunakan.",
        "Language" to "Bahasa",
        "Bahasa Indonesia" to "Bahasa Indonesia",
        "English" to "English",
        "My recordings" to "Rekaman saya",
        "Your recordings, organized in one place." to "Semua rekamanmu tersimpan di satu tempat.",
        "VIDEO LIBRARY" to "GALERI VIDEO",
        "saved recording" to "rekaman tersimpan",
        "saved recordings" to "rekaman tersimpan",
        "Saved in Movies/Klovv Screen" to "Tersimpan di Movies/Klovv Screen",
        "BROWSE RECORDINGS" to "LIHAT REKAMAN",
        "About" to "Tentang",
        "Built to make screen recording feel effortless." to "Dibuat agar merekam layar terasa lebih mudah.",
        "A focused screen recorder with a clean interface and practical controls." to "Perekam layar dengan tampilan bersih dan kontrol yang praktis.",
        "Home" to "Beranda",
        "Videos" to "Video",
        "Cancel" to "Batal",
        "OK" to "OK",
        "Close" to "Tutup",
        "CLOSE" to "TUTUP",
        "MY RECORDINGS" to "REKAMAN SAYA",
        "Belum ada video Klovv Screen. Hasil rekaman tersimpan di Movies/Klovv Screen." to "Belum ada video Klovv Screen. Hasil rekaman tersimpan di Movies/Klovv Screen.",
        "Play video" to "Putar video",
        "Share video" to "Bagikan video",
        "Video details" to "Detail video",
        "Rename video" to "Ganti nama video",
        "Delete video" to "Hapus video",
        "No video player found" to "Pemutar video tidak ditemukan",
        "Share recording" to "Bagikan rekaman",
        "VIDEO DETAILS" to "DETAIL VIDEO",
        "Not available" to "Tidak tersedia",
        "Video name" to "Nama video",
        "Rename recording" to "Ganti nama rekaman",
        "CANCEL" to "BATAL",
        "SAVE" to "SIMPAN",
        "Recording renamed" to "Nama rekaman diubah",
        "Recording was not renamed" to "Nama rekaman gagal diubah",
        "Could not rename recording" to "Tidak dapat mengganti nama rekaman",
        "Delete this recording?" to "Hapus rekaman ini?",
        "DELETE" to "HAPUS",
        "Recording deleted" to "Rekaman dihapus",
        "Recording was not deleted" to "Rekaman gagal dihapus",
        "Could not delete recording" to "Tidak dapat menghapus rekaman",
        "Floating controls unavailable; notification controls will remain available" to "Kontrol mengambang tidak tersedia; kontrol notifikasi tetap bisa digunakan",
        "Could not open screen-capture permission" to "Tidak dapat membuka izin perekaman layar",
        "Audio permission not granted" to "Izin audio tidak diberikan",
        "Could not start recording service" to "Tidak dapat memulai layanan perekaman",
        "Recording starting…" to "Memulai rekaman…",
        "Screen-capture permission cancelled" to "Izin perekaman layar dibatalkan",
        "Audio permission not granted" to "Izin audio tidak diberikan",
        "Recording starts in " to "Rekaman dimulai dalam ",
        " seconds • tap STOP / CANCEL to cancel" to " detik • tekan HENTIKAN / BATALKAN untuk membatalkan",
        "Auto (recommended)" to "Otomatis (disarankan)",
        "Standard (smaller file)" to "Standar (file lebih kecil)",
        "High quality" to "Kualitas tinggi",
        "Maximum quality" to "Kualitas maksimum",
        "H.264 (most compatible)" to "H.264 (paling kompatibel)",
        "H.265 / HEVC (smaller file, if supported)" to "H.265 / HEVC (file lebih kecil, jika didukung)",
        "Recording paused" to "Rekaman dijeda",
        "Recording • elapsed time" to "Merekam • durasi berjalan",
        "Resume" to "Lanjutkan",
        "Pause" to "Jeda",
        "Save & Stop" to "Simpan & hentikan",
        "PAUSE" to "JEDA",
        "RESUME" to "LANJUTKAN",
        "SAVE & STOP" to "SIMPAN & HENTIKAN",
        "KLOVV SCREEN • RECORDING" to "KLOVV SCREEN • MEREKAM"
    )

    fun translate(context: Context, value: String): String {
        val language = context.getSharedPreferences("glip_settings", Context.MODE_PRIVATE)
            .getString("language", "id") ?: "id"
        if (language != "id") return value
        id[value]?.let { return it }
        if (value.startsWith("Recording starts in ")) {
            return "Rekaman dimulai dalam " + value.removePrefix("Recording starts in ")
                .replace(" seconds • tap STOP / CANCEL to cancel", " detik • tekan HENTIKAN / BATALKAN untuk membatalkan")
        }
        if (value.matches(Regex("\\d+ saved recording(s)?"))) {
            val count = value.substringBefore(" ").toIntOrNull() ?: 0
            return "$count rekaman tersimpan"
        }
        if (value.startsWith("Resolution: ")) return value.replace("Resolution:", "Resolusi:")
            .replace("Frame rate:", "Kecepatan frame:").replace("Not available", "Tidak tersedia")
            .replace("Bitrate:", "Bitrate:")
        return value
    }
}
