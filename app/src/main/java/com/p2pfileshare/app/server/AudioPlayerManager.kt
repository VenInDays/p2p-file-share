package com.p2pfileshare.app.server

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * Manages audio playback on the remote device.
 * Uses MediaPlayer to play audio files without launching external apps.
 * Supports play, stop, and volume control.
 */
object AudioPlayerManager {

    private const val TAG = "AudioPlayerManager"

    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null
    private var isPrepared = false

    /**
     * Play an audio file from the given path.
     * Stops any currently playing audio first.
     */
    fun play(context: Context, filePath: String) {
        stop()

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(filePath)
                setOnPreparedListener { player ->
                    isPrepared = true
                    player.start()
                    Log.d(TAG, "Audio playback started: $filePath")
                }
                setOnCompletionListener { player ->
                    Log.d(TAG, "Audio playback completed: $filePath")
                    currentFilePath = null
                    isPrepared = false
                    player.release()
                    mediaPlayer = null
                }
                setOnErrorListener { player, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    currentFilePath = null
                    isPrepared = false
                    player.release()
                    mediaPlayer = null
                    true
                }
            }

            mediaPlayer = mp
            currentFilePath = filePath
            isPrepared = false
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio: $filePath", e)
            currentFilePath = null
            throw e
        }
    }

    /**
     * Stop any currently playing audio.
     */
    fun stop() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaPlayer", e)
        } finally {
            mediaPlayer = null
            currentFilePath = null
            isPrepared = false
        }
    }

    /**
     * Check if audio is currently playing.
     */
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the currently playing file path.
     */
    fun getCurrentFile(): String? {
        return currentFilePath
    }

    /**
     * Get current playback position in milliseconds.
     */
    fun getCurrentPosition(): Int {
        return try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get total duration in milliseconds.
     */
    fun getDuration(): Int {
        return try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
