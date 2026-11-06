package app.atlante.library

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.atlante.library.widget.LargeWidgetReceiver
import app.atlante.library.widget.MediumWidgetReceiver
import app.atlante.library.widget.SmallWidgetReceiver
import app.atlante.library.widget.Widgets
import kotlinx.coroutines.launch

/**
 * Debug builds only: places the three widgets as a launcher would (AppWidgetHost, real RemoteViews), so the
 * emulator check can see them, tap their buttons and read the library afterwards. Binding needs
 * "adb shell appwidget grantbind --package app.atlante.library.debug".
 */
class WidgetHostActivity : ComponentActivity() {
    private lateinit var host: AppWidgetHost

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        host = AppWidgetHost(this, 4711)
        host.deleteHost()                     // a fresh set of widgets at every start
        val manager = AppWidgetManager.getInstance(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(24), dp(12), dp(24)) }
        val sizes = listOf(SmallWidgetReceiver::class.java to (160 to 160), MediumWidgetReceiver::class.java to (340 to 150), LargeWidgetReceiver::class.java to (340 to 340))
        for ((receiver, size) in sizes) {
            val provider = ComponentName(this, receiver)
            val id = host.allocateAppWidgetId()
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.first); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, size.first)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.second); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size.second)
            }
            if (!manager.bindAppWidgetIdIfAllowed(id, provider, options)) { column.addView(TextView(this).apply { text = "Bind non permesso: ${receiver.simpleName}" }); continue }
            val info = manager.getAppWidgetInfo(id)
            val view = host.createView(this, id, info)
            view.contentDescription = "widget-" + receiver.simpleName
            column.addView(view, LinearLayout.LayoutParams(dp(size.first), dp(size.second)).apply { bottomMargin = dp(16) })
        }
        setContentView(ScrollView(this).apply { addView(column) })
        lifecycleScope.launch { Widgets.update(this@WidgetHostActivity) }
    }

    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { host.stopListening(); super.onStop() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
