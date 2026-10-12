package com.xcam.camera.gallery

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xcam.camera.R

/** Pemutar video sederhana di dalam XCAM. Dipakai juga oleh galeri di tahap berikutnya. */
class VideoPlayerActivity : AppCompatActivity() {

    private var video: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val player = VideoView(this)
        root.addView(
            player,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundResource(R.drawable.bg_round_button)
            contentDescription = getString(R.string.cd_back)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.xcam_text))
            setOnClickListener { finish() }
        }
        root.addView(
            back,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(16), dp(8), 0, 0)
            }
        )
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val controller = MediaController(this)
        controller.setAnchorView(player)
        player.setMediaController(controller)
        player.setVideoURI(uri)
        player.setOnPreparedListener { player.start() }
        video = player
    }

    override fun onPause() {
        super.onPause()
        video?.pause()
    }

    override fun onDestroy() {
        video?.stopPlayback()
        video = null
        super.onDestroy()
    }
}
