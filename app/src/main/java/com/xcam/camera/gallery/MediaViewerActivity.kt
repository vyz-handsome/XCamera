package com.xcam.camera.gallery

import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.CheckBox
import android.widget.EditText
import android.text.InputFilter
import android.text.InputType
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.xcam.camera.R
import com.xcam.camera.hd.HdService
import com.xcam.camera.livephoto.MotionPhotoWriter
import com.xcam.camera.livephoto.LoopPhotoMaker
import com.xcam.camera.settings.Prefs
import com.xcam.camera.databinding.ActivityMediaViewerBinding
import com.xcam.camera.databinding.ItemViewerPageBinding
import java.text.SimpleDateFormat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Penampil foto/video ukuran besar: geser untuk pindah, bagikan, dan hapus (dengan konfirmasi). */
class MediaViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewerBinding
    private val items = mutableListOf<MediaStoreHelper.MediaItem>()
    private val adapter = PageAdapter()
    private val io = Executors.newFixedThreadPool(2)
    private var pendingDelete: MediaStoreHelper.MediaItem? = null
    @Volatile private var loopCancelled = false
    private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID"))

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val item = pendingDelete
            pendingDelete = null
            if (item == null) return@registerForActivityResult
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                onDeleted(item)
            } else {
                // Android 10: izin sudah diberikan, ulangi penghapusan.
                try {
                    var ok = true
                    for (u in listOfNotNull(item.uri, item.liveVideoUri)) {
                        if (contentResolver.delete(u, null, null) <= 0) ok = false
                    }
                    if (ok) onDeleted(item) else failDelete()
                } catch (e: Exception) {
                    failDelete()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareCurrent() }
        binding.btnLoop.setOnClickListener { showLoopDialog() }
        binding.btnHd.setOnClickListener { onHdClicked() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        binding.pager.adapter = adapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateInfo(position)
            }
        })

        val startUri = intent.getStringExtra(EXTRA_URI)
        try {
            io.execute {
                val all = MediaStoreHelper.queryAll(this)
                val start = all.indexOfFirst { it.uri.toString() == startUri }.coerceAtLeast(0)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (all.isEmpty()) {
                        finish()
                        return@runOnUiThread
                    }
                    items.clear()
                    items.addAll(all)
                    adapter.notifyDataSetChanged()
                    binding.pager.setCurrentItem(start, false)
                    updateInfo(start)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun onStart() {
        super.onStart()
        HdService.listener = object : HdService.Listener {
            override fun onProgress(uri: Uri, percent: Int) {
                if (current()?.uri == uri) updateInfo(binding.pager.currentItem)
            }

            override fun onDone(uri: Uri, success: Boolean) {
                if (isDestroyed) return
                if (success) adapter.notifyDataSetChanged()
                updateInfo(binding.pager.currentItem)
            }
        }
        updateInfo(binding.pager.currentItem)
    }

    override fun onStop() {
        super.onStop()
        HdService.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun current() = items.getOrNull(binding.pager.currentItem)

    private fun updateInfo(position: Int) {
        val item = items.getOrNull(position) ?: return
        binding.btnLoop.visibility = if (item.isLive && !item.isLoop) View.VISIBLE else View.GONE
        binding.btnHd.visibility = if (item.isLive && !item.isLoop) View.VISIBLE else View.GONE
        val hdPct = HdService.progressOf(item.uri)
        val hdText = when {
            hdPct < 0 && HdService.isBusy(item.uri) -> "  \u2022  HD antre"
            hdPct >= 0 -> "  \u2022  HD $hdPct%"
            else -> ""
        }
        binding.tvInfo.text = "${dateFormat.format(item.dateMs)}  \u2022  ${position + 1}/${items.size}$hdText"
    }

    // ---- HD (pembesar AI 2x) untuk foto Live Photo

    private fun onHdClicked() {
        val item = current() ?: return
        if (!item.isLive || item.isLoop) return
        if (HdService.isBusy(item.uri)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.hd_title)
                .setMessage(R.string.hd_busy)
                .setNegativeButton(R.string.loop_close, null)
                .setPositiveButton(R.string.hd_cancel_job) { _, _ -> HdService.cancel(item.uri) }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hd_title)
            .setMessage(R.string.hd_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.hd_start) { _, _ ->
                if (HdService.enqueue(this, item.uri, item.isMotion)) {
                    Toast.makeText(this, R.string.hd_started, Toast.LENGTH_SHORT).show()
                    updateInfo(binding.pager.currentItem)
                } else {
                    Toast.makeText(this, R.string.hd_unavailable, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    // ---- Loop Live Photo (video -> foto -> video ..., sekitar 60 detik)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showLoopDialog() {
        val item = current() ?: return
        if (!item.isLive || item.isLoop) return
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val options = intArrayOf(15, 30, 60)
        val saved = prefs.getInt(Prefs.LOOP_SECONDS, 60)
        // Nilai tersimpan di luar pilihan tetap (15/30/60) berarti durasi sendiri.
        val savedIsCustom = saved !in options && saved in CUSTOM_MIN..CUSTOM_MAX
        var seconds = if (saved in options || savedIsCustom) saved else 60

        val label = TextView(this).apply {
            setText(R.string.loop_duration)
            setPadding(0, 0, 0, dp(4))
        }
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (s in options) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(R.string.loop_seconds_fmt, s)
                tag = s
                isChecked = !savedIsCustom && s == seconds
            })
        }
        // Pilihan durasi sendiri
        val customRadio = RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.loop_custom)
            isChecked = savedIsCustom
        }
        group.addView(customRadio)
        val customInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            hint = getString(R.string.loop_custom_hint, CUSTOM_MIN, CUSTOM_MAX)
            setSingleLine()
            if (savedIsCustom) setText(saved.toString())
            isEnabled = savedIsCustom
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val isCustom = checkedId == customRadio.id
            customInput.isEnabled = isCustom
            if (isCustom) {
                customInput.requestFocus()
            } else {
                (g.findViewById<RadioButton>(checkedId)?.tag as? Int)?.let { seconds = it }
            }
        }
        val sound = CheckBox(this).apply {
            setText(R.string.loop_with_sound)
            isChecked = true
        }
        val mp4 = CheckBox(this).apply { setText(R.string.loop_save_mp4) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(label)
            addView(group)
            addView(customInput)
            addView(sound)
            addView(mp4)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.loop_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.loop_make, null)
            .create()
        dialog.show()
        // Tombol Buat dipasang manual supaya dialog tidak tertutup saat input durasi sendiri tidak valid.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var chosen = seconds
            if (customRadio.isChecked) {
                val typed = customInput.text.toString().trim().toIntOrNull()
                if (typed == null || typed < CUSTOM_MIN || typed > CUSTOM_MAX) {
                    customInput.error = getString(R.string.loop_custom_invalid, CUSTOM_MIN, CUSTOM_MAX)
                    return@setOnClickListener
                }
                chosen = typed
            }
            prefs.edit().putInt(Prefs.LOOP_SECONDS, chosen).apply()
            dialog.dismiss()
            startLoop(item, chosen, sound.isChecked, mp4.isChecked)
        }
    }

    private fun startLoop(item: MediaStoreHelper.MediaItem, seconds: Int, withAudio: Boolean, saveMp4: Boolean) {
        loopCancelled = false
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val label = TextView(this).apply {
            setText(R.string.loop_working)
            setPadding(0, 0, 0, dp(12))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(label)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.loop_title)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> loopCancelled = true }
            .create()
        dialog.show()

        val app = applicationContext
        Thread {
            var result: LoopPhotoMaker.Result? = null
            try {
                val src = File(app.cacheDir, "live_loop_src.mp4")
                val live = item.liveVideoUri
                val videoFile: File? = if (item.isMotion) {
                    MotionPhotoWriter.extractVideo(app, item.uri, src)
                } else if (live != null) {
                    app.contentResolver.openInputStream(live)?.use { input ->
                        src.outputStream().use { input.copyTo(it) }
                        src
                    }
                } else {
                    null
                }
                if (videoFile != null) {
                    result = LoopPhotoMaker.make(
                        app, videoFile, item.uri, seconds, withAudio, saveMp4,
                        { p -> runOnUiThread { bar.progress = p } },
                        { loopCancelled }
                    )
                }
                src.delete()
            } catch (e: Throwable) {
                result = null
            }
            runOnUiThread {
                try {
                    dialog.dismiss()
                } catch (e: Exception) {
                }
                if (isDestroyed) return@runOnUiThread
                val out = result
                if (out != null) {
                    showLoopDone(out)
                } else if (!loopCancelled) {
                    Toast.makeText(this, R.string.loop_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showLoopDone(result: LoopPhotoMaker.Result) {
        AlertDialog.Builder(this)
            .setTitle(R.string.loop_done)
            .setMessage(getString(R.string.loop_done_message, result.seconds))
            .setPositiveButton(R.string.loop_play) { _, _ ->
                // Putar video loop yang tertanam di Live Photo baru.
                playMotion(MediaStoreHelper.MediaItem(result.photoUri, false, System.currentTimeMillis(), 0L, null, true, true))
            }
            .setNeutralButton(R.string.share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, result.photoUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
            .setNegativeButton(R.string.loop_close, null)
            .show()
    }

    private fun shareCurrent() {
        val item = current() ?: return
        val live = item.liveVideoUri
        val send = if (live != null) {
            // Live Photo: kirim foto dan video pasangannya sekaligus.
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(item.uri, live))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = if (item.isVideo) "video/mp4" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun confirmDelete() {
        val item = current() ?: return
        AlertDialog.Builder(this)
            .setTitle(if (item.isVideo) R.string.delete_video_title else R.string.delete_photo_title)
            .setMessage(R.string.delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> doDelete(item) }
            .show()
    }

    private fun doDelete(item: MediaStoreHelper.MediaItem) {
        val targets = listOfNotNull(item.uri, item.liveVideoUri)
        try {
            var ok = true
            for (u in targets) {
                if (contentResolver.delete(u, null, null) <= 0) ok = false
            }
            if (ok) onDeleted(item) else failDelete()
        } catch (e: SecurityException) {
            // File bukan milik instalasi app ini: minta izin ke user lewat dialog sistem.
            val sender: IntentSender? = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createDeleteRequest(contentResolver, targets).intentSender
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException ->
                    e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) {
                pendingDelete = item
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else {
                failDelete()
            }
        } catch (e: Exception) {
            failDelete()
        }
    }

    /** Motion Photo: ambil video tertanam ke cache, lalu putar di pemutar internal. */
    private fun playMotion(item: MediaStoreHelper.MediaItem) {
        val app = applicationContext
        try {
            io.execute {
                var file: java.io.File? = null
                try {
                    // Bersihkan hasil ekstrak lama, lalu pakai nama unik supaya tidak menimpa file yang sedang diputar.
                    app.cacheDir.listFiles { f -> f.name.startsWith("live_play_") }?.forEach { it.delete() }
                    val dest = java.io.File(app.cacheDir, "live_play_${System.nanoTime()}.mp4")
                    file = com.xcam.camera.livephoto.MotionPhotoWriter.extractVideo(app, item.uri, dest)
                } catch (e: Throwable) {
                    file = null
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val f = file
                    if (f != null) {
                        startActivity(
                            Intent(this, VideoPlayerActivity::class.java).setData(Uri.fromFile(f))
                        )
                    } else {
                        Toast.makeText(this, R.string.toast_live_no_video, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun failDelete() {
        Toast.makeText(this, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
    }

    private fun onDeleted(item: MediaStoreHelper.MediaItem) {
        val index = items.indexOfFirst { it.uri == item.uri }
        if (index >= 0) items.removeAt(index)
        if (items.isEmpty()) {
            finish()
            return
        }
        adapter.notifyDataSetChanged()
        updateInfo(binding.pager.currentItem.coerceAtMost(items.size - 1))
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.PageHolder>() {

        inner class PageHolder(val page: ItemViewerPageBinding) : RecyclerView.ViewHolder(page.root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
            PageHolder(ItemViewerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val item = items[position]
            val image = holder.page.image
            val key = item.uri.toString()
            image.tag = key
            image.setImageDrawable(null)
            holder.page.playBadge.visibility =
                if (item.isVideo || item.isLive) View.VISIBLE else View.GONE
            holder.page.liveTag.visibility = if (item.isLive) View.VISIBLE else View.GONE
            holder.page.root.setOnClickListener {
                // Video biasa: putar videonya. Live Photo: putar gerakan dari video pasangannya.
                val playUri = item.liveVideoUri ?: if (item.isVideo) item.uri else null
                if (playUri != null) {
                    startActivity(Intent(this@MediaViewerActivity, VideoPlayerActivity::class.java).setData(playUri))
                } else if (item.isMotion) {
                    playMotion(item)
                }
            }

            val dm = resources.displayMetrics
            val maxSize = maxOf(dm.widthPixels, dm.heightPixels)
            val app = applicationContext
            try {
                io.execute {
                    val bmp = if (item.isVideo) {
                        MediaStoreHelper.loadThumbnail(app, item.uri, maxSize, true)
                    } else {
                        MediaStoreHelper.loadBitmap(app, item.uri, maxSize)
                    }
                    if (bmp != null) image.post { if (image.tag == key) image.setImageBitmap(bmp) }
                }
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        /** Batas durasi loop yang diketik sendiri (detik). */
        private const val CUSTOM_MIN = 5
        private const val CUSTOM_MAX = 180
    }
}
