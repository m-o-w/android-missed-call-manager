package com.example.missedcallforwarder.core

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Helpers for turning a raw call-log number into the digits-only, country-coded
 * form that wa.me requires (no '+', no spaces). See:
 * https://faq.whatsapp.com/5913398998672934 (click-to-chat).
 */
object PhoneNumbers {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /**
     * Returns E.164 digits without the leading '+', suitable for https://wa.me/<this>.
     * [defaultRegion] is an ISO region code (e.g. "IN") used to resolve local-format
     * numbers like "098...". Returns null if the number can't be parsed/validated.
     */
    fun toWaMeDigits(raw: String, defaultRegion: String?): String? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty() || cleaned.equals("private", true) ||
            cleaned.equals("unknown", true) || cleaned == "-1" || cleaned == "-2"
        ) return null

        return try {
            val region = defaultRegion?.takeIf { it.isNotBlank() }
            val parsed = util.parse(cleaned, region)
            if (!util.isValidNumber(parsed)) return null
            // E.164 looks like "+919812345678"; strip the '+' for wa.me.
            util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164).removePrefix("+")
        } catch (e: NumberParseException) {
            null
        }
    }

    fun waMeUrl(raw: String, defaultRegion: String?, greeting: String? = null): String? {
        val digits = toWaMeDigits(raw, defaultRegion) ?: return null
        val base = "https://wa.me/$digits"
        val text = greeting?.takeIf { it.isNotBlank() } ?: return base
        return "$base?text=${java.net.URLEncoder.encode(text, "UTF-8")}"
    }
}
