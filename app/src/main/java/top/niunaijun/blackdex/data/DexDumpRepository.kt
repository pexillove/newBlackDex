package top.niunaijun.blackdex.data

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.webkit.URLUtil
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.BlackBoxCore.getPackageManager
import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackbox.entity.dump.DumpResult
import top.niunaijun.blackbox.entity.pm.InstallResult
import top.niunaijun.blackbox.utils.AbiUtils
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.app.App
import top.niunaijun.blackdex.app.AppManager
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.data.entity.DumpInfo
import top.niunaijun.blackdex.util.HelperManager
import java.io.File

class DexDumpRepository {

    private var dumpTaskId = 0
    private var dualArm64Done = false
    private var dualArm32Done = false
    private var dualArm64Result: DumpInfo? = null
    private var dualArm32Result: DumpInfo? = null
    @Volatile
    var isDualDumping = false
    var onLocalDumpComplete: ((DumpResult) -> Unit)? = null
    private var helperTimeoutJob: Job? = null

    fun getAppList(mAppListLiveData: MutableLiveData<List<AppInfo>>) {
        val installedApplications: List<ApplicationInfo> =
                getPackageManager().getInstalledApplications(0)
        val installedList = mutableListOf<AppInfo>()
        val is32BitCompat = AppManager.mBlackBoxLoader.is32BitCompat()

        for (installedApplication in installedApplications) {
            val file = File(installedApplication.sourceDir)

            if ((installedApplication.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

            val abiType = AbiUtils.detectAbi(file)

            if (!is32BitCompat) {
                if (!AbiUtils.isSupport(file)) continue
            }

            val info = AppInfo(
                    installedApplication.loadLabel(getPackageManager()).toString(),
                    installedApplication.packageName,
                    installedApplication.loadIcon(getPackageManager()),
                    abiType
            )
            installedList.add(info)
        }

        mAppListLiveData.postValue(installedList)
    }

    fun dumpDex(
        source: String,
        dexDumpLiveData: MutableLiveData<DumpInfo>,
        progressLiveData: MutableLiveData<DumpResult>,
        arm64ProgressLiveData: MutableLiveData<DumpResult>? = null,
        arm32ProgressLiveData: MutableLiveData<DumpResult>? = null
    ) {
        val abiType = detectAbiFromSource(source)
        val isDualArch = AppManager.mBlackBoxLoader.isDualArchDump()
        val is32BitCompat = AppManager.mBlackBoxLoader.is32BitCompat()
        val isPkgName = !URLUtil.isValidUrl(source) && !source.contains("/")

        if (isDualArch && abiType == AbiUtils.AbiType.BOTH && isPkgName && is32BitCompat) {
            if (!checkHelperReady(dexDumpLiveData)) return
            startDualDump(source, dexDumpLiveData, arm64ProgressLiveData, arm32ProgressLiveData)
        } else if (abiType == AbiUtils.AbiType.ARM32_ONLY && isPkgName && is32BitCompat) {
            if (!checkHelperReady(dexDumpLiveData)) return
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.LOADING))
            dumpViaHelper(source, dexDumpLiveData, progressLiveData, null, "")
        } else {
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.LOADING))
            dumpLocally(source, dexDumpLiveData)
        }
    }

    private fun checkHelperReady(dexDumpLiveData: MutableLiveData<DumpInfo>): Boolean {
        if (!HelperManager.isHelperInstalled(App.getContext())) {
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.FAIL,
                App.getContext().getString(R.string.helper_not_installed)))
            return false
        }
        if (HelperManager.getHelperVersionCode(App.getContext()) < HelperManager.REQUIRED_HELPER_VERSION) {
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.FAIL,
                "Helper outdated. Please update helper in settings."))
            return false
        }
        return true
    }

    private fun startDualDump(
        source: String,
        dexDumpLiveData: MutableLiveData<DumpInfo>,
        arm64ProgressLiveData: MutableLiveData<DumpResult>?,
        arm32ProgressLiveData: MutableLiveData<DumpResult>?
    ) {
        dualArm64Done = false
        dualArm32Done = false
        dualArm64Result = null
        dualArm32Result = null
        isDualDumping = true
        helperTimeoutJob?.cancel()
        helperTimeoutJob = null
        dexDumpLiveData.postValue(DumpInfo(DumpInfo.LOADING))

        arm64ProgressLiveData?.postValue(DumpResult())
        arm32ProgressLiveData?.postValue(DumpResult())

        val loader = AppManager.mBlackBoxLoader
        val baseDir = loader.getBaseDumpDir()

        onLocalDumpComplete = { result ->
            if (result.isRunning) {
                arm64ProgressLiveData?.postValue(result)
            } else {
                arm64ProgressLiveData?.postValue(result)
                dualArm64Done = true
                dualArm64Result = if (result.isSuccess) {
                    DumpInfo(DumpInfo.SUCCESS,
                        App.getContext().getString(R.string.dex_save, result.dir))
                } else {
                    DumpInfo(DumpInfo.FAIL,
                        App.getContext().getString(R.string.error_msg, result.msg))
                }
                checkDualComplete(dexDumpLiveData)
            }
        }

        dumpLocally(source, object : MutableLiveData<DumpInfo>() {
            override fun postValue(value: DumpInfo?) {
                super.postValue(value)
                if (value != null && value.state != DumpInfo.LOADING) {
                    dualArm64Done = true
                    dualArm64Result = value
                    checkDualComplete(dexDumpLiveData)
                }
            }
        })

        val helperDumpDir = baseDir
        dumpViaHelper(source, object : MutableLiveData<DumpInfo>() {
            override fun postValue(value: DumpInfo?) {
                super.postValue(value)
                if (value != null && value.state != DumpInfo.LOADING) {
                    dualArm32Done = true
                    dualArm32Result = value
                    checkDualComplete(dexDumpLiveData)
                }
            }
        }, arm32ProgressLiveData ?: MutableLiveData(), helperDumpDir, "arm32")
    }

    private fun checkDualComplete(dexDumpLiveData: MutableLiveData<DumpInfo>) {
        if (dualArm64Done && dualArm32Done) {
            isDualDumping = false
            onLocalDumpComplete = null
            helperTimeoutJob?.cancel()
            helperTimeoutJob = null
            val r64 = dualArm64Result
            val r32 = dualArm32Result
            val msg = buildString {
                append("ARM64: ")
                append(if (r64?.state == DumpInfo.SUCCESS) "成功" else "失败")
                if (r64?.msg?.isNotBlank() == true) append("\n  ${r64.msg}")
                append("\nARM32: ")
                append(if (r32?.state == DumpInfo.SUCCESS) "成功" else "失败")
                if (r32?.msg?.isNotBlank() == true) append("\n  ${r32.msg}")
            }
            val state = when {
                r64?.state == DumpInfo.SUCCESS || r32?.state == DumpInfo.SUCCESS -> DumpInfo.SUCCESS
                else -> DumpInfo.FAIL
            }
            dexDumpLiveData.postValue(DumpInfo(state, msg))
        }
    }

    fun finishDualDump(dexDumpLiveData: MutableLiveData<DumpInfo>) {
        if (!isDualDumping) return
        isDualDumping = false
        onLocalDumpComplete = null
        helperTimeoutJob?.cancel()
        helperTimeoutJob = null
        HelperManager.unregisterStatusReceiver(App.getContext())
        val r64 = dualArm64Result
        val r32 = dualArm32Result
        val msg = buildString {
            append("ARM64: ")
            append(when {
                r64?.state == DumpInfo.SUCCESS -> "成功"
                r64?.state == DumpInfo.FAIL -> "失败"
                r64 != null -> "超时"
                else -> "进行中"
            })
            if (r64?.msg?.isNotBlank() == true) append("\n  ${r64.msg}")
            append("\nARM32: ")
            append(when {
                r32?.state == DumpInfo.SUCCESS -> "成功"
                r32?.state == DumpInfo.FAIL -> "失败"
                r32 != null -> "超时"
                else -> "进行中"
            })
            if (r32?.msg?.isNotBlank() == true) append("\n  ${r32.msg}")
        }
        val state = when {
            r64?.state == DumpInfo.SUCCESS || r32?.state == DumpInfo.SUCCESS -> DumpInfo.SUCCESS
            else -> DumpInfo.FAIL
        }
        dexDumpLiveData.postValue(DumpInfo(state, msg))
    }

    private fun detectAbiFromSource(source: String): AbiUtils.AbiType {
        return if (URLUtil.isValidUrl(source)) {
            AbiUtils.AbiType.ARM64_ONLY
        } else if (source.contains("/")) {
            AbiUtils.detectAbi(File(source))
        } else {
            try {
                val info = getPackageManager().getPackageInfo(source, 0)
                AbiUtils.detectAbi(File(info.applicationInfo.sourceDir))
            } catch (e: Exception) {
                AbiUtils.AbiType.ARM64_ONLY
            }
        }
    }

    private fun dumpLocally(source: String, dexDumpLiveData: MutableLiveData<DumpInfo>) {
        val result = if (URLUtil.isValidUrl(source)) {
            BlackDexCore.get().dumpDex(Uri.parse(source))
        } else if (source.contains("/")) {
            BlackDexCore.get().dumpDex(File(source))
        } else {
            BlackDexCore.get().dumpDex(source)
        }

        if (result != null) {
            dumpTaskId++
            startCountdown(result, dexDumpLiveData)
        } else {
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.TIMEOUT))
        }
    }

    private fun dumpViaHelper(
        packageName: String,
        dexDumpLiveData: MutableLiveData<DumpInfo>,
        progressLiveData: MutableLiveData<DumpResult>,
        customDumpDir: String?,
        subDir: String = ""
    ) {
        val dumpDir = customDumpDir ?: BlackBoxCore.get().dexDumpDir
        val loader = AppManager.mBlackBoxLoader

        HelperManager.registerStatusReceiver(App.getContext()) { result ->
            if (result.isRunning) {
                progressLiveData.postValue(result)
            } else if (result.isSuccess) {
                HelperManager.unregisterStatusReceiver(App.getContext())
                helperTimeoutJob?.cancel()
                progressLiveData.postValue(result)
                dexDumpLiveData.postValue(DumpInfo(DumpInfo.SUCCESS,
                    App.getContext().getString(R.string.dex_save, result.dir)))
            } else if (result.isFail) {
                HelperManager.unregisterStatusReceiver(App.getContext())
                helperTimeoutJob?.cancel()
                progressLiveData.postValue(result)
                dexDumpLiveData.postValue(DumpInfo(DumpInfo.FAIL,
                    App.getContext().getString(R.string.error_msg, result.msg)))
            }
        }

        val started = HelperManager.startDump(
            App.getContext(),
            packageName, dumpDir, subDir,
            loader.isFixCodeItem(), loader.isHookDump(),
            loader.isAutoCallMethod(), loader.isVerifyDex()
        )

        if (!started) {
            HelperManager.unregisterStatusReceiver(App.getContext())
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.FAIL,
                "Helper failed to start dump"))
            return
        }

        helperTimeoutJob = GlobalScope.launch(Dispatchers.Main) {
            delay(300000)
            HelperManager.unregisterStatusReceiver(App.getContext())
            dexDumpLiveData.postValue(DumpInfo(DumpInfo.TIMEOUT))
        }
    }

    fun dumpSuccess() {
        dumpTaskId++
    }

    private fun startCountdown(installResult: InstallResult, dexDumpLiveData: MutableLiveData<DumpInfo>) {
        GlobalScope.launch {
            val tempId = dumpTaskId
            while (BlackDexCore.get().isRunning) {
                delay(3000)
                if (!AppManager.mBlackBoxLoader.isFixCodeItem()) {
                    break
                }
            }
            if (tempId == dumpTaskId) {
                if (BlackDexCore.get().isExistDexFile(installResult.packageName)) {
                    dexDumpLiveData.postValue(DumpInfo(
                            DumpInfo.SUCCESS,
                            App.getContext().getString(R.string.dex_save, File(BlackBoxCore.get().dexDumpDir, installResult.packageName).absolutePath)
                    ))
                } else {
                    dexDumpLiveData.postValue(DumpInfo(DumpInfo.TIMEOUT))
                }
            }
        }
    }
}
