package com.receiptscanner

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap

class ReceiptScannerModule(
  reactContext: ReactApplicationContext,
) : NativeReceiptScannerSpec(reactContext) {
  override fun scan(
    options: ReadableMap,
    promise: Promise,
  ) {
    promise.resolve(null)
  }

  companion object {
    const val NAME = NativeReceiptScannerSpec.NAME
  }
}
