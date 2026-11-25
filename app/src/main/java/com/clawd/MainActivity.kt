package com.clawd

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

val SERVER_PORT = 4444

class MainActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, RatForeground::class.java))
        finish()
    }
}

class RatAccessibility : AccessibilityService() {
    companion object { @Volatile var instance: RatAccessibility? = null }

    override fun onServiceConnected() {
        instance = this
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun pressHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "No root"
        val sb = StringBuilder()
        walk(root, sb, 0)
        return sb.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        node ?: return
        val text = node.text?.toString()?.replace("\n", " ") ?: ""
        if (text.isNotBlank()) sb.append(text).append("\n")
        for (i in 0 until node.childCount) walk(node.getChild(i), sb, depth + 1)
    }
}

class RatForeground : Service() {
    private var serverSocket: ServerSocket? = null
    private var running = true

    override fun onCreate() {
        super.onCreate()
        // Запускаем фоновый сервер в отдельном потоке
        Thread {
            try {
                serverSocket = ServerSocket(SERVER_PORT)
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun handleClient(client: Socket) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)
                
                // Читаем HTTP-запрос
                val requestLine = reader.readLine() ?: return@Thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@Thread
                
                val path = parts[1] // Например, /tap?x=100&y=200
                
                // Простейший роутинг
                if (path.startsWith("/tap")) {
                    val x = Regex("x=([\\d.]+)").find(path)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    val y = Regex("y=([\\d.]+)").find(path)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    RatAccessibility.instance?.tap(x, y)
                    writer.println("HTTP/1.1 200 OK\r\n\r\nOK")
                } else if (path.startsWith("/type")) {
                    val text = path.substringAfter("type=").replace("%20", " ")
                    RatAccessibility.instance?.typeText(text)
                    writer.println("HTTP/1.1 200 OK\r\n\r\nOK")
                } else if (path.startsWith("/toast")) {
                    val text = path.substringAfter("toast=").replace("%20", " ")
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
                    }
                    writer.println("HTTP/1.1 200 OK\r\n\r\nOK")
                } else if (path.startsWith("/vibrate")) {
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        v.vibrate(1000)
                    }
                    writer.println("HTTP/1.1 200 OK\r\n\r\nOK")
                } else if (path.startsWith("/back")) {
                    RatAccessibility.instance?.pressBack()
                    writer.println("HTTP/1.1 200 OK\r\n\r\nOK")
                } else if (path.startsWith("/home")) {
                    RatAccessibility.instance?.pressHome()
                    writer.println("HTTP/1.1 200 OK\r\n\r\nOK")
                } else if (path.startsWith("/dump")) {
                    val screen = RatAccessibility.instance?.dumpScreen() ?: "Error"
                    writer.println("HTTP/1.1 200 OK\r\n\r\n$screen")
                } else {
                    // Главная страница с кнопками (HTML)
                    val html = """
                        <html>
                        <body>
                        <h2>Clawd Control</h2>
                        <button onclick="fetch('/toast?toast=Hello')">Toast</button>
                        <button onclick="fetch('/vibrate')">Vibrate</button>
                        <button onclick="fetch('/back')">Back</button>
                        <button onclick="fetch('/home')">Home</button>
                        <br><br>
                        <input type="text" id="t" placeholder="Text to type">
                        <button onclick="fetch('/type?type='+document.getElementById('t').value)">Type</button>
                        <br><br>
                        <pre id="dump"></pre>
                        <button onclick="fetch('/dump').then(r=>r.text()).then(t=>document.getElementById('dump').innerText=t)">Dump Screen</button>
                        </body>
                        </html>
                    """.trimIndent()
                    writer.println("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n$html")
                }
                client.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "rat_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Clawd Web")
            .setContentText("Сервер запущен на порту $SERVER_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(1, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        running = false
        serverSocket?.close()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ctx.startForegroundService(Intent(ctx, RatForeground::class.java))
        }
    }
} 
