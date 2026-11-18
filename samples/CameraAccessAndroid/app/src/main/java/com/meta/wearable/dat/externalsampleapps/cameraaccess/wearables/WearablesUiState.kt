/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesUiState - DAT API State Management (DAT SDK 1.0.1)
//
// This data class aggregates DAT API state for the UI layer.
// In SDK 1.0.1 RegistrationState is a plain enum (REGISTERED, REGISTERING, ...),
// no longer the sealed classes (Registered, Registering, ...) of SDK 0.4.0.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class WearablesUiState(
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val devices: ImmutableList<DeviceIdentifier> = persistentListOf(),
    val recentError: String? = null,
    val isStreaming: Boolean = false,
    val isGettingStartedSheetVisible: Boolean = false,
    val hasActiveDevice: Boolean = false,
    val isPhoneMode: Boolean = false,
    val isSettingsVisible: Boolean = false,
) {
  // UNREGISTERING still counts as registered until the Meta AI app confirms (as in Meta's sample).
  val isRegistered: Boolean =
      registrationState == RegistrationState.REGISTERED ||
          registrationState == RegistrationState.UNREGISTERING

  val isRegistering: Boolean = registrationState == RegistrationState.REGISTERING
}
