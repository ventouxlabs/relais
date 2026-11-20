/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */

package cc.grepon.relais

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hardware-only #146 proof that a live node accepts the same OpenAI content-parts payload emitted
 * by in-app chat, preserves both an image and WAV attachment, and produces a completion.
 *
 * This is deliberately double-gated: it loads a multi-GB model and runs real multimodal inference.
 * It accepts either the Tensor TPU AOT lane or the GPU fallback; the chat HTTP contract must work on
 * both, and the installed model's filename alone must not turn that contract test into a false fail.
 */
@RunWith(AndroidJUnit4::class)
class HttpMultimodalProbe {

  private val args = InstrumentationRegistry.getArguments()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun liveNodeAcceptsImageAndAudioContentParts() {
    assumeTrue("pass -e RELAIS_PROBE 1 to run hardware multimodal inference", args.getString("RELAIS_PROBE") == "1")
    val modelPath = args.getString("model")
    assumeTrue("pass -e model <a readable multimodal .litertlm>", modelPath != null && File(modelPath).canRead())

    RelaisEngine.ensureInitialized(context, modelPath!!, RelaisConfig.modelId(context))
    assertTrue("engine not ready after init", RelaisEngine.isReady)
    assertTrue("model did not expose image and audio encoders", RelaisEngine.isMultimodal)

    val server = RelaisHttpServer(context, port = PORT, tls = false, bindAddr = "127.0.0.1").also { it.start() }
    Thread.sleep(300)
    try {
      val png = solidColorPng(Color.RED)
      val wav = sineWav()
      val parts = JSONArray()
        .put(JSONObject().put("type", "text").put("text", "What is the dominant image color? Answer briefly."))
        .put(
          JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/png;base64,${Base64.encodeToString(png, Base64.NO_WRAP)}")),
        )
        .put(
          JSONObject()
            .put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav")),
        )
      val body =
        JSONObject()
          .put("model", RelaisEngine.residentModelId)
          .put("stream", false)
          .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", parts)))
          .toString()

      val response = post(body, RelaisConfig.apiKey(context))
      assertTrue("chat completion should be 200, got: ${response.statusLine} ${response.body.take(400)}", response.statusLine.contains(" 200 "))
      val text = JSONObject(response.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
      assertTrue("multimodal completion was empty", text.isNotBlank())
      Log.i(TAG, "content-parts completion (${if (RelaisEngine.residentIsTpu) "TPU" else "GPU"}): ${text.take(160)}")
    } finally {
      server.stop()
    }
  }

  private data class HttpResult(val statusLine: String, val body: String)

  private fun post(json: String, key: String?): HttpResult {
    val head = "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $key\r\n" +
      "Content-Type: application/json\r\nContent-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n"
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", PORT), 5_000)
      socket.soTimeout = RESPONSE_TIMEOUT_MS
      socket.getOutputStream().apply { write((head + json).toByteArray()); flush() }
      val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
      val statusLine = reader.readLine() ?: ""
      while (reader.readLine()?.isNotEmpty() == true) Unit
      return HttpResult(statusLine, reader.readText())
    }
  }

  private fun solidColorPng(color: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(color)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  private fun sineWav(): ByteArray {
    val sampleRate = 16_000
    val samples = sampleRate
    return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
      put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
      putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
      put("data".toByteArray()); putInt(samples * 2)
      repeat(samples) { index -> putShort((Math.sin(2.0 * Math.PI * 440 * index / sampleRate) * 0.6 * Short.MAX_VALUE).toInt().toShort()) }
    }.array()
  }

  private companion object {
    const val PORT = 18097
    const val RESPONSE_TIMEOUT_MS = 180_000
    const val TAG = "HttpMultimodalProbe"
  }
}
