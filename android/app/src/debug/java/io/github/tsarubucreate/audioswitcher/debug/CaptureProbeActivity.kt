package io.github.tsarubucreate.audioswitcher.debug

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 端末の中で鳴っている音（X の Space など）を取り込めるかを確かめる画面（Debug 版だけ。docs/構成.md の 2.7）。
 *
 * 「X だけ」と「全部のアプリ」の2つで 20 秒ずつ取り込み、1 秒ごとの音の大きさを [CaptureProbeService] が
 * 画面と logcat（タグ CaptureProbe）に出す。取り込んだ音はアプリの外部ファイル用のフォルダに WAV で置く。
 * 開き方: `adb shell am start -n io.github.tsarubucreate.audioswitcher.debug/io.github.tsarubucreate.audioswitcher.debug.CaptureProbeActivity`
 */
class CaptureProbeActivity : Activity() {
    private lateinit var output: TextView
    private var onlyX = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(32, 32, 32, 32) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
            addView(Button(context).apply {
                text = "X だけを 20 秒取り込む"
                setOnClickListener { start(onlyX = true) }
            })
            addView(Button(context).apply {
                text = "全部のアプリを 20 秒取り込む"
                setOnClickListener { start(onlyX = false) }
            })
            addView(output)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        CaptureProbeService.onLine = { line -> runOnUiThread { output.append(line + "\n") } }
        output.append("Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）、機種 ${Build.MODEL}\n")
    }

    override fun onDestroy() {
        CaptureProbeService.onLine = null
        super.onDestroy()
    }

    private fun start(onlyX: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            output.append("Android 10 より前なので取り込めない\n")
            return
        }
        this.onlyX = onlyX
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            start(onlyX)
        } else {
            output.append("録音の許可が無いので取り込めない\n")
        }
    }

    @Deprecated("startActivityForResult の結果")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            output.append("取り込みの許可が得られなかった（resultCode=$resultCode）\n")
            return
        }
        startForegroundService(
            Intent(this, CaptureProbeService::class.java)
                .putExtra(CaptureProbeService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureProbeService.EXTRA_DATA, data)
                .putExtra(CaptureProbeService.EXTRA_ONLY_X, onlyX),
        )
    }

    private companion object {
        const val REQUEST_RECORD = 1
        const val REQUEST_PROJECTION = 2
    }
}
