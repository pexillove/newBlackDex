package top.niunaijun.blackdex.view.main

import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackdex.data.DexDumpRepository
import top.niunaijun.blackdex.data.entity.AppInfo
import top.niunaijun.blackdex.data.entity.DumpInfo
import top.niunaijun.blackdex.view.base.BaseViewModel
import top.niunaijun.blackbox.entity.dump.DumpResult

class MainViewModel(private val repo: DexDumpRepository) : BaseViewModel() {

    val mAppListLiveData = MutableLiveData<List<AppInfo>>()
    val mDexDumpLiveData = MutableLiveData<DumpInfo>()
    val mProgressLiveData = MutableLiveData<DumpResult>()
    val mArm64ProgressLiveData = MutableLiveData<DumpResult>()
    val mArm32ProgressLiveData = MutableLiveData<DumpResult>()

    fun getAppList() {
        launchOnUI { repo.getAppList(mAppListLiveData) }
    }

    fun startDexDump(source: String) {
        launchOnUI {
            repo.dumpDex(source, mDexDumpLiveData, mProgressLiveData,
                mArm64ProgressLiveData, mArm32ProgressLiveData)
        }
    }

    fun dexDumpSuccess() {
        launchOnUI { repo.dumpSuccess() }
    }

    fun isDualDumping(): Boolean = repo.isDualDumping

    fun onLocalDumpComplete(result: DumpResult) {
        repo.onLocalDumpComplete?.invoke(result)
    }

    fun finishDualDump() {
        repo.finishDualDump(mDexDumpLiveData)
    }
}
