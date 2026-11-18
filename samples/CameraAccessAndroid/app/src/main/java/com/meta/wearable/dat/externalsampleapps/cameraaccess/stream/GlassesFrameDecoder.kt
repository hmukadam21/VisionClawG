/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// GlassesFrameDecoder - compressed (HEVC) glasses video -> Bitmaps for Gemini and the preview
//
// Why compressed video: the DAT SDK pauses a RAW stream as soon as the app leaves the foreground
// (screen locked, phone in pocket) but keeps a COMPRESSED stream flowing. So the glasses send
// HEVC, and this class turns it back into pictures:
//
//   HEVC frames -> HevcDecoder (Meta's own sample decoder, unchanged)
//               -> ImageReader surface (YUV_420_888 images, no screen needed)
//               -> NV21 -> JPEG -> Bitmap, at most one every [minIntervalMs]
//
// Gemini only needs about one picture per second, so most decoded images are dropped unconverted.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.externalsampleapps.cameraaccess.debug.DebugLog
import java.io.ByteArrayOutputStream

class GlassesFrameDecoder(
    private val minIntervalMs: Long,
    private val onBitmap: (Bitmap) -> Unit,
) {
  companion object {
    private const val TAG = "GlassesFrameDecoder"
    private const val MAX_IMAGES = 3
    private const val JPEG_QUALITY = 70
  }

  private val lock = Any()
  private var hevcDecoder: HevcDecoder? = null
  private var imageReader: ImageReader? = null
  private var readerThread: HandlerThread? = null
  private var released = false

  // Codec-config frames (VPS/SPS/PPS) that arrive before we know the picture size are kept and
  // fed to the decoder first, so it can start on the first keyframe.
  private val pendingConfig = mutableListOf<ByteArray>()

  @Volatile private var lastEmitMs = 0L
  @Volatile private var firstPictureLogged = false
  @Volatile private var failureCount = 0

  /** Feed one frame from Stream.videoStream. Called on a single background thread. */
  fun decode(frame: VideoFrame) {
    if (!frame.isCompressed) return

    val buffer = frame.buffer
    val bytes = ByteArray(buffer.remaining())
    val originalPosition = buffer.position()
    buffer.get(bytes)
    buffer.position(originalPosition)
    if (bytes.isEmpty()) return

    synchronized(lock) {
      if (released) return
      if (hevcDecoder == null) {
        if (frame.width <= 0 || frame.height <= 0) {
          if (frame.isCodecConfig) pendingConfig.add(bytes)
          return
        }
        setupLocked(frame.width, frame.height)
        pendingConfig.forEach { hevcDecoder?.decodeFrame(it, 0L) }
        pendingConfig.clear()
      }
      hevcDecoder?.decodeFrame(bytes, frame.presentationTimeUs)
    }
  }

  /** Stops decoding and frees the codec, the image reader and its thread. */
  fun release() {
    synchronized(lock) {
      released = true
      teardownLocked()
      pendingConfig.clear()
    }
  }

  private fun setupLocked(width: Int, height: Int) {
    DebugLog.log("DECODER", "starting HEVC decode at ${width}x$height")
    val thread = HandlerThread("GlassesFrameReader").apply { start() }
    readerThread = thread
    val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES)
    reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, Handler(thread.looper))
    imageReader = reader
    hevcDecoder = HevcDecoder().also { it.start(width, height, reader.surface) }
  }

  private fun teardownLocked() {
    // Stop the codec first so it no longer writes into the reader's surface.
    try {
      hevcDecoder?.stop()
    } catch (e: Exception) {
      Log.w(TAG, "Decoder stop failed", e)
    }
    hevcDecoder = null
    try {
      imageReader?.setOnImageAvailableListener(null, null)
      imageReader?.close()
    } catch (e: Exception) {
      Log.w(TAG, "ImageReader close failed", e)
    }
    imageReader = null
    readerThread?.quitSafely()
    readerThread = null
  }

  private fun onImageAvailable(reader: ImageReader) {
    val image =
        try {
          reader.acquireLatestImage()
        } catch (e: Exception) {
          Log.w(TAG, "acquireLatestImage failed", e)
          null
        } ?: return

    try {
      val now = SystemClock.elapsedRealtime()
      if (now - lastEmitMs < minIntervalMs) return
      lastEmitMs = now
      val bitmap = imageToBitmap(image) ?: return
      if (!firstPictureLogged) {
        firstPictureLogged = true
        DebugLog.log("DECODER", "first picture decoded (${bitmap.width}x${bitmap.height})")
      }
      onBitmap(bitmap)
    } catch (e: Exception) {
      failureCount++
      if (failureCount <= 3) {
        DebugLog.log("DECODER", "picture conversion failed #$failureCount: ${e.javaClass.simpleName}: ${e.message}")
      }
    } finally {
      image.close()
    }
  }

  // YUV_420_888 Image -> NV21 bytes (respecting crop, row and pixel strides) -> JPEG -> Bitmap
  private fun imageToBitmap(image: Image): Bitmap? {
    val crop = image.cropRect
    val width = crop.width() and 1.inv() // even sizes for 4:2:0
    val height = crop.height() and 1.inv()
    if (width <= 0 || height <= 0 || image.planes.size < 3) return null

    val nv21 = ByteArray(width * height * 3 / 2)

    // Y plane, row by row
    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer.duplicate()
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    var out = 0
    for (row in 0 until height) {
      val rowStart = (crop.top + row) * yRowStride + crop.left * yPixelStride
      if (yPixelStride == 1) {
        yBuffer.position(rowStart)
        yBuffer.get(nv21, out, width)
        out += width
      } else {
        for (col in 0 until width) {
          nv21[out++] = yBuffer.get(rowStart + col * yPixelStride)
        }
      }
    }

    // Chroma: NV21 wants interleaved V,U at quarter resolution
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride
    val vRowStride = vPlane.rowStride
    val vPixelStride = vPlane.pixelStride
    val chromaTop = crop.top / 2
    val chromaLeft = crop.left / 2
    for (row in 0 until height / 2) {
      for (col in 0 until width / 2) {
        val uIndex = (chromaTop + row) * uvRowStride + (chromaLeft + col) * uvPixelStride
        val vIndex = (chromaTop + row) * vRowStride + (chromaLeft + col) * vPixelStride
        nv21[out++] = vBuffer.get(vIndex)
        nv21[out++] = uBuffer.get(uIndex)
      }
    }

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val jpeg =
        ByteArrayOutputStream().use { stream ->
          yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, stream)
          stream.toByteArray()
        }
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
  }
}
