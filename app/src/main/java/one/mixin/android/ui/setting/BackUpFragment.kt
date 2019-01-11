package one.mixin.android.ui.setting

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.tbruyelle.rxpermissions2.RxPermissions
import kotlinx.android.synthetic.main.fragment_backup.*
import kotlinx.android.synthetic.main.view_title.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.mixin.android.Constants.BackUp.BACKUP_PERIOD
import one.mixin.android.R
import one.mixin.android.extension.defaultSharedPreferences
import one.mixin.android.extension.fileSize
import one.mixin.android.extension.openPermissionSetting
import one.mixin.android.extension.putInt
import one.mixin.android.job.BackupJob
import one.mixin.android.job.MixinJobManager
import one.mixin.android.ui.common.BaseFragment
import one.mixin.android.util.backup.Result
import one.mixin.android.util.backup.findBackup
import java.util.Date
import javax.inject.Inject

class BackUpFragment : BaseFragment() {
    companion object {
        const val TAG = "BackUpFragment"
        fun newInstance() = BackUpFragment()
    }

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject
    lateinit var jobManager: MixinJobManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_backup, container, false)

    @SuppressLint("CheckResult")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        backup_info.text = getString(R.string.backup_external_storage, "")
        backup_auto.setOnClickListener {
            showBackupDialog()
        }
        backup_auto_tv.text = options[defaultSharedPreferences.getInt(BACKUP_PERIOD, 0)]
        title_view.left_ib.setOnClickListener { activity?.onBackPressed() }
        backup_bn.setOnClickListener {
            RxPermissions(requireActivity())
                .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .subscribe({ granted ->
                    if (granted) {
                        jobManager.addJobInBackground(BackupJob(true))
                    } else {
                        context?.openPermissionSetting()
                    }
                }, {
                    context?.openPermissionSetting()
                })
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            findBackUp()
        }

        BackupJob.backupLiveData.observe(this, Observer {
            if (it) {
                backup_bn.visibility = View.INVISIBLE
                progressGroup.visibility = View.VISIBLE
            } else {
                backup_bn.visibility = View.VISIBLE
                progressGroup.visibility = View.GONE
                if (BackupJob.backupLiveData.result == Result.SUCCESS) {
                    findBackUp()
                }
            }
        })
    }

    private val options by lazy {
        requireContext().resources.getStringArray(R.array.backup_dialog_list)
    }

    private fun showBackupDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.backup_dialog_title)

        val checkedItem = defaultSharedPreferences.getInt(BACKUP_PERIOD, 0)
        builder.setSingleChoiceItems(options, checkedItem) { dialog, which ->
            backup_auto_tv.text = options[which]
            defaultSharedPreferences.putInt(BACKUP_PERIOD, which)
            dialog.dismiss()
        }
        builder.setNegativeButton(android.R.string.cancel) { _, _ ->

        }
        val dialog = builder.create()
        dialog.show()
    }

    @RequiresPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    private fun findBackUp() {
        GlobalScope.launch {
            val file = findBackup(requireContext(), coroutineContext)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (file == null) {
                    backup_info.text = getString(R.string.backup_external_storage, getString(R.string.backup_never))
                    backup_size.visibility = GONE
                } else {
                    val time = file.lastModified().run {
                        val now = Date().time
                        val createTime = file.lastModified()
                        DateUtils.getRelativeTimeSpanString(createTime, now, when {
                            ((now - createTime) < 60000L) -> DateUtils.SECOND_IN_MILLIS
                            ((now - createTime) < 3600000L) -> DateUtils.MINUTE_IN_MILLIS
                            ((now - createTime) < 86400000L) -> DateUtils.HOUR_IN_MILLIS
                            else -> DateUtils.DAY_IN_MILLIS
                        })
                    }
                    backup_info.text = getString(R.string.backup_external_storage, time)
                    backup_size.text = getString(R.string.restore_size, file.length().fileSize())
                    backup_size.visibility = VISIBLE
                }
            }
        }
    }
}