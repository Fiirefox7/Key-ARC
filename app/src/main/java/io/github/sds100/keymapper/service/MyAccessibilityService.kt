package io.github.sds100.keymapper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.FingerprintGestureController
import android.bluetooth.BluetoothDevice
import android.content.*
import android.media.AudioManager
import android.os.Build.*
import android.os.SystemClock
import android.os.VibrationEffect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.lifecycle.*
import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import io.github.sds100.keymapper.Constants.PACKAGE_NAME
import io.github.sds100.keymapper.NotificationController
import io.github.sds100.keymapper.R
import io.github.sds100.keymapper.ServiceLocator
import io.github.sds100.keymapper.data.*
import io.github.sds100.keymapper.data.model.Action
import io.github.sds100.keymapper.data.model.KeyMap
import io.github.sds100.keymapper.data.model.Trigger
import io.github.sds100.keymapper.data.repository.FingerprintMapRepository
import io.github.sds100.keymapper.globalPreferences
import io.github.sds100.keymapper.util.*
import io.github.sds100.keymapper.util.delegate.*
import io.github.sds100.keymapper.util.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import splitties.bitflags.hasFlag
import splitties.bitflags.minusFlag
import splitties.bitflags.withFlag
import splitties.systemservices.displayManager
import splitties.systemservices.vibrator
import splitties.toast.toast
import timber.log.Timber

/**
 * Created by sds100 on 05/04/2020.
 */
