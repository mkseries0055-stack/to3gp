package com.example.to3gp

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
    private val green = Color.parseColor("#16A34A")
    private val red = Color.parseColor("#DC2626")
    private val orange = Color.parseColor("#F59E0B")
    private val track = Color.parseColor("#D1D5DB")

    private class Row(val uri: Uri, val name: String, val bar: ProgressBar, val label: TextView)

    private var rows: List<Row> = emptyList()

    @Volatile private var cancelled = false
    @Volatile private var curTotal = 0L
    @Volatile private var curRow: Row? = null
    @Volatile private var lastError = ""

    private lateinit var pickBtn: Button
    private lateinit var status: TextView
    private lateinit var convertBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var sizeGroup: RadioGroup
    private lateinit var listCard: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var listBox: LinearLayout

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
            } ?: "video"
        } catch (_: Throwable) { "video" }
    }

    private fun baseName(name: String): String {
        val n = name.substringBeforeLast('.', name)
        val clean = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80)
        return if (clean.isEmpty()) "video" else clean
    }

    private fun setButtons(busy: Boolean) {
        val canConvert = !busy && rows.isNotEmpty()
        convertBtn.isEnabled = canConvert
        convertBtn.alpha = if (canConvert) 1f else 0.45f
        pickBtn.isEnabled = !busy
        pickBtn.alpha = if (busy) 0.45f else 1f
        cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
        cancelBtn.isEnabled = true
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showStatus(text: String, color: Int, size: Float = 14f) {
        status.text = text
        status.setTextColor(color)
        status.textSize = size
    }

    private fun setRow(r: Row, progress: Int, color: Int, text: String) {
        r.bar.progress = progress
        r.bar.progressTintList = ColorStateList.valueOf(color)
        r.label.text = text
        r.label.setTextColor(color)
    }

    private fun setWaiting(r: Row, text: String = "অপেক্ষায়") {
        r.bar.progress = 0
        r.label.text = text
        r.label.setTextColor(textGray)
    }

    private fun loadThumb(uri: Uri, iv: ImageView) {
        Thread {
            var bmp: Bitmap? = null
            try { bmp = contentResolver.loadThumbnail(uri, Size(160, 160), null) } catch (_: Throwable) {}
            if (bmp == null) {
                try {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(this, uri)
                    bmp = r.getFrameAtTime(1_000_000) ?: r.getFrameAtTime(0)
                    r.release()
                } catch (_: Throwable) {}
            }
            val b = bmp
            if (b != null) runOnUiThread { iv.setImageBitmap(b) }
        }.start()
    }

    private fun makeRow(uri: Uri, name: String): Pair<View, Row> {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val thumbBox = FrameLayout(this).apply {
            background = shape(Color.parseColor("#E5E7EB"), 10)
            clipToOutline = true
        }
        thumbBox.addView(TextView(this).apply {
            text = "🎬"
            textSize = 22f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumbBox.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        line.addView(thumbBox, LinearLayout.LayoutParams(dp(56), dp(56)))
        loadThumb(uri, iv)

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        right.addView(TextView(this).apply {
            text = name
            textSize = 13f
            setTextColor(textDark)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })

        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(red)
            progressBackgroundTintList = ColorStateList.valueOf(track)
        }
        barRow.addView(bar, LinearLayout.LayoutParams(0, dp(6), 1f))
        val label = TextView(this).apply {
            text = "অপেক্ষায়"
            textSize = 12f
            setTextColor(textGray)
            gravity = Gravity.END
            minWidth = dp(70)
            setPadding(dp(8), 0, 0, 0)
        }
        barRow.addView(label)
        right.addView(barRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        line.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return Pair(line, Row(uri, name, bar, label))
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            listBox.removeAllViews()
            val built = ArrayList<Row>()
            for (u in uris) {
                val (view, row) = makeRow(u, fileName(u))
                listBox.addView(view)
                built.add(row)
            }
            rows = built
            listTitle.text = "📋 ভিডিও তালিকা (${built.size} টি)"
            listCard.visibility = View.VISIBLE
            showStatus("প্রস্তুত! এবার কনভার্ট করুন", textGray)
        }
        setButtons(false)
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
            setPadding(dp(20), dp(28), dp(20), dp(44))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(primary, accent)
            )
        }
        header.addView(TextView(this).apply {
            text = "📱 3GP কনভার্টার"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = "পুরোনো কিপ্যাড ফোনের জন্য ভিডিও রূপান্তর"
            textSize = 13f
            setTextColor(Color.parseColor("#E0F2FE"))
            setPadding(0, dp(4), 0, 0)
        })
        page.addView(header)

        // ছোট মূল বক্স
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            background = shape(Color.WHITE, 18)
            elevation = dp(6).toFloat()
        }
        page.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), -dp(28), dp(16), dp(12)) })

        pickBtn = Button(this).apply {
            text = "📂  ভিডিও বেছে নিন (একাধিক)"
            isAllCaps = false
            textSize = 15f
            setTextColor(primary)
            background = shape(Color.WHITE, 12, primary)
            stateListAnimator = null
        }
        card.addView(pickBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

        sizeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        fun option(label: String, optId: Int, checked: Boolean) = RadioButton(this).apply {
            text = label
            id = optId
            isChecked = checked
            textSize = 14f
            setTextColor(textDark)
            buttonTintList = ColorStateList.valueOf(primary)
        }
        sizeGroup.addView(option("176×144 দ্রুত", 1, true),
            RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        sizeGroup.addView(option("352×288 ভালো", 2, false),
            RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(sizeGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        convertBtn = Button(this).apply {
            text = "🚀  কনভার্ট করুন"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(primary, accent)
            ).apply { cornerRadius = dp(12).toFloat() }
            stateListAnimator = null
        }
        btnRow.addView(convertBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        cancelBtn = Button(this).apply {
            text = "⛔  বাতিল"
            isAllCaps = false
            textSize = 15f
            setTextColor(red)
            background = shape(Color.WHITE, 12, red)
            stateListAnimator = null
            visibility = View.GONE
        }
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(48), 0.7f).apply { leftMargin = dp(10) })
        card.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        status = TextView(this).apply {
            text = "শুরু করতে এক বা একাধিক ভিডিও বেছে নিন"
            textSize = 14f
            setTextColor(textGray)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        card.addView(status)

        // ভিডিও তালিকা
        listCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            background = shape(Color.WHITE, 18)
            elevation = dp(3).toFloat()
            visibility = View.GONE
        }
        listTitle = TextView(this).apply {
            text = "📋 ভিডিও তালিকা"
            textSize = 15f
            setTextColor(textDark)
            typeface = Typeface.DEFAULT_BOLD
        }
        listCard.addView(listTitle)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(listBox)
        page.addView(listCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), 0, dp(16), dp(12)) })

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

        setButtons(false)

        if (crashFile.exists()) {
            showStatus("আগের ক্র্যাশের ত্রুটি:\n" + crashFile.readText().take(1200), red, 11f)
            crashFile.delete()
        }

        FFmpegKitConfig.enableStatisticsCallback { st ->
            val r = curRow
            val total = curTotal
            if (r != null && total > 0) {
                val frac = (st.time.toDouble() / total).coerceIn(0.0, 1.0)
                runOnUiThread {
                    if (curRow === r) {
                        setRow(r, (frac * 1000).toInt(), red, String.format(Locale.US, "%.1f%%", frac * 100))
                    }
                }
            }
        }

        pickBtn.setOnClickListener { picker.launch("video/*") }
        convertBtn.setOnClickListener { runQueue() }
        cancelBtn.setOnClickListener {
            cancelled = true
            cancelBtn.isEnabled = false
            showStatus("বাতিল করা হচ্ছে...", red)
            try { FFmpegKit.cancel() } catch (_: Throwable) {}
        }
    }

    private fun runQueue() {
        val list = rows
        if (list.isEmpty()) return
        val big = sizeGroup.checkedRadioButtonId == 2
        val w = if (big) 352 else 176
        val h = if (big) 288 else 144
        val vb = if (big) "300k" else "128k"

        cancelled = false
        setButtons(true)
        list.forEach { setWaiting(it) }
        showStatus("", textGray)

        Thread {
            var ok = 0
            var fail = 0
            for ((idx, row) in list.withIndex()) {
                if (cancelled) break
                runOnUiThread { showStatus("ভিডিও ${idx + 1}/${list.size} চলছে...", textDark) }
                curRow = row
                val res = convertOne(row, w, h, vb)
                curRow = null
                when (res) {
                    0 -> { ok++; runOnUiThread { setRow(row, 1000, green, "সম্পন্ন ✓") } }
                    1 -> { fail++; runOnUiThread { setRow(row, 1000, orange, "ব্যর্থ ✗") } }
                    else -> runOnUiThread { setWaiting(row, "বাতিল") }
                }
            }
            runOnUiThread {
                setButtons(false)
                if (cancelled) {
                    showStatus("⛔ বাতিল করা হয়েছে ($ok টি সম্পন্ন হয়েছিল)", red, 14f)
                } else if (fail == 0) {
                    showStatus("✅ সব সম্পন্ন! ($ok টি)\nDownload/3GP ফোল্ডারে সেভ হয়েছে", green, 15f)
                } else {
                    showStatus("সম্পন্ন: $ok টি, ব্যর্থ: $fail টি ❌\n" + lastError.takeLast(500), red, 12f)
                }
            }
        }.start()
    }

    // 0 = সফল, 1 = ব্যর্থ, 2 = বাতিল
    private fun convertOne(row: Row, w: Int, h: Int, vb: String): Int {
        val stamp = System.currentTimeMillis()
        val tmpIn = File(cacheDir, "in_$stamp.mp4")
        val out = File(cacheDir, "out_$stamp.3gp")
        try {
            runOnUiThread { setRow(row, 0, red, "কপি হচ্ছে…") }
            contentResolver.openInputStream(row.uri)!!.use { input ->
                tmpIn.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled) return 2
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            if (cancelled) return 2

            curTotal = try {
                val r = MediaMetadataRetriever()
                r.setDataSource(tmpIn.absolutePath)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                r.release()
                d
            } catch (_: Throwable) { 0L }

            runOnUiThread { setRow(row, 0, red, "0.0%") }

            fun cmd(audio: String) =
                "-y -i \"${tmpIn.absolutePath}\" " +
                "-vf scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2 " +
                "-r 15 -c:v h263 -b:v $vb $audio \"${out.absolutePath}\""

            val amr = "-c:a libopencore_amrnb -ar 8000 -ac 1 -b:a 12.2k"
            val aac = "-c:a aac -ar 16000 -ac 1 -b:a 32k"

            var s = FFmpegKit.execute(cmd(amr))
            if (cancelled) return 2
            if (!ReturnCode.isSuccess(s.returnCode)) {
                s = FFmpegKit.execute(cmd(aac))
                if (cancelled) return 2
            }

            if (!ReturnCode.isSuccess(s.returnCode)) {
                lastError = "[${row.name}]\n" + (s.output ?: "")
                return 1
            }

            return if (saveResult(out, baseName(row.name))) 0 else 1
        } catch (t: Throwable) {
            lastError = "[${row.name}]\n" + rootCause(t)
            return 1
        } finally {
            tmpIn.delete()
            out.delete()
        }
    }

    private fun saveResult(file: File, base: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$base.3gp")
                put(MediaStore.Downloads.MIME_TYPE, "video/3gpp")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/3GP")
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw RuntimeException("ফাইল তৈরি করা যায়নি")
            contentResolver.openOutputStream(dest)!!.use { o ->
                file.inputStream().use { it.copyTo(o) }
            }
            true
        } catch (t: Throwable) {
            lastError = "সেভ করতে ত্রুটি: " + rootCause(t)
            false
        }
    }
}
