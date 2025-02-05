package one.mixin.android.ui.conversation

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.CountDownTimer
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import dagger.hilt.android.AndroidEntryPoint
import one.mixin.android.Constants
import one.mixin.android.Constants.Account.PREF_DUPLICATE_TRANSFER
import one.mixin.android.Constants.Account.PREF_STRANGER_TRANSFER
import one.mixin.android.R
import one.mixin.android.api.response.PaymentStatus
import one.mixin.android.databinding.FragmentPreconditionBottomSheetBinding
import one.mixin.android.extension.defaultSharedPreferences
import one.mixin.android.extension.dp
import one.mixin.android.extension.formatPublicKey
import one.mixin.android.extension.getRelativeTimeSpan
import one.mixin.android.extension.numberFormat2
import one.mixin.android.extension.withArgs
import one.mixin.android.session.Session
import one.mixin.android.ui.common.MixinBottomSheetDialogFragment
import one.mixin.android.ui.common.biometric.BiometricItem
import one.mixin.android.ui.common.biometric.TransferBiometricItem
import one.mixin.android.ui.common.biometric.ValuableBiometricBottomSheetDialogFragment
import one.mixin.android.ui.common.biometric.WithdrawBiometricItem
import one.mixin.android.ui.common.biometric.displayAddress
import one.mixin.android.util.viewBinding
import one.mixin.android.vo.Fiats
import one.mixin.android.vo.UserRelationship
import one.mixin.android.widget.BottomSheet
import org.jetbrains.anko.textColor
import java.math.BigDecimal

@AndroidEntryPoint
class PreconditionBottomSheetDialogFragment : MixinBottomSheetDialogFragment() {
    companion object {
        const val TAG = "PreconditionBottomSheetDialogFragment"
        const val ARGS_FROM = "args_from"
        const val FROM_LINK = 0
        const val FROM_TRANSFER = 1

        inline fun <reified T : BiometricItem> newInstance(t: T, from: Int) =
            PreconditionBottomSheetDialogFragment().withArgs {
                putParcelable(ValuableBiometricBottomSheetDialogFragment.ARGS_BIOMETRIC_ITEM, t)
                putInt(ARGS_FROM, from)
            }
    }

    private val t: BiometricItem by lazy {
        requireArguments().getParcelable(ValuableBiometricBottomSheetDialogFragment.ARGS_BIOMETRIC_ITEM)!!
    }
    private val from: Int by lazy { requireArguments().getInt(ARGS_FROM, FROM_LINK) }

    private val binding by viewBinding(FragmentPreconditionBottomSheetBinding::inflate)

    private var mCountDownTimer: CountDownTimer? = null

    @SuppressLint("RestrictedApi", "SetTextI18n")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        contentView = binding.root
        (dialog as BottomSheet).run {
            setCustomView(contentView)
            dismissClickOutside = false
        }
        binding.cancelTv.setOnClickListener {
            callback?.onCancel()
            dismiss()
        }

