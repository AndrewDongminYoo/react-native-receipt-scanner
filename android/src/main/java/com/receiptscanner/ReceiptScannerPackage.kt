package com.receiptscanner

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/**
 * React Native package registration entry. Wires [ReceiptScannerModule] into
 * the host app's React module registry and advertises it as a TurboModule.
 *
 * Hosts add this package via `MainApplication.kt` (or via autolinking, which
 * is what example/ uses). The module name string is sourced from
 * [ReceiptScannerModule.NAME] to keep JS / iOS / Android in lockstep.
 */
class ReceiptScannerPackage : BaseReactPackage() {
  override fun getModule(
    name: String,
    reactContext: ReactApplicationContext,
  ): NativeModule? =
    if (name == ReceiptScannerModule.NAME) {
      ReceiptScannerModule(reactContext)
    } else {
      null
    }

  override fun getReactModuleInfoProvider() =
    ReactModuleInfoProvider {
      mapOf(
        ReceiptScannerModule.NAME to
          ReactModuleInfo(
            name = ReceiptScannerModule.NAME,
            className = ReceiptScannerModule.NAME,
            canOverrideExistingModule = false,
            needsEagerInit = false,
            isCxxModule = false,
            isTurboModule = true,
          ),
      )
    }
}
