package com.receiptscanner

import com.google.mlkit.vision.text.TextRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

class OcrModelManagerTest {
  @Test
  fun `Korean capability is ready without consulting the installer`() {
    // Given
    val fixture = ManagerFixture()

    // When
    var result: List<OcrModelState>? = null
    fixture.manager.capabilities({ result = it }, ::unexpectedFailure)

    // Then
    assertEquals(OcrModelState("Kore", "ready"), result?.stateFor("Kore"))
    assertFalse(fixture.installer.checks.contains(OcrScript.KOREAN))
  }

  @Test
  fun `available Latin capability is ready`() {
    // Given
    val fixture = ManagerFixture(setOf(OcrScript.LATIN))

    // When
    var result: List<OcrModelState>? = null
    fixture.manager.capabilities({ result = it }, ::unexpectedFailure)

    // Then
    assertEquals(OcrModelState("Latn", "ready"), result?.stateFor("Latn"))
  }

  @Test
  fun `absent Japanese capability requires download without installing`() {
    // Given
    val fixture = ManagerFixture()

    // When
    var result: List<OcrModelState>? = null
    fixture.manager.capabilities({ result = it }, ::unexpectedFailure)

    // Then
    assertEquals(OcrModelState("Jpan", "download-required"), result?.stateFor("Jpan"))
    assertTrue(fixture.installer.installs.isEmpty())
  }

  @Test
  fun `capability check reports Unicode script identifiers`() {
    // Given
    val fixture = ManagerFixture(OcrScript.entries.toSet())

    // When
    var result: List<OcrModelState>? = null
    fixture.manager.capabilities({ result = it }, ::unexpectedFailure)

    // Then
    assertEquals(
      listOf(
        OcrModelState("Latn", "ready"),
        OcrModelState("Kore", "ready"),
        OcrModelState("Jpan", "ready"),
        OcrModelState("Hans", "ready"),
        OcrModelState("Hant", "ready"),
        OcrModelState("Deva", "ready"),
      ),
      result,
    )
  }

  @Test
  fun `shared Chinese capability checks its model once`() {
    // Given
    val fixture = ManagerFixture(setOf(OcrScript.CHINESE))

    // When
    var result: List<OcrModelState>? = null
    fixture.manager.capabilities({ result = it }, ::unexpectedFailure)

    // Then
    assertEquals(1, fixture.installer.checks.count { it == OcrScript.CHINESE })
    assertEquals(
      listOf(
        OcrModelState("Hans", "ready"),
        OcrModelState("Hant", "ready"),
      ),
      result?.filter { it.script == "Hans" || it.script == "Hant" },
    )
  }

  @Test
  fun `prepare keeps ready pending while an absent model is installing`() {
    // Given
    val fixture = ManagerFixture()
    var processor: OcrProcessor? = null

    // When
    fixture.manager.prepare(OcrScript.JAPANESE, { processor = it }, ::unexpectedFailure)

    // Then
    assertNull(processor)
  }

  @Test
  fun `prepare reports ready after an absent model finishes installing`() {
    // Given
    val fixture = ManagerFixture()
    var processor: OcrProcessor? = null
    fixture.manager.prepare(OcrScript.JAPANESE, { processor = it }, ::unexpectedFailure)

    // When
    fixture.installer.completeInstall()

    // Then
    assertNotNull(processor)
    processor?.close()
  }

  @Test
  fun `installation failure closes the recognizer and reports the same error`() {
    // Given
    val fixture = ManagerFixture()
    val expected = IllegalStateException("model install failed")
    var failure: Exception? = null
    fixture.manager.prepare(OcrScript.DEVANAGARI, { fail("Unexpected ready callback") }, { failure = it })

    // When
    fixture.installer.failInstall(expected)

    // Then
    assertSame(expected, failure)
    assertTrue(fixture.recognizers.latest(OcrScript.DEVANAGARI).isClosed)
  }

  @Test
  fun `capability check closes every temporary dynamic recognizer`() {
    // Given
    val fixture = ManagerFixture(OcrScript.entries.toSet())

    // When
    var result: List<OcrModelState>? = null
    fixture.manager.capabilities({ result = it }, ::unexpectedFailure)

    // Then
    assertNotNull(result)
    assertEquals(4, fixture.recognizers.all.size)
    assertTrue(fixture.recognizers.all.all(FakeRecognizer::isClosed))
  }

  private fun List<OcrModelState>.stateFor(script: String): OcrModelState = single { it.script == script }

  private fun unexpectedFailure(error: Exception): Nothing = throw AssertionError(error)

  private class ManagerFixture(
    availableScripts: Set<OcrScript> = emptySet(),
  ) {
    val recognizers = FakeRecognizerFactory()
    val installer = FakeModuleInstaller(recognizers, availableScripts)
    val manager = OcrModelManager(installer, recognizers)
  }

  private class FakeRecognizerFactory : OcrRecognizerFactory {
    val all = mutableListOf<FakeRecognizer>()
    private val scripts = IdentityHashMap<TextRecognizer, OcrScript>()

    override fun create(script: OcrScript): TextRecognizer {
      val fake = FakeRecognizer(script)
      all += fake
      scripts[fake.client] = script
      return fake.client
    }

    fun scriptFor(recognizer: TextRecognizer): OcrScript = checkNotNull(scripts[recognizer])

    fun latest(script: OcrScript): FakeRecognizer = all.last { it.script == script }
  }

  private class FakeModuleInstaller(
    private val recognizers: FakeRecognizerFactory,
    private val availableScripts: Set<OcrScript>,
  ) : OcrModuleInstaller {
    val checks = mutableListOf<OcrScript>()
    val installs = mutableListOf<OcrScript>()
    private var onInstalled: (() -> Unit)? = null
    private var onInstallFailure: ((Exception) -> Unit)? = null

    override fun check(
      recognizer: TextRecognizer,
      onResult: (Boolean) -> Unit,
      onFailure: (Exception) -> Unit,
    ) {
      val script = recognizers.scriptFor(recognizer)
      checks += script
      onResult(script in availableScripts)
    }

    override fun install(
      recognizer: TextRecognizer,
      onInstalled: () -> Unit,
      onFailure: (Exception) -> Unit,
    ) {
      installs += recognizers.scriptFor(recognizer)
      this.onInstalled = onInstalled
      onInstallFailure = onFailure
    }

    fun completeInstall() {
      checkNotNull(onInstalled).invoke()
    }

    fun failInstall(error: Exception) {
      checkNotNull(onInstallFailure).invoke(error)
    }
  }

  private class FakeRecognizer(
    val script: OcrScript,
  ) {
    var isClosed = false
      private set

    val client: TextRecognizer =
      Proxy.newProxyInstance(
        TextRecognizer::class.java.classLoader,
        arrayOf(TextRecognizer::class.java),
      ) { proxy, method, args ->
        when (method.name) {
          "close" -> {
            isClosed = true
            null
          }

          "equals" -> {
            proxy === args?.firstOrNull()
          }

          "hashCode" -> {
            System.identityHashCode(proxy)
          }

          "toString" -> {
            "FakeTextRecognizer($script)"
          }

          else -> {
            error("Unexpected TextRecognizer method: ${method.name}")
          }
        }
      } as TextRecognizer
  }
}
