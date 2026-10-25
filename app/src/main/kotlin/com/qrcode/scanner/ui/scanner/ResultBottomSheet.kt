package com.qrcode.scanner.ui.scanner

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.qrcode.scanner.R
import com.qrcode.scanner.data.QrContent
import com.qrcode.scanner.databinding.BottomSheetResultBinding
import com.qrcode.scanner.util.ClipboardHelper
import com.qrcode.scanner.util.IntentHelper
import com.qrcode.scanner.util.WifiConnector
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Bottom sheet shown after a successful scan. Renders the parsed payload and a row of
 * smart action buttons that depend on the detected QR type.
 */
class ResultBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetResultBinding? = null
    private val binding get() = _binding!!
    var onDismiss: (() -> Unit)? = null
    private lateinit var content: QrContent

    override fun getTheme(): Int = R.style.ThemeOverlay_QRScanner_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val raw = arguments?.getString(ARG_RAW).orEmpty()
        content = com.qrcode.scanner.data.QrTypeDetector.detect(raw)
        render()
    }

    private fun render() {
        binding.tvTypeLabel.setText(content.typeLabelRes)
        binding.tvTypeLabel.visibility = View.VISIBLE

        when (content) {
            is QrContent.Text -> renderText(content as QrContent.Text)
            is QrContent.Url -> renderUrl(content as QrContent.Url)
            is QrContent.Wifi -> renderWifi(content as QrContent.Wifi)
            is QrContent.VCard -> renderVCard(content as QrContent.VCard)
            is QrContent.Phone -> renderPhone(content as QrContent.Phone)
            is QrContent.Email -> renderEmail(content as QrContent.Email)
            is QrContent.Sms -> renderSms(content as QrContent.Sms)
            is QrContent.Geo -> renderGeo(content as QrContent.Geo)
            is QrContent.CalendarEvent -> renderCalendar(content as QrContent.CalendarEvent)
            is QrContent.Crypto -> renderCrypto(content as QrContent.Crypto)
        }

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnShareAll.setOnClickListener {
            IntentHelper.shareText(requireContext(), content.typeLabelRes.toString(), content.raw)
        }
        binding.btnScanAgain.setOnClickListener { dismiss() }
    }

    private fun renderText(c: QrContent.Text) {
        binding.tvTitle.text = c.raw.take(120)
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = c.raw
        addButton(R.string.action_copy_text, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "Text", c.raw)
        }
        addButton(R.string.action_share_text, R.drawable.ic_share) {
            IntentHelper.shareText(requireContext(), "Text", c.raw)
        }
    }

    private fun renderUrl(c: QrContent.Url) {
        binding.tvTitle.text = c.url
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = c.url
        if (!c.isLikelySafe) {
            binding.bannerUnsafe.visibility = View.VISIBLE
            binding.bannerUnsafe.text = getString(R.string.toast_unsafe_link_warning)
        } else {
            binding.bannerUnsafe.visibility = View.GONE
        }
        addButton(R.string.action_open_url, R.drawable.ic_browser) {
            IntentHelper.openUrl(requireContext(), c.url)
        }
        addButton(R.string.action_copy_link, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "URL", c.url)
        }
    }

    private fun renderWifi(c: QrContent.Wifi) {
        binding.tvTitle.text = c.ssid.ifBlank { "Wi-Fi network" }
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardWifi.visibility = View.VISIBLE
        binding.tvWifiSsid.text = c.ssid.ifBlank { "—" }
        binding.tvWifiSecurity.text = c.security.ifBlank { getString(R.string.wifi_open) }
        binding.tvWifiHidden.text = if (c.hidden) getString(R.string.wifi_hidden) else ""
        binding.tvWifiHidden.visibility = if (c.hidden) View.VISIBLE else View.GONE
        binding.tvWifiPassword.text = c.password ?: getString(R.string.wifi_unknown_password)
        binding.tvWifiPasswordLabel.visibility = if (c.password.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.tvWifiPassword.visibility = if (c.password.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Copy Network Name
        addButton(R.string.action_copy_ssid, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "Wi-Fi SSID", c.ssid)
        }
        // Copy Password (if any)
        if (!c.password.isNullOrEmpty()) {
            addButton(R.string.action_copy_password, R.drawable.ic_lock) {
                ClipboardHelper.copyPassword(requireContext(), c.password)
            }
        }
        // Connect to Wi-Fi
        addButton(R.string.action_connect_wifi, R.drawable.ic_wifi) {
            attemptWifiConnect(c)
        }
    }

    private fun attemptWifiConnect(c: QrContent.Wifi) {
        // Pre-emptively copy the password to the clipboard so the user can paste it
        // if the system dialog fails.
        if (!c.password.isNullOrEmpty()) {
            val clipboard = requireContext()
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Wi-Fi password", c.password))
        }
        WifiConnector.connect(requireContext(), c, object : WifiConnector.Callback {
            override fun onSystemDialogShown() {
                // System Wi-Fi dialog is now visible — show a hint that the user must approve.
            }
            override fun onDirectAttemptFailed() {
                showWifiManualInstructions(c)
            }
        })
    }

    private fun showWifiManualInstructions(c: QrContent.Wifi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wifi_instruction_title)
            .setMessage(getString(R.string.wifi_instruction_message, c.ssid))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun renderVCard(c: QrContent.VCard) {
        binding.tvTitle.text = c.name ?: c.phones.firstOrNull() ?: c.emails.firstOrNull() ?: "Contact"
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardContact.visibility = View.VISIBLE
        binding.tvContactName.text = c.name ?: "—"
        binding.tvContactOrg.text = listOfNotNull(c.organization, c.title)
            .joinToString(" · ").ifEmpty { "—" }
        binding.tvContactPhone.text = c.phones.joinToString("\n").ifEmpty { "—" }
        binding.tvContactEmail.text = c.emails.joinToString("\n").ifEmpty { "—" }

        if (!c.name.isNullOrBlank()) {
            addButton(R.string.action_copy_name, R.drawable.ic_copy) {
                ClipboardHelper.copyName(requireContext(), c.name)
            }
        }
        if (c.phones.isNotEmpty()) {
            addButton(R.string.action_copy_phone, R.drawable.ic_copy) {
                ClipboardHelper.copy(requireContext(), "Phone", c.phones.first())
            }
        }
        if (c.emails.isNotEmpty()) {
            addButton(R.string.action_copy_email, R.drawable.ic_copy) {
                ClipboardHelper.copy(requireContext(), "Email", c.emails.first())
            }
        }
        addButton(R.string.action_save_contact, R.drawable.ic_contact) {
            IntentHelper.saveContact(requireContext(), c)
        }
        if (c.raw.isNotEmpty()) {
            addButton(R.string.action_share_text, R.drawable.ic_share) {
                IntentHelper.shareText(requireContext(), "vCard", c.raw)
            }
        }
    }

    private fun renderPhone(c: QrContent.Phone) {
        binding.tvTitle.text = c.number
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = c.number
        addButton(R.string.action_call, R.drawable.ic_phone) {
            IntentHelper.dial(requireContext(), c.number)
        }
        addButton(R.string.action_copy_phone, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "Phone", c.number)
        }
        addButton(R.string.action_share_text, R.drawable.ic_share) {
            IntentHelper.shareText(requireContext(), "Phone", c.number)
        }
    }

    private fun renderEmail(c: QrContent.Email) {
        binding.tvTitle.text = c.address
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = c.address
        addButton(R.string.action_send_email, R.drawable.ic_email) {
            IntentHelper.sendEmail(requireContext(), c)
        }
        addButton(R.string.action_copy_email, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "Email", c.address)
        }
        addButton(R.string.action_share_text, R.drawable.ic_share) {
            IntentHelper.shareText(requireContext(), "Email", c.address)
        }
    }

    private fun renderSms(c: QrContent.Sms) {
        binding.tvTitle.text = c.number
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = if (c.message.isNullOrBlank()) c.number else "${c.number}\n\n${c.message}"
        addButton(R.string.action_send_sms, R.drawable.ic_sms) {
            IntentHelper.sendSms(requireContext(), c)
        }
        if (!c.message.isNullOrBlank()) {
            addButton(R.string.action_copy_message, R.drawable.ic_copy) {
                ClipboardHelper.copy(requireContext(), "SMS message", c.message)
            }
        }
        addButton(R.string.action_copy_phone, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "Phone", c.number)
        }
    }

    private fun renderGeo(c: QrContent.Geo) {
        binding.tvTitle.text = "%.5f, %.5f".format(c.lat, c.lng)
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = c.query ?: "%.5f, %.5f".format(c.lat, c.lng)
        addButton(R.string.action_view_map, R.drawable.ic_map) {
            IntentHelper.viewOnMap(requireContext(), c)
        }
        addButton(R.string.action_navigate, R.drawable.ic_navigate) {
            IntentHelper.navigateTo(requireContext(), c)
        }
        addButton(R.string.action_copy_text, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "Coordinates", "${c.lat},${c.lng}")
        }
    }

    private fun renderCalendar(c: QrContent.CalendarEvent) {
        binding.tvTitle.text = c.title ?: getString(R.string.type_calendar)
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = buildString {
            append(c.title ?: ""); append('\n')
            c.start?.let { append("Start: ").append(it).append('\n') }
            c.end?.let { append("End: ").append(it).append('\n') }
            c.location?.let { append("Where: ").append(it).append('\n') }
            c.description?.let { append(it).append('\n') }
        }.trim()
        addButton(R.string.action_add_to_calendar, R.drawable.ic_calendar) {
            IntentHelper.addToCalendar(requireContext(), c)
        }
        addButton(R.string.action_copy_text, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), "Event", content.raw)
        }
    }

    private fun renderCrypto(c: QrContent.Crypto) {
        binding.tvTitle.text = "${c.scheme.uppercase()} ${c.address.take(16)}…"
        binding.tvTitle.visibility = View.VISIBLE
        binding.cardTextContent.visibility = View.VISIBLE
        binding.tvTextValue.text = "${c.address}${c.amount?.let { "\nAmount: $it" } ?: ""}"
        addButton(R.string.action_copy_text, R.drawable.ic_copy) {
            ClipboardHelper.copy(requireContext(), c.scheme, c.address)
        }
        addButton(R.string.action_share_text, R.drawable.ic_share) {
            IntentHelper.shareText(requireContext(), c.scheme, c.raw)
        }
    }

    private fun addButton(labelRes: Int, iconRes: Int, onClick: () -> Unit) {
        val ctx = context ?: return
        val button = com.google.android.material.button.MaterialButton(
            ctx, null, com.google.android.material.R.attr.borderlessButtonStyle
        )
        button.text = getString(labelRes)
        button.icon = androidx.core.content.ContextCompat.getDrawable(ctx, iconRes)
        button.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        button.iconPadding = (8 * resources.displayMetrics.density).toInt()
        button.cornerRadius = (12 * resources.displayMetrics.density).toInt()
        val params = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = (6 * resources.displayMetrics.density).toInt()
        button.layoutParams = params
        button.setOnClickListener { onClick() }
        binding.actionsContainer.addView(button)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        onDismiss?.invoke()
    }

    companion object {
        const val TAG = "ResultBottomSheet"
        private const val ARG_RAW = "raw_content"

        fun newInstance(content: QrContent): ResultBottomSheet {
            return ResultBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_RAW, content.raw)
                }
            }
        }
    }
}
