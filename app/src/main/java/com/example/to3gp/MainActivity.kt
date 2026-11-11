package com.example.to3gp

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var convertBtn: Button
    private lateinit var sizeGroup: RadioGroup

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoUri = uri
        status.text = if (uri != null) "ভিডিও নির্বাচিত হয়েছে ✔" else "কোনো ভিডিও নির্বাচন করা হয়নি"
        convertBtn.isEnabled = uri != null
    }

    private fun rootCause(t: Throwable): String {
        var r: Throwable = t
        while (r.cause != null && r.cause !== r) r = r.cause!!
        return r.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try { crashFile.writeText(rootCause(e) + "\n\n" + e.stackTraceToString()) } catch (_: Throwable) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply { text = "3GP কনভার্টার"; textSize = 24f }
        val pickBtn = Button(this).apply { text = "ভিডিও বেছে নিন (MP4, MKV...)" }

        sizeGroup = RadioGroup(this).apply {
            addView(RadioButton(this@MainActivity).apply { text = "176×144 (সাধারণ কিপ্যাড)"; id = 1; isChecked = true })
            addView(RadioButton(this@MainActivity).apply { text = "352×288 (একটু ভালো কোয়ালিটি)"; id = 2 })
        }

        convertBtn = Button(this).apply { text = "3GP-তে কনভার্ট করুন"; isEnabled = false }
        status = TextView(this).apply { text = "শুরু করতে ভিডিও বেছে নিন"; setPadding(0, pad, 0, 0) }

        listOf(title, pickBtn, sizeGroup, convertBtn, status).forEach { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })

        if (crashFile.exists()) {
            status.textSize = 11f
            status.text = "আগের ক্র্যাশের ত্রুটি:\n" + crashFile.readText().take(1200)
            crashFile.delete()
        }

        pickBtn.setOnClickListener { picker.launch("video/*") }
        convertBtn.setOnClickListener { convert() }
    }

    private fun convert() {
        val uri = videoUri ?: return
        val big = sizeGroup.checkedRadioButtonId == 2
        val w = if (big) 352 else 176
        val h = if (big) 288 else 144
        val vb = if (big) "300k" else "128k"

        convertBtn.isEnabled = false
        status.textSize = 14f
        status.text = "ভিডিও কপি হচ্ছে... ⏳"

        Thread {
            val stamp = System.currentTimeMillis()
            val tmpIn = File(cacheDir, "in_$stamp.mp4")
            val out = File(cacheDir, "out_$stamp.3gp")
            try {
                contentResolver.openInputStream(uri)!!.use { i ->
                    tmpIn.outputStream().use { o -> i.copyTo(o) }
                }
                runOnUiThread { status.text = "কনভার্ট হচ্ছে... অপেক্ষা করুন ⏳" }

                fun cmd(audio: String) =
                    "-y -i \"${tmpIn.absolutePath}\" " +
                    "-vf scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2 " +
                    "-r 15 -c:v h263 -b:v $vb $audio \"${out.absolutePath}\""

                val amr = "-c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k"
                val aac = "-c:a aac -ar 16000 -ac 1 -b:a 32k"

                var s = FFmpegKit.execute(cmd(amr))
                if (!ReturnCode.isSuccess(s.returnCode)) s = FFmpegKit.execute(cmd(aac))

                if (ReturnCode.isSuccess(s.returnCode)) {
                    saveResult(out)
                } else {
                    val log = (s.output ?: "").takeLast(900)
                    runOnUiThread {
                        status.textSize = 11f
                        status.text = "কনভার্ট ব্যর্থ ❌\n$log"
                        convertBtn.isEnabled = true
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.textSize = 12f
                    status.text = "ত্রুটি:\n" + rootCause(t).take(700)
                    convertBtn.isEnabled = true
                }
            } finally {
                tmpIn.delete()
            }
        }.start()
    }

    private fun saveResult(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "video_${System.currentTimeMillis()}.3gp")
                put(MediaStore.Downloads.MIME_TYPE, "video/3gpp")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/3GP")
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            dest?.let { u -> contentResolver.openOutputStream(u)?.use { o -> file.inputStream().use { it.copyTo(o) } } }
            file.delete()
            runOnUiThread {
                status.textSize = 14f
                status.text = "সম্পন্ন ✅ Download/3GP ফোল্ডারে সেভ হয়েছে"
                convertBtn.isEnabled = true
            }
        } catch (t: Throwable) {
            runOnUiThread {
                status.textSize = 12f
                status.text = "সেভ করতে ত্রুটি:\n" + rootCause(t).take(700)
                convertBtn.isEnabled = true
            }
        }
    }
}
