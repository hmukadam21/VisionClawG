package com.meta.wearable.dat.externalsampleapps.cameraaccess.debug

// DebugLog - a small on-phone diary of what VisionClaw does, for diagnosing problems.
//
// - Every important event (glasses session, video stream, Gemini connection, reconnects,
//   errors, app going to background) is written with a timestamp to a file in the app's
//   private storage, so it survives even if Android kills the app.
// - A heartbeat line every 30 s shows how many glasses frames, decoded pictures, mic chunks,
//   audio chunks to/from Gemini and pictures to Gemini flowed since the previous heartbeat,
//   which shows exactly which link stopped.
// - Settings -> "Share diagnostic log" sends the file (e.g. to Claude or Telegram).
// - Never logs secrets: no API key, no tokens, no spoken text.

import android.content.Context
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object DebugLog {
  private const val TAG = "VisionClawLog"
  private const val FILE_NAME = "visionclaw-log.txt"
  private const val OLD_FILE_NAME = "visionclaw-log.old.txt"
  private const val MAX_BYTES = 1_000_000L
  private const val SHARE_DIR = "images" // matches res/xml/file_paths.xml (cache/images/)

  private val executor = Executors.newSingleThreadExecutor()
  private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) // executor only
  @Volatile private var logFile: File? = null
  @Volatile private var oldLogFile: File? = null

  // Flow counters (read and reset by heartbeat())
  val glassesFrames = AtomicLong()
  val pictures = AtomicLong()
  val micChunks = AtomicLong()
  val audioSentToGemini = AtomicLong()
  val audioDroppedNotReady = AtomicLong()
  val audioFromGemini = AtomicLong()
  val picturesSentToGemini = AtomicLong()

  fun init(context: Context) {
    if (logFile != null) return
    val dir = context.applicationContext.filesDir
    logFile = File(dir, FILE_NAME)
    oldLogFile = File(dir, OLD_FILE_NAME)
    log("APP", "process start, version ${BuildConfig.VERSION_NAME}")
  }

  fun log(area: String, message: String) {
    Log.d(TAG, "[$area] $message")
    val now = System.currentTimeMillis()
    val thread = Thread.currentThread().name
    executor.execute {
      val file = logFile ?: return@execute
      try {
        if (file.exists() && file.length() > MAX_BYTES) {
          oldLogFile?.let { old ->
            old.delete()
            file.renameTo(old)
          }
        }
        file.appendText("${timeFormat.format(Date(now))} [$area] $message  ($thread)\n")
      } catch (e: Exception) {
        Log.w(TAG, "log write failed", e)
      }
    }
  }

  /** One line with the counts since the previous heartbeat, plus caller-provided states. */
  fun heartbeat(states: String) {
    log(
        "BEAT",
        "glassesFrames=${glassesFrames.getAndSet(0)} pictures=${pictures.getAndSet(0)} " +
            "mic=${micChunks.getAndSet(0)} audioToGemini=${audioSentToGemini.getAndSet(0)} " +
            "audioDropped=${audioDroppedNotReady.getAndSet(0)} " +
            "audioFromGemini=${audioFromGemini.getAndSet(0)} " +
            "picturesToGemini=${picturesSentToGemini.getAndSet(0)} | $states",
    )
  }

  /** Copies old + current log into the shareable cache folder and returns that file. */
  fun exportForSharing(context: Context): File? {
    return try {
      executor
          .submit(
              Callable {
                val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
                val out = File(dir, "visionclaw-log.txt")
                out.writeText("VisionClaw ${BuildConfig.VERSION_NAME} diagnostic log\n")
                oldLogFile?.takeIf { it.exists() }?.let { out.appendText(it.readText()) }
                logFile?.takeIf { it.exists() }?.let { out.appendText(it.readText()) }
                out
              }
          )
          .get(5, TimeUnit.SECONDS)
    } catch (e: Exception) {
      Log.w(TAG, "export failed", e)
      null
    }
  }

  /** Records crashes (with stack trace) before Android's default crash handling runs. */
  @Volatile private var crashHandlerInstalled = false

  fun installCrashHandler() {
    if (crashHandlerInstalled) return
    crashHandlerInstalled = true
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      try {
        val file = logFile
        if (file != null) {
          file.appendText(
              "${SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} [CRASH] " +
                  "in ${thread.name}: ${Log.getStackTraceString(throwable)}\n"
          )
        }
      } catch (_: Exception) {}
      previous?.uncaughtException(thread, throwable)
    }
  }
}