        val t = this.t
        binding.assetBalance.setInfo(t)
        if (t.state == PaymentStatus.pending.name) {
            if (t is TransferBiometricItem) {
                if (shouldShowStrangerTransferTip(t)) {
                    binding.assetBalance.setInfoWithUser(t)
                    showStrangerTip(t)
                } else {
                    checkTransferTrace(t)
                }
            } else if (t is WithdrawBiometricItem) {
                checkWithdrawTrace(t)
            }
        } else if (t.state == PaymentStatus.paid.name) {
            callback?.onSuccess()
            contentView.post { dismiss() }
        }
    }

    override fun dismiss() {
        mCountDownTimer?.cancel()
        super.dismiss()
    }

    private fun checkTransferTrace(t: TransferBiometricItem) {
        val trace = t.trace
        if (trace == null || isDuplicateTransferDisable()) {
            if (shouldShowTransferTip(t)) {
                showLargeAmountTip(t)
            } else {
                callback?.onSuccess()
                binding.root.post { dismiss() }
            }
            return
        }

        val time = trace.createdAt.getRelativeTimeSpan()
        val amount = "${t.amount} ${t.asset.symbol}"
        binding.titleTv.text = getString(R.string.transfer_duplicate_title)
        binding.warningTv.text = getString(R.string.wallet_transfer_recent_tip, time, t.user.fullName, amount)
        binding.continueTv.setOnClickListener {
            if (shouldShowTransferTip(t)) {
                showLargeAmountTip(t)
            } else {
                callback?.onSuccess()
                dismiss()
            }
        }
        startCountDown()
    }

    private fun checkWithdrawTrace(t: WithdrawBiometricItem) {
        val trace = t.trace
        if (trace == null || isDuplicateTransferDisable()) {
            if (shouldShowWithdrawalTip(t)) {
                showFirstWithdrawalTip(t)
            } else {
                callback?.onSuccess()
                binding.root.post { dismiss() }
            }
            return
        }

        val time = trace.createdAt.getRelativeTimeSpan()
        val amount = "${t.amount} ${t.asset.symbol}"
        binding.titleTv.text = getString(R.string.withdraw_duplicate_title)
        binding.warningTv.text = getString(
            R.string.wallet_withdrawal_recent_tip,
            time,
            t.displayAddress().formatPublicKey(),
            amount
        )
        binding.continueTv.setOnClickListener {
            callback?.onSuccess()
            dismiss()
        }
        startCountDown()
    }

    private fun showStrangerTip(t: TransferBiometricItem) {
        binding.titleTv.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = 20.dp
        }
        binding.warningTv.isVisible = false
        binding.warningBottomTv.isVisible = true
        binding.warningBottomTv.text = getString(R.string.bottom_transfer_stranger_tip, t.user.identityNumber)
        binding.continueTv.setOnClickListener {
            binding.titleTv.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = 70.dp
            }
            binding.warningTv.isVisible = true
            binding.warningBottomTv.isVisible = false
            binding.assetBalance.setInfo(t)
            checkTransferTrace(t)
        }
        startCountDown()
    }

    private fun showLargeAmountTip(t: TransferBiometricItem) {
        binding.titleTv.text = getString(R.string.wallet_transaction_tip_title)
        val fiatAmount =
            (BigDecimal(t.amount) * t.asset.priceFiat()).numberFormat2()
        binding.warningTv.text = getString(
            R.string.wallet_transaction_tip,
            t.user.fullName,
            "${Fiats.getSymbol()}$fiatAmount",
            t.asset.symbol
        )
        binding.continueTv.setOnClickListener {
            callback?.onSuccess()
            dismiss()
        }
        startCountDown()
    }

    private fun showFirstWithdrawalTip(t: WithdrawBiometricItem) {
        binding.titleTv.text = getString(R.string.bottom_withdrawal_title, t.asset.symbol)
        binding.warningTv.text = getString(R.string.bottom_withdrawal_address_tips)
        binding.continueTv.text = getString(R.string.bottom_withdrawal_change_amount)
        binding.continueTv.textColor = ContextCompat.getColor(requireContext(), R.color.white)
        binding.continueTv.setOnClickListener {
            callback?.onCancel()
            dismiss()
        }
        binding.cancelTv.text = getString(R.string.bottom_withdrawal_address_continue)
        binding.cancelTv.setTextColor(resources.getColor(R.color.colorDarkBlue, null))
        binding.cancelTv.setOnClickListener {
            callback?.onSuccess()
            dismiss()
        }
        binding.continueTv.isEnabled = true
        binding.cancelTv.isEnabled = true
    }

    private fun shouldShowWithdrawalTip(t: WithdrawBiometricItem): Boolean {
        val price = t.asset.priceUsd.toDoubleOrNull() ?: return false
        val amount = BigDecimal(t.amount).toDouble() * price
        if (amount <= 10) {
            return false
        }
        val hasWithdrawalAddressSet = defaultSharedPreferences.getStringSet(Constants.Account.PREF_HAS_WITHDRAWAL_ADDRESS_SET, null)
        return if (hasWithdrawalAddressSet == null) {
            true
        } else {
            !hasWithdrawalAddressSet.contains(t.addressId)
        }
    }

    private fun shouldShowTransferTip(t: TransferBiometricItem): Boolean {
        val price = t.asset.priceUsd.toDoubleOrNull() ?: return false
        val amount = BigDecimal(t.amount).toDouble() * price
        return amount >= (Session.getAccount()!!.transferConfirmationThreshold)
    }

    private fun shouldShowStrangerTransferTip(t: TransferBiometricItem): Boolean {
        return from == FROM_TRANSFER &&
            !isStrangerTransferDisable() && t.user.relationship != UserRelationship.FRIEND.name
    }

    private fun isDuplicateTransferDisable() = !defaultSharedPreferences.getBoolean(PREF_DUPLICATE_TRANSFER, true)

    private fun isStrangerTransferDisable() = !defaultSharedPreferences.getBoolean(PREF_STRANGER_TRANSFER, true)

    private fun startCountDown() {
        binding.continueTv.isEnabled = false
        binding.continueTv.textColor = ContextCompat.getColor(requireContext(), R.color.wallet_text_gray)
        mCountDownTimer?.cancel()
        mCountDownTimer = object : CountDownTimer(4000, 1000) {

            override fun onTick(l: Long) {
                if (isAdded) {
                    binding.continueTv.text = getString(R.string.wallet_transaction_continue_count, l / 1000)
                }
            }

            override fun onFinish() {
                if (isAdded) {
                    binding.continueTv.text = getString(R.string.common_continue)
                    binding.continueTv.textColor = ContextCompat.getColor(requireContext(), R.color.white)
                    binding.continueTv.isEnabled = true
                }
            }
        }
        mCountDownTimer?.start()
    }

    var callback: Callback? = null

    interface Callback {
        fun onSuccess()

        fun onCancel()
    }
}
