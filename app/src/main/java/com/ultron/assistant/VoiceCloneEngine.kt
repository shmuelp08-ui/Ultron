package com.ultron.assistant

import android.content.Context
import android.media.*
import android.net.Uri
import com.k2fsa.sherpa.onnx.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.net.URL
import java.nio.ByteOrder

object VoiceCloneEngine {
    private const val MODEL = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
    private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL.tar.bz2"
    private const val VOCODER_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx"

    fun cloneAndPlay(context: Context, uri: Uri, referenceText: String, outputText: String, status: (String) -> Unit) {
        require(referenceText.isNotBlank()) { "Enter the exact transcript of the recording." }
        require(outputText.isNotBlank()) { "Enter what ULTRON should say." }
        Thread {
            try {
                val root = File(context.filesDir, "ultron-voice-model").apply { mkdirs() }
                val modelDir = File(root, MODEL)
                val vocoder = File(root, "vocos_24khz.onnx")
                if (!File(modelDir, "tokens.txt").isFile) {
                    status("Downloading ZipVoice model (one-time download)…")
                    val archive = File(context.cacheDir, "$MODEL.tar.bz2")
                    URL(MODEL_URL).openStream().use { input ->
                        FileOutputStream(archive).use { output ->
                            val buffer = ByteArray(65536)
                            var count = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                count += n
                                if (count % (8L * 1024 * 1024) < buffer.size) status("Downloading model: \${count / 1048576} MB")
                            }
                        }
                    }
                    status("Unpacking model…")
                    modelDir.deleteRecursively()
                    TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val relative = entry.name.substringAfter("$MODEL/", entry.name)
                            val target = File(modelDir, relative)
                            if (entry.isDirectory) target.mkdirs() else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { tar.copyTo(it) }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                    archive.delete()
                }
                if (!vocoder.isFile) {
                    status("Downloading ZipVoice vocoder…")
                    URL(VOCODER_URL).openStream().use { input -> FileOutputStream(vocoder).use { input.copyTo(it) } }
                }
                check(File(modelDir, "encoder.int8.onnx").isFile && File(modelDir, "decoder.int8.onnx").isFile && File(modelDir, "tokens.txt").isFile) {
                    "ZipVoice model download is incomplete."
                }
                status("Reading recording…")
                val (samples, sampleRate) = decode(context, uri)
                status("Cloning voice on this phone…")
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = File(modelDir, "tokens.txt").absolutePath,
                            encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                            decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                            vocoder = vocoder.absolutePath,
                            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                            lexicon = File(modelDir, "lexicon.txt").absolutePath
                        ),
                        numThreads = 4,
                        debug = false,
                        provider = "cpu"
                    ),
                    maxNumSentences = 2
                )
                val tts = OfflineTts(assetManager = null, config = config)
                val audio = tts.generateWithConfig(
                    text = outputText,
                    config = GenerationConfig(
                        silenceScale = 1f, speed = 1f, sid = 0,
                        referenceAudio = samples, referenceSampleRate = sampleRate,
                        referenceText = referenceText.trim(), numSteps = 4, extra = emptyMap()
                    )
                )
                status("Playing cloned voice…")
                play(audio.samples, audio.sampleRate)
                tts.release()
                status("Voice clone complete ✓")
            } catch (e: Throwable) {
                status("Voice clone failed: \${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun decode(context: Context, uri: Uri): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use {
            extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length)
        }
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
        }
        val source = format ?: error("Selected file has no audio track.")
        extractor.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(source.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(source, null, null, 0)
        codec.start()
        val chunks = ArrayList<ShortArray>()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var rate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        while (!outputDone) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10000)
            if (index >= 0) {
                val fmt = codec.outputFormat
                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val buffer = codec.getOutputBuffer(index)!!
                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                val shorts = ShortArray(info.size / 2)
                buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                chunks.add(shorts)
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        codec.stop(); codec.release(); extractor.release()
        val mono = FloatArray(chunks.sumOf { it.size } / channels)
        var p = 0
        for (chunk in chunks) {
            var i = 0
            while (i + channels <= chunk.size && p < mono.size) {
                var sum = 0f
                for (c in 0 until channels) sum += chunk[i + c] / 32768f
                mono[p++] = sum / channels
                i += channels
            }
        }
        return mono to rate
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        player.write(pcm, 0, pcm.size)
        player.play()
        while (player.playState == AudioTrack.PLAYSTATE_PLAYING && player.playbackHeadPosition < pcm.size) Thread.sleep(100)
        player.release()
    }
}
