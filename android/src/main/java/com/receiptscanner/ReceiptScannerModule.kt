package com.receiptscanner

import com.facebook.react.bridge.ReactApplicationContext

class ReceiptScannerModule(
  reactContext: ReactApplicationContext,
) : NativeReceiptScannerSpec(reactContext) {
  override fun multiply(
    a: Double,
    b: Double,
  ): Double = a * b

  companion object {
    const val NAME = NativeReceiptScannerSpec.NAME
  }
}
