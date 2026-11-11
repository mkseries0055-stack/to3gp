package com.example.to3gp

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val primary = Color.parseColor("#4F46E5")
    private val accent = Color.parseColor("#06B6D4")
    private val bgColor = Color.parseColor("#F3F5FB")
    private val textDark = Color.parseColor("#1F2937")
    private val textGray = Color.parseColor("#6B7280")
    private val green = Color.parseColor("#15803D")
    private val red = Color.parseColor("#B91C1C")

    private var videoUri: Uri? = null
    private lateinit var fileLabel: TextView
    private lateinit var status: TextView
    private lateinit var convertBtn: Button
    private lateinit var sizeGroup: RadioGroup
    private lateinit var progressBox: LinearLayout
    private lateinit var percentText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var timeText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

    private fun rootCause(t: Throwable): String {
        var r: Throwable = t
        while (r.cause != null && r.cause !== r) r = r.cause!!
        return r.toString()
    }

    private fun fileName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "ভিডিও"
        } catch (_: Throwable) { "ভিডিও" }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun setConvertEnabled(on: Boolean) {
        convertBtn.isEnabled = on
        convertBtn.alpha = if (on) 1f else 0.45f
    }

    private fun showStatus(text: String, color: Int, size: Float = 14f) {
        status.text = text
        status.setTextColor(color)
        status.textSize = size
    }

    private fun updateProgress(doneMs: Long, totalMs: Long) {
        val frac = if (totalMs > 0) (doneMs.toDouble() / totalMs).coerceIn(0.0, 1.0) else 0.0
        percentText.text = String.format(Locale.US, "%.1f%%", frac * 100)
        bar.progress = (frac * 1000).toInt()
        timeText.text = fmt(doneMs) + " / " + fmt(totalMs)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        videoUri = uri
        if (uri != null) {
            fileLabel.text = "🎬  " + fileName(uri)
            fileLabel.setTextColor(textDark)
            progressBox.visibility = View.GONE
            showStatus("প্রস্তুত! এবার কনভার্ট করুন", textGray)
        }
        setConvertEnabled(uri != null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = primary

        val crashFile = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try { crashFile.writeText(rootCause(e) + "\n\n" + e.stackTraceToString()) } catch (_: Throwable) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(56))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(primary, accent)
            )
        }
        header.addView(TextView(this).apply {
            text = "📱 3GP কনভার্টার"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = "পুরোনো কিপ্যাড ফোনের জন্য ভিডিও রূপান্তর"
            textSize = 14f
            setTextColor(Color.parseColor("#E0F2FE"))
            setPadding(0, dp(6), 0, 0)
        })
        page.addView(header)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = shape(Color.WHITE, 20)
            elevation = dp(6).toFloat()
        }
        val cardParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), -dp(32), dp(16), dp(16)) }

        val pickBtn = Button(this).apply {
            text = "📂  ভিডিও বেছে নিন"
            isAllCaps = false
            textSize = 16f
            setTextColor(primary)
            background = shape(Color.WHITE, 14, primary)
            stateListAnimator = null
        }
        card.addView(pickBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))

        fileLabel = TextView(this).apply {
            text = "কোনো ভিডিও বাছা হয়নি (MP4, MKV...)"
            textSize = 14f
            setTextColor(textGray)
            setPadding(dp(4), dp(12), dp(4), dp(8))
        }
        card.addView(fileLabel)

        card.addView(TextView(this).apply {
            text = "কোয়ালিটি বেছে নিন"
            textSize = 13f
            setTextColor(textGray)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(8), 0, dp(4))
        })

        sizeGroup = RadioGroup(this)
        fun option(label: String, optId: Int, checked: Boolean) = RadioButton(this).apply {
            text = label
            id = optId
            isChecked = checked
            textSize = 15f
            setTextColor(textDark)
            buttonTintList = ColorStateList.valueOf(primary)
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        sizeGroup.addView(option("176×144 — দ্রুত, সাধারণ কিপ্যাড", 1, true))
        sizeGroup.addView(option("352×288 — একটু ভালো, ধীর", 2, false))
        card.addView(sizeGroup)

        convertBtn = Button(this).apply {
            text = "🚀  3GP-তে কনভার্ট করুন"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(primary, accent)
            ).apply { cornerRadius = dp(14).toFloat() }
            stateListAnimator = null
        }
        setConvertEnabled(false)
        card.addView(convertBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(16) })

        // প্রগ্রেস অংশ
        progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(0, dp(20), 0, 0)
        }
        percentText = TextView(this).apply {
            text = "0.0%"
            textSize = 32f
            setTextColor(primary)
            typeface = Typeface.DEFAULT_BOLD
        }
        progressBox.addView(percentText)
        progressBox.addView(TextView(this).apply {
            text = "সম্পন্ন"
            textSize = 13f
            setTextColor(textGray)
        })
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#E5E7EB"))
        }
        progressBox.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(12) })
        timeText = TextView(this).apply {
            text = "00:00:00 / 00:00:00"
            textSize = 13f
            setTextColor(primary)
            setPadding(0, dp(8), 0, 0)
        }
        progressBox.addView(timeText)
        card.addView(progressBox)

        status = TextView(this).apply {
            text = "শুরু করতে একটি ভিডিও বেছে নিন"
            textSize = 14f
            setTextColor(textGray)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(14), dp(4), 0)
        }
        card.addView(status)

        page.addView(card, cardParams)

        page.addView(TextView(this).apply {
            text = "ফাইল সেভ হবে: Download/3GP ফোল্ডারে"
            textSize = 12f
            setTextColor(textGray)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(4), dp(16), dp(24))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
            addView(page)
        })

        if (crashFile.exists()) {
            showStatus("আগের ক্র্যাশের ত্রুটি:\n" + crashFile.readText().take(1200), red, 11f)
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

        setConvertEnabled(false)
        progressBox.visibility = View.GONE
        showStatus("ভিডিও কপি হচ্ছে... ⏳", textGray)

        Thread {
            val stamp = System.currentTimeMillis()
            val tmpIn = File(cacheDir, "in_$stamp.mp4")
            val out = File(cacheDir, "out_$stamp.3gp")
            try {
                contentResolver.openInputStream(uri)!!.use { i ->
                    tmpIn.outputStream().use { o -> i.copyTo(o) }
                }

                val total: Long = try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(tmpIn.absolutePath)
                    val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    r.release()
                    d
                } catch (_: Throwable) { 0L }

                runOnUiThread {
                    showStatus("কনভার্ট হচ্ছে... অপেক্ষা করুন", textGray)
                    updateProgress(0, total)
                    progressBox.visibility = View.VISIBLE
                }

                FFmpegKitConfig.enableStatisticsCallback { st ->
                    val t = st.time.toLong()
                    runOnUiThread { updateProgress(t, total) }
                }

                fun cmd(audio: String) =
                    "-y -i \"${tmpIn.absolutePath}\" " +
                    "-vf scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2 " +
                    "-r 15 -c:v h263 -b:v $vb $audio \"${out.absolutePath}\""

                val amr = "-c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k"
                val aac = "-c:a aac -ar 16000 -ac 1 -b:a 32k"

                var s = FFmpegKit.execute(cmd(amr))
                if (!ReturnCode.isSuccess(s.returnCode)) s = FFmpegKit.execute(cmd(aac))

                if (ReturnCode.isSuccess(s.returnCode)) {
                    runOnUiThread { updateProgress(total, total) }
                    saveResult(out)
                } else {
                    val log = (s.output ?: "").takeLast(900)
                    runOnUiThread {
                        progressBox.visibility = View.GONE
                        showStatus("কনভার্ট ব্যর্থ ❌\n$log", red, 11f)
                        setConvertEnabled(true)
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    progressBox.visibility = View.GONE
                    showStatus("ত্রুটি:\n" + rootCause(t).take(700), red, 12f)
                    setConvertEnabled(true)
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
                showStatus("✅ সম্পন্ন!\nDownload/3GP ফোল্ডারে সেভ হয়েছে", green, 16f)
                setConvertEnabled(true)
            }
        } catch (t: Throwable) {
            runOnUiThread {
                progressBox.visibility = View.GONE
                showStatus("সেভ করতে ত্রুটি:\n" + rootCause(t).take(700), red, 12f)
                setConvertEnabled(true)
            }
        }
    }
}
