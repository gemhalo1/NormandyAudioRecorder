/*
* Copyright 2024 Dmytro Ponomarenko
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.dimowner.audiorecorder.v2.app.home

import android.animation.TypeEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dimowner.audiorecorder.ARApplication
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.DecodeService
import com.dimowner.audiorecorder.app.DecodeServiceListener
import com.dimowner.audiorecorder.app.DownloadService
import com.dimowner.audiorecorder.audio.AudioDecoder
import com.dimowner.audiorecorder.audio.player.PlayerContractNew
import com.dimowner.audiorecorder.exception.AppException
import com.dimowner.audiorecorder.exception.CantCreateFileException
import com.dimowner.audiorecorder.util.AndroidUtils
import com.dimowner.audiorecorder.util.FileUtil
import com.dimowner.audiorecorder.util.TimeUtils
import com.dimowner.audiorecorder.v2.app.adjustWaveformHeights
import com.dimowner.audiorecorder.v2.app.calculateGridStep
import com.dimowner.audiorecorder.v2.app.calculateScale
import com.dimowner.audiorecorder.v2.app.components.WaveformState
import com.dimowner.audiorecorder.v2.app.info.RecordInfoState
import com.dimowner.audiorecorder.v2.app.info.toRecordInfoState
import com.dimowner.audiorecorder.v2.app.toInfoCombinedText
import com.dimowner.audiorecorder.v2.data.FileDataSource
import com.dimowner.audiorecorder.v2.data.PrefsV2
import com.dimowner.audiorecorder.v2.data.RecordsDataSource
import com.dimowner.audiorecorder.v2.data.model.Record
import com.dimowner.audiorecorder.v2.di.qualifiers.IoDispatcher
import com.dimowner.audiorecorder.v2.di.qualifiers.MainDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

private const val ANIMATION_DURATION = 330L //mills.

@SuppressWarnings("LongParameterList")
@HiltViewModel
internal class HomeViewModel @Inject constructor(
    private val recordsDataSource: RecordsDataSource,
    private val fileDataSource: FileDataSource,
    private val prefs: PrefsV2,
    private val audioPlayer: PlayerContractNew.Player,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationContext context: Context,
) : AndroidViewModel(context as Application) {

    private val _state = mutableStateOf(HomeScreenState())
    val state: State<HomeScreenState> = _state

    private val _event = MutableSharedFlow<HomeScreenEvent?>()
    val event: SharedFlow<HomeScreenEvent?> = _event

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DecodeService.LocalBinder
            val decodeService = binder.getService()
            decodeService.setDecodeListener(object : DecodeServiceListener {
                override fun onStartProcessing() {
                    //Do nothing
                }

                override fun onFinishProcessing(decodedData: IntArray) {
                    viewModelScope.launch(ioDispatcher) {
                        //TODO: Handle the case when active racord has changed during decoding.
                        recordsDataSource.getActiveRecord()?.let {
                            recordsDataSource.updateRecord(it.copy(
                                amps = decodedData
                            ))
                        }
                    }
                }
            })
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            //Do nothing
        }

        override fun onBindingDied(name: ComponentName) {
            //Do nothing
        }
    }

    suspend fun init() {
        withContext(ioDispatcher) {
            updateState()
            audioPlayer.addPlayerCallback(callback = object : PlayerContractNew.PlayerCallback {
                override fun onStartPlay() {
                    _state.value = _state.value.copy(
                        showPause = true,
                        showStop = true,
                    )
                }

                override fun onPlayProgress(mills: Long) {
                    if (!_state.value.isSeek) {
                        _state.value = _state.value.copy(
                            waveformState = _state.value.waveformState.copy(
                                playProgressMills = mills
                            ),
                            progress = mills / _state.value.waveformState.durationMills.toFloat(),
                            time = TimeUtils.formatTimeIntervalHourMinSec2(mills),
                            showPause = true,
                            showStop = true,
                        )
                    }
                }

                override fun onPausePlay() {
                    _state.value = _state.value.copy(
                        showPause = false,
                        showStop = true
                    )
                }

                override fun onSeek(mills: Long) {
                    //Do nothing
                }

                override fun onStopPlay() {
                    _state.value = _state.value.copy(
                        showPause = false,
                        showStop = false
                    )
                    moveToStart()
                }

                override fun onError(throwable: AppException) {
                    Timber.e(throwable)
                    //TODO: Show error to user
                }
            })
        }

        val context: Context = getApplication<Application>().applicationContext
        val intent = Intent(context, DecodeService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private suspend fun updateState() {
        val context: Context = getApplication<Application>().applicationContext
        val activeRecord = recordsDataSource.getActiveRecord()
        if (activeRecord != null) {
            withContext(mainDispatcher) {
                _state.value = _state.value.copy(
                    waveformState = WaveformState(
                        widthScale = calculateScale(activeRecord.durationMills, defaultWidthScale = 1.5f),
                        durationMills = activeRecord.durationMills,
                        playProgressMills = 0L,
                        waveformData = adjustWaveformHeights(activeRecord.amps, 100),
                        durationSample = activeRecord.amps.size,
                        gridStepMills = calculateGridStep(activeRecord.durationMills)
                    ),
                    startTime = context.getString(R.string.zero_time),
                    endTime = TimeUtils.formatTimeIntervalHourMinSec2(activeRecord.durationMills),
                    time = context.getString(R.string.zero_time),
                    recordName = activeRecord.name,
                    recordInfo = activeRecord.toInfoCombinedText(context),
                    isContextMenuAvailable = true
                )
            }
        } else {
            withContext(mainDispatcher) {
                _state.value = HomeScreenState()
            }
        }
    }

    @SuppressLint("Recycle")
    fun importAudioFile(uri: Uri) {
        val context: Context = getApplication<Application>().applicationContext
        viewModelScope.launch(ioDispatcher) {
            try {
                val parcelFileDescriptor: ParcelFileDescriptor? =
                    context.contentResolver.openFileDescriptor(uri, "r")
                val fileDescriptor = parcelFileDescriptor?.fileDescriptor
                val name: String? = DocumentFile.fromSingleUri(context, uri)?.name
                if (name != null) {
                    val newFile: File = fileDataSource.createRecordFile(name)
                    if (FileUtil.copyFile(fileDescriptor, newFile)) { //TODO: Fix
                        val info = AudioDecoder.readRecordInfo(newFile)

                        //Do 2 step import: 1) Import record with empty waveform.
                        //2) Process and update waveform in background.
                        val record = Record(
                            0,
                            FileUtil.removeFileExtension(newFile.name), //TODO: Fix
                            if (info.duration >= 0) info.duration/1000 else 0,
                            newFile.lastModified(),
                            System.currentTimeMillis(),
                            Long.MAX_VALUE,
                            newFile.absolutePath,
                            info.format,
                            info.size,
                            info.sampleRate,
                            info.channelCount,
                            info.bitrate,
                            isBookmarked = false,
                            isWaveformProcessed = false,
                            isMovedToRecycle = false,
                            IntArray(ARApplication.longWaveformSampleCount),
                        )
                        val id = recordsDataSource.insertRecord(record)
                        withContext(mainDispatcher) {
                            audioPlayer.stop()
                        }
                        prefs.activeRecordId = id
                        updateState()
                        decodeRecord(record.path, record.durationMills)
                    }
                } else {
                    //TODO: Show an error
                }
            } catch (e: SecurityException) {
                Timber.e(e)
            } catch (e: IOException) {
                Timber.e(e)
            } catch (e: OutOfMemoryError) {
                Timber.e(e)
            } catch (e: IllegalStateException) {
                Timber.e(e)
            } catch (ex: CantCreateFileException) {
                Timber.e(ex)
            }
        }
    }

    private fun decodeRecord(path: String, durationMills: Long) {
        DecodeService.startNotificationV2(
            getApplication<Application>().applicationContext,
            path,
            durationMills
        )
    }

    fun shareActiveRecord() {
        viewModelScope.launch(ioDispatcher) {
            val activeRecord = recordsDataSource.getActiveRecord()
            if (activeRecord != null) {
                withContext(mainDispatcher) {
                    AndroidUtils.shareAudioFile(
                        getApplication<Application>().applicationContext,
                        activeRecord.path,
                        activeRecord.name,
                        activeRecord.format
                    )
                }
            }
        }
    }

    fun showActiveRecordInfo() {
        viewModelScope.launch(ioDispatcher) {
            recordsDataSource.getActiveRecord()?.toRecordInfoState()?.let {
                emitEvent(HomeScreenEvent.RecordInformationEvent(it))
            }
        }
    }

    fun renameActiveRecord(newName: String) {
        viewModelScope.launch(ioDispatcher) {
            val activeRecord = recordsDataSource.getActiveRecord()
            if (activeRecord != null) {
                recordsDataSource.renameRecord(activeRecord, newName)
                updateState()
            }
        }
    }

    fun openActiveRecordWithAnotherApp() {
        viewModelScope.launch(ioDispatcher) {
            val activeRecord = recordsDataSource.getActiveRecord()
            if (activeRecord != null) {
                withContext(mainDispatcher) {
                    AndroidUtils.openAudioFile(
                        getApplication<Application>().applicationContext,
                        activeRecord.path,
                        activeRecord.name
                    )
                }
            }
        }
    }

    fun saveActiveRecordAs() {
        viewModelScope.launch(ioDispatcher) {
            val activeRecord = recordsDataSource.getActiveRecord()
            if (activeRecord != null) {
                DownloadService.startNotification(
                    getApplication<Application>().applicationContext,
                    activeRecord.path
                )
            }
        }
    }

    fun deleteActiveRecord() {
        audioPlayer.stop()
        viewModelScope.launch(ioDispatcher) {
            val recordId = prefs.activeRecordId
            if (recordId != -1L && recordsDataSource.moveRecordToRecycle(recordId)) {
                prefs.activeRecordId = -1
                //TODO: Notify active record deleted
                updateState()
            } else {
                //TODO: Show error message
            }
        }
    }

    fun handleSeekStart() {
        _state.value = _state.value.copy(
            isSeek = true
        )
    }

    fun handleSeekProgress(mills: Long) {
        _state.value = _state.value.copy(
            time = TimeUtils.formatTimeIntervalHourMinSec2(mills),
            progress = mills/_state.value.waveformState.durationMills.toFloat(),
            waveformState = _state.value.waveformState.copy(
                playProgressMills = mills
            )
        )
    }

    fun handleSeekEnd(mills: Long) {
        _state.value = _state.value.copy(
            time = TimeUtils.formatTimeIntervalHourMinSec2(mills),
            progress = mills/_state.value.waveformState.durationMills.toFloat(),
            isSeek = false,
            waveformState = _state.value.waveformState.copy(
                playProgressMills = mills,
            )
        )
        if (!audioPlayer.isPlaying()) {
            _state.value = _state.value.copy(
                showPause = false,
                showStop = true
            )
        }
        audioPlayer.seek(mills)
    }

    fun handleProgressBarStateChange(value: Float) {
        val mills = (_state.value.waveformState.durationMills*value).toLong()
        _state.value = _state.value.copy(
            time = TimeUtils.formatTimeIntervalHourMinSec2(mills),
            progress = mills/_state.value.waveformState.durationMills.toFloat(),
            waveformState = _state.value.waveformState.copy(
                playProgressMills = mills
            )
        )
        audioPlayer.seek(mills)
    }

    fun handlePlayClick() {
        if (!audioPlayer.isPlaying()) {
            viewModelScope.launch(ioDispatcher) {
                val activeRecord = recordsDataSource.getActiveRecord()
                if (activeRecord != null) {
                    withContext(mainDispatcher) {
                        audioPlayer.play(activeRecord.path)
                    }
                }
            }
        } else {
            Timber.e("Playback did not started because already playing")
        }
    }

    fun handlePauseClick() {
        audioPlayer.pause()
    }

    fun handleStopClick() {
        audioPlayer.stop()
    }

    fun moveToStart() {
        val moveAnimator = ValueAnimator.ofObject(
            LongEvaluator(),
            _state.value.waveformState.playProgressMills,
            0L
        )
        moveAnimator.interpolator = DecelerateInterpolator()
        moveAnimator.duration = ANIMATION_DURATION
        moveAnimator.addUpdateListener { animation: ValueAnimator ->
            val moveValMills = animation.animatedValue as Long
            handleSeekProgress(moveValMills)
        }
        moveAnimator.start()
    }

    fun onAction(action: HomeScreenAction) {
        when (action) {
            HomeScreenAction.InitHomeScreen -> viewModelScope.launch { init() }
            is HomeScreenAction.ImportAudioFile -> importAudioFile(action.uri)
            HomeScreenAction.ShareActiveRecord -> shareActiveRecord()
            HomeScreenAction.ShowActiveRecordInfo -> showActiveRecordInfo()
            HomeScreenAction.OpenActiveRecordWithAnotherApp -> openActiveRecordWithAnotherApp()
            HomeScreenAction.DeleteActiveRecord -> deleteActiveRecord()
            HomeScreenAction.SaveActiveRecordAs -> saveActiveRecordAs()
            is HomeScreenAction.RenameActiveRecord -> renameActiveRecord(action.newName)
            HomeScreenAction.OnSeekStart -> handleSeekStart()
            is HomeScreenAction.OnSeekProgress -> handleSeekProgress(action.mills)
            is HomeScreenAction.OnSeekEnd -> handleSeekEnd(action.mills)
            is HomeScreenAction.OnProgressBarStateChange -> handleProgressBarStateChange(action.value)
            HomeScreenAction.OnPauseClick -> handlePauseClick()
            HomeScreenAction.OnPlayClick -> handlePlayClick()
            HomeScreenAction.OnStopClick -> handleStopClick()
        }
    }

    private fun emitEvent(event: HomeScreenEvent) {
        viewModelScope.launch {
            _event.emit(event)
        }
    }
}

data class HomeScreenState(
    val waveformState: WaveformState = WaveformState(),
    val startTime: String = "",
    val endTime: String = "",
    val time: String = "",
    //Progress is value between 0 - 1f
    val progress: Float = 0f,
    val recordName: String = "",
    val recordInfo: String = "",
    val isContextMenuAvailable: Boolean = false,
    val isStopRecordingButtonAvailable: Boolean = false,
    val showPause: Boolean = false,
    val showStop: Boolean = false,
    val isSeek: Boolean = false,
)

internal sealed class HomeScreenAction {
    data object InitHomeScreen : HomeScreenAction()
    data class ImportAudioFile(val uri: Uri) : HomeScreenAction()
    data object ShareActiveRecord : HomeScreenAction()
    data object ShowActiveRecordInfo : HomeScreenAction()
    data object OpenActiveRecordWithAnotherApp : HomeScreenAction()
    data object DeleteActiveRecord : HomeScreenAction()
    data object SaveActiveRecordAs : HomeScreenAction()
    data class RenameActiveRecord(val newName: String) : HomeScreenAction()
    data object OnSeekStart : HomeScreenAction()
    data object OnPlayClick : HomeScreenAction()
    data object OnPauseClick : HomeScreenAction()
    data object OnStopClick : HomeScreenAction()
    data class OnSeekProgress(val mills: Long) : HomeScreenAction()
    data class OnSeekEnd(val mills: Long) : HomeScreenAction()
    data class OnProgressBarStateChange(val value: Float) : HomeScreenAction()
}

sealed class HomeScreenEvent {
    data object ShowImportErrorError : HomeScreenEvent()
    data class RecordInformationEvent(val recordInfo: RecordInfoState) : HomeScreenEvent()
}

private class LongEvaluator : TypeEvaluator<Long> {
    override fun evaluate(fraction: Float, startValue: Long, endValue: Long): Long {
        return startValue + ((endValue - startValue) * fraction).toLong()
    }
}