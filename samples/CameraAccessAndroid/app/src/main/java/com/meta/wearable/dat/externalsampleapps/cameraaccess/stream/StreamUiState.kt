/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamUiState - DAT Camera Streaming UI State (DAT SDK 1.0.1)
//
// In SDK 1.0.1 the stream state is StreamState (STOPPED, STARTING, STARTED, STREAMING, PAUSED,
// STOPPING, CLOSED); SDK 0.4.0's StreamSessionState no longer exists.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamState

enum class StreamingMode { GLASSES, PHONE }

data class StreamUiState(
    val streamSessionState: StreamState = StreamState.STOPPED,
    val videoFrame: Bitmap? = null,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
    val streamingMode: StreamingMode = StreamingMode.GLASSES,
    // v1.6 on-demand camera: camera off until Gemini looks; isLooking while a photo is taken
    val cameraOnDemand: Boolean = false,
    val isLooking: Boolean = false,
)
