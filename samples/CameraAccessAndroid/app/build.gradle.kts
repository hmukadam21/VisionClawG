/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  // DAT SDK 1.0.1 is built against API 36 (same as Meta's own sample).
  compileSdk = 36

  buildFeatures {
    buildConfig = true
    compose = true
  }

  defaultConfig {
    // Own app ID (was Meta's sample ID). Needed to register the app in Meta's Wearables Developer
    // Center (e.g. for "Hey Meta, start VisionClaw"). The code package (namespace) is unchanged.
    applicationId = "com.hmukadam21.visionclaw"
    minSdk = 31
    // Kept at 34 so Android behaviour of the app does not change in this upgrade.
    targetSdk = 34
    versionCode = 4
    versionName = "1.3-diag"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }

    // Meta Wearables Device Access Toolkit setup.
    // In Developer Mode (Meta AI app) these stay empty, exactly as in Meta's sample.
    // Without Developer Mode they must hold the app's ID and client token from the
    // Wearables Developer Center (put them in local.properties).
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "")
  }

  signingConfigs {
    // Fixed sample key so every new build installs over the previous one.
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  // VisionClaw additions
  implementation(libs.okhttp)
  implementation(libs.webrtc)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.datastore.preferences)
  implementation(libs.gson)
  implementation(libs.lifecycle.process)
}