class MyAccessibilityService : AccessibilityService(),
    LifecycleOwner,
    IClock,
    IAccessibilityService,
    IConstraintState,
    IActionError {

    companion object {

        const val ACTION_START_SERVICE = "$PACKAGE_NAME.START_ACCESSIBILITY_SERVICE"
        const val ACTION_STOP_SERVICE = "$PACKAGE_NAME.STOP_ACCESSIBILITY_SERVICE"
        const val ACTION_SHOW_KEYBOARD = "$PACKAGE_NAME.SHOW_KEYBOARD"
        const val ACTION_RECORD_TRIGGER = "$PACKAGE_NAME.RECORD_TRIGGER"
        const val ACTION_TEST_ACTION = "$PACKAGE_NAME.TEST_ACTION"
        const val ACTION_RECORDED_TRIGGER_KEY = "$PACKAGE_NAME.RECORDED_TRIGGER_KEY"
        const val ACTION_RECORD_TRIGGER_TIMER_INCREMENTED = "$PACKAGE_NAME.RECORD_TRIGGER_TIMER_INCREMENTED"
        const val ACTION_STOP_RECORDING_TRIGGER = "$PACKAGE_NAME.STOP_RECORDING_TRIGGER"
        const val ACTION_STOPPED_RECORDING_TRIGGER = "$PACKAGE_NAME.STOPPED_RECORDING_TRIGGER"
        const val ACTION_ON_START = "$PACKAGE_NAME.ON_ACCESSIBILITY_SERVICE_START"
        const val ACTION_ON_STOP = "$PACKAGE_NAME.ON_ACCESSIBILITY_SERVICE_STOP"
        const val ACTION_UPDATE_KEYMAP_LIST_CACHE = "$PACKAGE_NAME.UPDATE_KEYMAP_LIST_CACHE"

        //DONT CHANGE!!!
        const val ACTION_TRIGGER_KEYMAP_BY_UID = "$PACKAGE_NAME.TRIGGER_KEYMAP_BY_UID"
        const val EXTRA_KEYMAP_UID = "$PACKAGE_NAME.KEYMAP_UID"

        const val EXTRA_KEY_EVENT = "$PACKAGE_NAME.KEY_EVENT"
        const val EXTRA_TIME_LEFT = "$PACKAGE_NAME.TIME_LEFT"
        const val EXTRA_ACTION = "$PACKAGE_NAME.ACTION"
        const val EXTRA_KEYMAP_LIST = "$PACKAGE_NAME.KEYMAP_LIST"

        /**
         * How long should the accessibility service record a trigger in seconds.
         */
        private const val RECORD_TRIGGER_TIMER_LENGTH = 5
    }

    /**
     * Broadcast receiver for all intents sent from within the app.
     */
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: return

                    if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                        connectedBtAddresses.remove(device.address)
                    } else {
                        connectedBtAddresses.add(device.address)
                    }
                }

                ACTION_SHOW_KEYBOARD -> {
                    if (VERSION.SDK_INT >= VERSION_CODES.N) {
                        softKeyboardController.show(this@MyAccessibilityService)
                    }
                }

                ACTION_RECORD_TRIGGER -> {
                    //don't start recording if a trigger is being recorded
                    if (!recordingTrigger) {
                        recordingTriggerJob = recordTrigger()
                    }
                }

                ACTION_TEST_ACTION -> {
                    intent.getParcelableExtra<Action>(EXTRA_ACTION)?.let {
                        actionPerformerDelegate.performAction(
                            it,
                            chosenImePackageName,
                            currentPackageName
                        )
                    }
                }

                ACTION_STOP_RECORDING_TRIGGER -> {
                    val wasRecordingTrigger = recordingTrigger

                    recordingTriggerJob?.cancel()
                    recordingTriggerJob = null

                    if (wasRecordingTrigger) {
                        sendPackageBroadcast(ACTION_STOPPED_RECORDING_TRIGGER)
                    }
                }

                ACTION_UPDATE_KEYMAP_LIST_CACHE -> {
                    intent.getStringExtra(EXTRA_KEYMAP_LIST)?.let {
                        val keymapList = Gson().fromJson<List<KeyMap>>(it)

                        updateKeymapListCache(keymapList)
                    }
                }

                Intent.ACTION_SCREEN_ON -> {
                    _isScreenOn = true
                    getEventDelegate.stopListening()
                }

                Intent.ACTION_SCREEN_OFF -> {
                    _isScreenOn = false

                    lifecycleScope.launchWhenCreated {
                        val hasRootPermission = globalPreferences.hasRootPermission.first()

                        if (hasRootPermission && screenOffTriggersEnabled) {
                            if (!getEventDelegate.startListening(lifecycleScope)) {
                                toast(R.string.error_failed_execute_getevent)
                            }
                        }
                    }
                }

                ACTION_TRIGGER_KEYMAP_BY_UID -> {
                    intent.getStringExtra(EXTRA_KEYMAP_UID)?.let {
                        triggerKeymapByIntentController.onDetected(it)
                    }
                }

                Intent.ACTION_INPUT_METHOD_CHANGED -> {
                    chosenImePackageName =
                        KeyboardUtils.getChosenInputMethodPackageName(this@MyAccessibilityService)
                            .valueOrNull()
                }
            }
        }
    }

    private var recordingTriggerJob: Job? = null

    private val recordingTrigger: Boolean
        get() = recordingTriggerJob != null

    private var screenOffTriggersEnabled = false

    private lateinit var lifecycleRegistry: LifecycleRegistry

    private lateinit var keymapDetectionDelegate: KeymapDetectionDelegate
    private lateinit var actionPerformerDelegate: ActionPerformerDelegate
    private lateinit var constraintDelegate: ConstraintDelegate

    private lateinit var triggerKeymapByIntentController: TriggerKeymapByIntentController

    //fingerprint gesture stuff
    private lateinit var fingerprintGestureMapController: FingerprintGestureMapController

    private var fingerprintGestureCallback:
        FingerprintGestureController.FingerprintGestureCallback? = null

    override val currentTime: Long
        get() = SystemClock.elapsedRealtime()

    override val currentPackageName: String?
        get() = rootInActiveWindow?.packageName?.toString()

    private var _isScreenOn = true
    override val isScreenOn: Boolean
        get() = _isScreenOn

    override val orientation: Int?
        get() = displayManager.displays[0].rotation

    override val highestPriorityPackagePlayingMedia: String?
        get() = if (VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP) {
            MediaUtils.highestPriorityPackagePlayingMedia(this).valueOrNull()
        } else {
            null
        }

    override val fingerprintGestureDetectionAvailable: Boolean
        get() = if (VERSION.SDK_INT >= VERSION_CODES.O) {
            fingerprintGestureController.isGestureDetectionAvailable
        } else {
            false
        }

    private val connectedBtAddresses = mutableSetOf<String>()

    private var chosenImePackageName: String? = null

    private val isCompatibleImeChosen
        get() = KeyboardUtils.KEY_MAPPER_IME_PACKAGE_LIST.contains(chosenImePackageName)

    private val getEventDelegate = GetEventDelegate { keyCode, action, deviceDescriptor, isExternal, deviceId ->

        if (!globalPreferences.keymapsPaused.firstBlocking()) {
            withContext(Dispatchers.Main.immediate) {
                keymapDetectionDelegate.onKeyEvent(
                    keyCode,
                    action,
                    deviceDescriptor,
                    isExternal,
                    0,
                    deviceId
                )
            }
        }
    }

    private val notificationController: NotificationController
        get() = ServiceLocator.notificationController(this)

    private lateinit var controller: AccessibilityServiceController

    override fun onServiceConnected() {
        super.onServiceConnected()

        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val preferences = KeymapDetectionPreferences(
            globalPreferences.longPressDelay.firstBlocking(),
            globalPreferences.doublePressDelay.firstBlocking(),
            globalPreferences.repeatDelay.firstBlocking(),
            globalPreferences.repeatRate.firstBlocking(),
            globalPreferences.sequenceTriggerTimeout.firstBlocking(),
            globalPreferences.vibrationDuration.firstBlocking(),
            globalPreferences.holdDownDuration.firstBlocking(),
            globalPreferences.getFlow(Keys.forceVibrate).firstBlocking() ?: false
        )

        constraintDelegate = ConstraintDelegate(this)

        keymapDetectionDelegate = KeymapDetectionDelegate(
            lifecycleScope,
            preferences,
            iClock = this,
            iActionError = this,
            constraintDelegate)

        actionPerformerDelegate = ActionPerformerDelegate(
            context = this,
            iAccessibilityService = this,
            lifecycle = lifecycle)

        triggerKeymapByIntentController = TriggerKeymapByIntentController(
            coroutineScope = lifecycleScope,
            globalPreferences,
            constraintDelegate,
            iActionError = this
        )

        subscribeToPreferenceChanges()

        IntentFilter().apply {
            addAction(ACTION_SHOW_KEYBOARD)
            addAction(ACTION_RECORD_TRIGGER)
            addAction(ACTION_TEST_ACTION)
            addAction(ACTION_STOPPED_RECORDING_TRIGGER)
            addAction(ACTION_UPDATE_KEYMAP_LIST_CACHE)
            addAction(ACTION_STOP_RECORDING_TRIGGER)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_TRIGGER_KEYMAP_BY_UID)
            addAction(Intent.ACTION_INPUT_METHOD_CHANGED)

            registerReceiver(broadcastReceiver, this)
        }

        notificationController.onEvent(OnAccessibilityServiceStarted)
        sendPackageBroadcast(ACTION_ON_START)

        keymapDetectionDelegate.imitateButtonPress.observe(this, Observer {
            when (it.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> AudioUtils.adjustVolume(this, AudioManager.ADJUST_RAISE,
                    showVolumeUi = true)

                KeyEvent.KEYCODE_VOLUME_DOWN -> AudioUtils.adjustVolume(this, AudioManager.ADJUST_LOWER,
                    showVolumeUi = true)

                KeyEvent.KEYCODE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                KeyEvent.KEYCODE_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                KeyEvent.KEYCODE_APP_SWITCH -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                KeyEvent.KEYCODE_MENU ->
                    actionPerformerDelegate.performSystemAction(
                        SystemAction.OPEN_MENU,
                        chosenImePackageName,
                        currentPackageName
                    )

                else -> {
                    chosenImePackageName?.let { imePackageName ->
                        KeyboardUtils.inputKeyEventFromImeService(
                            this,
                            imePackageName = imePackageName,
                            keyCode = it.keyCode,
                            metaState = it.metaState,
                            keyEventAction = it.keyEventAction,
                            deviceId = it.deviceId,
                            scanCode = it.scanCode
                        )
                    }
                }
            }
        })

        chosenImePackageName = KeyboardUtils.getChosenInputMethodPackageName(this).valueOrNull()

        fingerprintGestureMapController = FingerprintGestureMapController(
            lifecycleScope,
            globalPreferences,
            iConstraintDelegate = constraintDelegate,
            iActionError = this
        )

        if (VERSION.SDK_INT >= VERSION_CODES.O) {

            checkFingerprintGesturesAvailability()

            fingerprintGestureCallback =
                object : FingerprintGestureController.FingerprintGestureCallback() {

                    override fun onGestureDetected(gesture: Int) {
                        super.onGestureDetected(gesture)

                        fingerprintGestureMapController.onGesture(gesture)
                    }
                }

            observeFingerprintMaps(ServiceLocator.fingerprintMapRepository(this))

            fingerprintGestureCallback?.let {
                fingerprintGestureController.registerFingerprintGestureCallback(it, null)
            }
        }

        lifecycleScope.launchWhenStarted {
            val keymapList = withContext(Dispatchers.IO) {
                ServiceLocator.keymapRepository(this@MyAccessibilityService).getKeymaps()
            }

            withContext(Dispatchers.Main) {
                updateKeymapListCache(keymapList)
            }
        }

        MediatorLiveData<Vibrate>().apply {
            addSource(keymapDetectionDelegate.vibrate) {
                value = it
            }

            addSource(fingerprintGestureMapController.vibrateEvent) {
                value = it
            }

            addSource(triggerKeymapByIntentController.vibrateEvent) {
                value = it
            }

            observe(this@MyAccessibilityService, Observer {
                if (it.duration <= 0) return@Observer

                if (VERSION.SDK_INT >= VERSION_CODES.O) {
                    val effect =
                        VibrationEffect.createOneShot(it.duration, VibrationEffect.DEFAULT_AMPLITUDE)

                    vibrator.vibrate(effect)
                } else {
                    vibrator.vibrate(it.duration)
                }
            })
        }

        MediatorLiveData<PerformAction>().apply {
            addSource(keymapDetectionDelegate.performAction) {
                value = it
            }

            addSource(fingerprintGestureMapController.performAction) {
                value = it
            }

            addSource(triggerKeymapByIntentController.performAction) {
                value = it
            }

            observe(this@MyAccessibilityService, Observer {
                actionPerformerDelegate.performAction(
                    it,
                    chosenImePackageName,
                    currentPackageName
                )
            })
        }

        MediatorLiveData<Unit>().apply {
            addSource(keymapDetectionDelegate.showTriggeredKeymapToast) {
                value = it
            }

            addSource(triggerKeymapByIntentController.showTriggeredToastEvent) {
                value = it
            }

            addSource(fingerprintGestureMapController.showTriggeredToastEvent) {
                value = it
            }

            observe(this@MyAccessibilityService, Observer {
                toast(R.string.toast_triggered_keymap)
            })
        }

        controller = AccessibilityServiceController(
            lifecycleOwner = this,
            iConstraintState = this,
            iAccessibilityService = this,
            appUpdateManager = ServiceLocator.appUpdateManager(this)
        )

        controller.eventStream.observe(this, Observer {
            when (it) {
                is ShowFingerprintFeatureNotification ->
                    notificationController.onEvent(ShowFingerprintFeatureNotification)
            }
        })
    }

    override fun onInterrupt() {}

    override fun onDestroy() {

        if (::lifecycleRegistry.isInitialized) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }

        notificationController.onEvent(OnAccessibilityServiceStopped)

        sendPackageBroadcast(ACTION_ON_STOP)

        unregisterReceiver(broadcastReceiver)

        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            fingerprintGestureController
                .unregisterFingerprintGestureCallback(fingerprintGestureCallback)

            fingerprintGestureMapController.reset()
        }

        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return super.onKeyEvent(event)

        if (recordingTrigger) {
            if (event.action == KeyEvent.ACTION_DOWN) {

                //tell the UI that a key has been pressed
                sendPackageBroadcast(ACTION_RECORDED_TRIGGER_KEY, bundleOf(EXTRA_KEY_EVENT to event))
            }

            return true
        }

        if (!globalPreferences.keymapsPaused.firstBlocking()) {
            try {
                val consume = keymapDetectionDelegate.onKeyEvent(
                    event.keyCode,
                    event.action,
                    event.device.descriptor,
                    event.device.isExternalCompat,
                    event.metaState,
                    event.deviceId,
                    event.scanCode)

                return consume
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        return super.onKeyEvent(event)
    }

    override fun isBluetoothDeviceConnected(address: String) = connectedBtAddresses.contains(address)

    override fun canActionBePerformed(action: Action): Result<Action> {
        if (action.requiresIME) {
            return if (isCompatibleImeChosen) {
                Success(action)
            } else {
                NoCompatibleImeChosen()
            }
        }

        return action.canBePerformed(this)
    }

    override fun getLifecycle() = lifecycleRegistry

    override val keyboardController: SoftKeyboardController?
        get() = if (VERSION.SDK_INT >= VERSION_CODES.N) {
            softKeyboardController
        } else {
            null
        }

    override val rootNode: AccessibilityNodeInfo?
        get() = rootInActiveWindow

    override fun requestFingerprintGestureDetection() {
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            serviceInfo = serviceInfo.apply {
                flags = flags.withFlag(AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES)
            }
        }
    }

    override fun denyFingerprintGestureDetection() {
        if (VERSION.SDK_INT >= VERSION_CODES.O) {
            serviceInfo = serviceInfo?.apply {
                flags = flags.minusFlag(AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES)
            }
        }
    }

    @RequiresApi(VERSION_CODES.O)
    private fun observeFingerprintMaps(repository: FingerprintMapRepository) {

        repository.fingerprintGestureMapsLiveData.observe(this, Observer { maps ->
            fingerprintGestureMapController.fingerprintMaps = maps

            invalidateFingerprintGestureDetection()
        })
    }

    @RequiresApi(VERSION_CODES.O)
    private fun invalidateFingerprintGestureDetection() {
        fingerprintGestureMapController.fingerprintMaps.let { maps ->
            if (maps.any { it.value.isEnabled && it.value.actionList.isNotEmpty() }
                && !globalPreferences.keymapsPaused.firstBlocking()) {
                requestFingerprintGestureDetection()
            } else {
                denyFingerprintGestureDetection()
            }
        }
    }

    private fun recordTrigger() = lifecycleScope.launchWhenStarted {
        repeat(RECORD_TRIGGER_TIMER_LENGTH) { iteration ->
            if (isActive) {
                val timeLeft = RECORD_TRIGGER_TIMER_LENGTH - iteration

                sendPackageBroadcast(
                    ACTION_RECORD_TRIGGER_TIMER_INCREMENTED,
                    bundleOf(EXTRA_TIME_LEFT to timeLeft)
                )

                delay(1000)
            }
        }

        sendPackageBroadcast(ACTION_STOP_RECORDING_TRIGGER)
    }

    private fun updateKeymapListCache(keymapList: List<KeyMap>) {
        keymapDetectionDelegate.keymapListCache = keymapList

        screenOffTriggersEnabled = keymapList.any { keymap ->
            keymap.trigger.flags.hasFlag(Trigger.TRIGGER_FLAG_SCREEN_OFF_TRIGGERS)
        }

        triggerKeymapByIntentController.onKeymapListUpdate(keymapList)
    }

    private fun checkFingerprintGesturesAvailability() {
        requestFingerprintGestureDetection()

        //this is important
        runBlocking {
            if (VERSION.SDK_INT >= VERSION_CODES.O) {
                val repository = ServiceLocator.fingerprintMapRepository(this@MyAccessibilityService)

                /* Don't update whether fingerprint gesture detection is supported if it has
                * been supported at some point. Just in case the fingerprint reader is being
                * used while this is called. */
                if (repository.fingerprintGesturesAvailable.first() != true) {
                    repository.setFingerprintGesturesAvailable(
                        fingerprintGestureController.isGestureDetectionAvailable)
                }
            }
        }

        denyFingerprintGestureDetection()
    }

    private fun subscribeToPreferenceChanges() {
        globalPreferences.longPressDelay.collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.defaultLongPressDelay = it
        }

        globalPreferences.doublePressDelay.collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.defaultDoublePressDelay = it
        }

        globalPreferences.repeatDelay.collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.defaultRepeatDelay = it
        }

        globalPreferences.repeatRate.collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.defaultRepeatRate = it
        }

        globalPreferences.sequenceTriggerTimeout.collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.defaultSequenceTriggerTimeout = it
        }

        globalPreferences.vibrationDuration.collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.defaultVibrateDuration = it
        }

        globalPreferences.holdDownDuration.collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.defaultHoldDownDuration = it
        }

        globalPreferences.getFlow(Keys.forceVibrate).collectWhenStarted(this) {
            keymapDetectionDelegate.preferences.forceVibrate = it ?: false
        }

        globalPreferences.keymapsPaused.collectWhenStarted(this) { paused ->
            keymapDetectionDelegate.reset()

            if (paused) {
                globalPreferences.getFlow(Keys.toggleKeyboardOnToggleKeymaps)
                    .firstBlocking()
                    .let {
                        if (it == true) {
                            KeyboardUtils.chooseLastUsedIncompatibleInputMethod(this)
                        }
                    }

                if (VERSION.SDK_INT >= VERSION_CODES.O) {
                    denyFingerprintGestureDetection()
                }
            } else {
                globalPreferences.getFlow(Keys.toggleKeyboardOnToggleKeymaps)
                    .firstBlocking()
                    .let {
                        if (it == true) {
                            KeyboardUtils.chooseCompatibleInputMethod(this)
                        }
                    }

                if (VERSION.SDK_INT >= VERSION_CODES.O) {
                    requestFingerprintGestureDetection()
                }
            }
        }
    }
}