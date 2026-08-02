package top.niunaijun.blackdex.view.widget

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import top.niunaijun.blackdex.R
import top.niunaijun.blackdex.databinding.DialogProgressBinding
import top.niunaijun.blackdex.util.inflate

class ProgressDialog : DialogFragment() {

    companion object {
        private const val ARG_DUAL_MODE = "dualMode"

        fun newInstance(dualMode: Boolean): ProgressDialog {
            return ProgressDialog().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_DUAL_MODE, dualMode)
                }
            }
        }
    }

    private val viewBinding: DialogProgressBinding by inflate()
    private var onFinishListener: (() -> Unit)? = null
    private var isDualMode = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isDualMode = arguments?.getBoolean(ARG_DUAL_MODE, false) ?: false

        val builder = AlertDialog.Builder(requireContext())
            .setView(viewBinding.root)
            .setTitle(getString(R.string.classes_progress, 1, 1))
            .setCancelable(false)
            .setNeutralButton("完成") { _, _ ->
                onFinishListener?.invoke()
            }

        val dialog = builder.show()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }

        if (isDualMode) {
            viewBinding.arm32Row.visibility = View.VISIBLE
            viewBinding.arm32Progress.visibility = View.VISIBLE
            viewBinding.arm64Label.visibility = View.VISIBLE
            (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.VISIBLE
        } else {
            viewBinding.arm32Row.visibility = View.GONE
            viewBinding.arm32Progress.visibility = View.GONE
            viewBinding.arm64Label.visibility = View.GONE
            (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
        }

        return dialog
    }

    fun setOnFinishListener(listener: () -> Unit) {
        onFinishListener = listener
    }

    fun setProgress(progress: Int, maxProgress: Int) {
        if (!isAdded) return
        viewBinding.root.post {
            if (!isAdded) return@post
            if (maxProgress > 0) {
                viewBinding.progress.max = maxProgress
            }
            viewBinding.progress.progress = progress
            if (!isDualMode) {
                dialog?.setTitle(getString(R.string.classes_progress, progress, maxProgress))
            } else {
                viewBinding.arm64Status.text = getString(R.string.classes_progress, progress, maxProgress)
            }
        }
    }

    fun setArm64Progress(curr: Int, total: Int) {
        if (!isAdded) return
        viewBinding.root.post {
            if (!isAdded) return@post
            if (total > 0) viewBinding.progress.max = total
            viewBinding.progress.progress = curr
            viewBinding.arm64Status.text = getString(R.string.classes_progress, curr, total)
        }
    }

    fun setArm64Done(success: Boolean) {
        if (!isAdded) return
        viewBinding.root.post {
            if (!isAdded) return@post
            viewBinding.arm64Status.text = if (success) "✓ 成功" else "✗ 失败"
            viewBinding.progress.max = 1
            viewBinding.progress.progress = 1
        }
    }

    fun setArm32Progress(curr: Int, total: Int) {
        if (!isAdded) return
        viewBinding.root.post {
            if (!isAdded) return@post
            if (total > 0) viewBinding.arm32Progress.max = total
            viewBinding.arm32Progress.progress = curr
            viewBinding.arm32Status.text = getString(R.string.classes_progress, curr, total)
        }
    }

    fun setArm32Done(success: Boolean) {
        if (!isAdded) return
        viewBinding.root.post {
            if (!isAdded) return@post
            viewBinding.arm32Status.text = if (success) "✓ 成功" else "✗ 失败"
            viewBinding.arm32Progress.max = 1
            viewBinding.arm32Progress.progress = 1
        }
    }
}
