package io.github.sds100.keymapper.actions.sound

import io.github.sds100.keymapper.system.files.FileAdapter
import io.github.sds100.keymapper.system.files.IFile
import io.github.sds100.keymapper.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*
import javax.inject.Inject

/**
 * Created by sds100 on 24/06/2021.
 */

class SoundsManagerImpl @Inject constructor(
    private val coroutineScope: CoroutineScope,
    private val fileAdapter: FileAdapter,
) : SoundsManager {
    companion object {
        private const val SOUNDS_DIR_NAME = "sounds"
    }

    override val soundFiles = MutableStateFlow<List<SoundFileInfo>>(emptyList())

    init {
        coroutineScope.launch {
            updateSoundFilesFlow()
        }
    }

    override suspend fun saveNewSound(uri: String, name: String): Result<String> {
        val uid = UUID.randomUUID().toString()

        val soundFile = fileAdapter.getFileFromUri(uri)
        val newFileName = createSoundCopyFileName(uid, name, soundFile.extension ?: "")

        val soundsDir = fileAdapter.getPrivateFile(SOUNDS_DIR_NAME)
        soundsDir.createDirectory()

        return soundFile.copyTo(soundsDir, newFileName)
            .then { Success(uid) }
            .onSuccess { updateSoundFilesFlow() }
            .onFailure {
                if (it is Error.Exception) {
                    Timber.d(it.exception)
                }
            }
    }

    override suspend fun restoreSound(file: IFile): Result<*> {

        val soundsDir = fileAdapter.getPrivateFile(SOUNDS_DIR_NAME)
        soundsDir.createDirectory()

        //Don't copy it if the file already exists
        if (fileAdapter.getFile(soundsDir, file.name!!).exists()) {
            return Success(Unit)
        } else {
            val result = file.copyTo(soundsDir)
            updateSoundFilesFlow()

            return result
        }
    }

    override fun getSound(uid: String): Result<IFile> {
        val soundsDir = fileAdapter.getPrivateFile(SOUNDS_DIR_NAME)
        soundsDir.createDirectory()

        val matchingFile = soundsDir.listFiles()!!.find { it.name?.contains(uid) == true }

        if (matchingFile == null) {
            return Error.CantFindSoundFile
        } else {
            return Success(matchingFile)
        }
    }

    override fun deleteSound(uid: String): Result<*> {
        return getSound(uid)
            .then { Success(it.delete()) }
            .onSuccess { updateSoundFilesFlow() }
    }

    private fun getSoundFileInfo(fileName: String): SoundFileInfo {
        val name = fileName.substringBeforeLast('_')
        val extension = fileName.substringAfterLast('.')

        val uid = fileName
            .substringBeforeLast('.')// e.g "bla_32a3b1289"
            .substringAfterLast('_')

        return SoundFileInfo(uid, "$name.$extension")
    }

    private fun updateSoundFilesFlow() {
        val soundsDir = fileAdapter.getPrivateFile(SOUNDS_DIR_NAME)
        soundsDir.createDirectory()

        soundFiles.value = soundsDir
            .listFiles()!!
            .map { getSoundFileInfo(it.name!!) }
    }

    private fun createSoundCopyFileName(uid: String, name: String, extension: String): String {
        return buildString {
            append(name)
            append("_$uid")
            append(".$extension")
        }
    }
}

interface SoundsManager {
    val soundFiles: StateFlow<List<SoundFileInfo>>

    /**
     * @return the sound file uid
     */
    suspend fun saveNewSound(uri: String, name: String): Result<String>
    suspend fun restoreSound(file: IFile): Result<*>
    fun getSound(uid: String): Result<IFile>
    fun deleteSound(uid: String): Result<*>
}