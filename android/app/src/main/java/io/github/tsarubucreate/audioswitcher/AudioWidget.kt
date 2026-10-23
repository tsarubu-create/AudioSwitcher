package io.github.tsarubucreate.audioswitcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews

/**
 * ホーム画面のウィジェット（横 2 マス・縦 1 マス。docs/画面.md）。
 * 左に「録音 / 停止」の丸いボタン、右に「PC / 端末」の切り替え。ほかの設定はアプリで最後に選んだものを使う。
 *
 * 丸いボタンは [WidgetActionActivity] を開いて始める・止める。切り替えはここで受けて、音の切り替えを書き換える。
 * 見た目は、再生・取り込みの始まりと終わり、音の切り替え、画面の色の切り替えのたびに [update] で描き直す。
 */
class AudioWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, render(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE_SOURCE) return
        // 再生中・取り込み中は変えない（画面と同じ決まり）。Android 10 より前は「端末」にしない。
        if (isRunning(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val settings = AppSettings(context)
        settings.deviceSource = !settings.deviceSource
        update(context)
    }

    companion object {
        private const val ACTION_TOGGLE_SOURCE = "io.github.tsarubucreate.audioswitcher.WIDGET_TOGGLE_SOURCE"

        /** 置いてあるウィジェットを、今の状態で描き直す（置いていなければ何もしない）。 */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AudioWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, render(context))
        }

        /** PC の音の再生中か、端末の音の取り込み中。 */
        fun isRunning(context: Context): Boolean =
            PlaybackStartStore(context).startedAt() != null || CaptureState(context).startedAt() != null

        private fun render(context: Context): RemoteViews {
            val settings = AppSettings(context)
            val running = isRunning(context)
            val device = settings.deviceSource && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val dark = settings.darkTheme
                ?: ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
            val c = WidgetPalette(context, dark)

            return RemoteViews(context.packageName, R.layout.widget_audio).apply {
                setInt(R.id.widget_root, "setBackgroundResource", c.background)
                setInt(R.id.widget_toggle, "setBackgroundResource", c.toggle)

                setInt(R.id.widget_record, "setBackgroundResource", if (running) c.circleRunning else c.circleIdle)
                setImageViewResource(R.id.widget_record, if (running) c.stopIcon else c.recordIcon)
                setContentDescription(
                    R.id.widget_record,
                    context.getString(if (running) R.string.widget_stop else R.string.widget_record),
                )
                setOnClickPendingIntent(
                    R.id.widget_record,
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, WidgetActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )

                // 選んでいるほうを信号の色で示す。再生中は押しても変わらないので、選んでいないほうを薄くする。
                val idleText = if (running) c.rule else c.graphite
                setInt(R.id.widget_source_pc, "setBackgroundResource", if (device) 0 else c.segment)
                setTextColor(R.id.widget_source_pc, if (device) idleText else c.onSignal)
                setInt(R.id.widget_source_device, "setBackgroundResource", if (device) c.segment else 0)
                setTextColor(R.id.widget_source_device, if (device) c.onSignal else idleText)
                setOnClickPendingIntent(
                    R.id.widget_toggle,
                    PendingIntent.getBroadcast(
                        context,
                        1,
                        Intent(context, AudioWidget::class.java).setAction(ACTION_TOGGLE_SOURCE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
    }
}

/** ウィジェットの色（res/values/widget_colors.xml の light・dark のどちらか）。 */
private class WidgetPalette(context: Context, dark: Boolean) {
    val background = if (dark) R.drawable.widget_bg_dark else R.drawable.widget_bg_light
    val toggle = if (dark) R.drawable.widget_toggle_dark else R.drawable.widget_toggle_light
    val circleIdle = if (dark) R.drawable.widget_circle_idle_dark else R.drawable.widget_circle_idle_light
    val circleRunning = if (dark) R.drawable.widget_circle_running_dark else R.drawable.widget_circle_running_light
    val segment = if (dark) R.drawable.widget_segment_dark else R.drawable.widget_segment_light
    val recordIcon = if (dark) R.drawable.ic_widget_record_dark else R.drawable.ic_widget_record_light
    val stopIcon = if (dark) R.drawable.ic_widget_stop_dark else R.drawable.ic_widget_stop_light
    val graphite = context.getColor(if (dark) R.color.widget_graphite_dark else R.color.widget_graphite_light)
    val rule = context.getColor(if (dark) R.color.widget_rule_dark else R.color.widget_rule_light)
    val onSignal = context.getColor(if (dark) R.color.widget_onSignal_dark else R.color.widget_onSignal_light)
}
