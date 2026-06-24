package com.readoutloud

/**
 * ISO 3166-1 Alpha-2 country code to flag emoji mapping.
 * Flag emojis are constructed from regional indicator symbols.
 */
object CountryUtils {

    private val COUNTRY_FLAGS = mapOf(
        "AD" to "🇦🇩", "AE" to "🇦🇪", "AF" to "🇦🇫", "AG" to "🇦🇬",
        "AI" to "🇦🇮", "AL" to "🇦🇱", "AM" to "🇦🇲", "AO" to "🇦🇴",
        "AQ" to "🇦🇶", "AR" to "🇦🇷", "AS" to "🇦🇸", "AT" to "🇦🇹",
        "AU" to "🇦🇺", "AW" to "🇦🇼", "AX" to "🇦🇽", "AZ" to "🇦🇿",
        "BA" to "🇧🇦", "BB" to "🇧🇧", "BD" to "🇧🇩", "BE" to "🇧🇪",
        "BF" to "🇧🇫", "BG" to "🇧🇬", "BH" to "🇧🇭", "BI" to "🇧🇮",
        "BJ" to "🇧🇯", "BL" to "🇧🇱", "BM" to "🇧🇲", "BN" to "🇧🇳",
        "BO" to "🇧🇴", "BQ" to "🇧🇶", "BR" to "🇧🇷", "BS" to "🇧🇸",
        "BT" to "🇧🇹", "BV" to "🇧🇻", "BW" to "🇧🇼", "BY" to "🇧🇾",
        "BZ" to "🇧🇿", "CA" to "🇨🇦", "CC" to "🇨🇨", "CD" to "🇨🇩",
        "CF" to "🇨🇫", "CG" to "🇨🇬", "CH" to "🇨🇭", "CI" to "🇨🇮",
        "CK" to "🇨🇰", "CL" to "🇨🇱", "CM" to "🇨🇲", "CN" to "🇨🇳",
        "CO" to "🇨🇴", "CR" to "🇨🇷", "CU" to "🇨🇺", "CV" to "🇨🇻",
        "CW" to "🇨🇼", "CX" to "🇨🇽", "CY" to "🇨🇾", "CZ" to "🇨🇿",
        "DE" to "🇩🇪", "DJ" to "🇩🇯", "DK" to "🇩🇰", "DM" to "🇩🇲",
        "DO" to "🇩🇴", "DZ" to "🇩🇿", "EC" to "🇪🇨", "EE" to "🇪🇪",
        "EG" to "🇪🇬", "EH" to "🇪🇭", "ER" to "🇪🇷", "ES" to "🇪🇸",
        "ET" to "🇪🇹", "FI" to "🇫🇮", "FJ" to "🇫🇯", "FK" to "🇫🇰",
        "FM" to "🇫🇲", "FO" to "🇫🇴", "FR" to "🇫🇷", "GA" to "🇬🇦",
        "GB" to "🇬🇧", "GD" to "🇬🇩", "GE" to "🇬🇪", "GF" to "🇬🇫",
        "GG" to "🇬🇬", "GH" to "🇬🇭", "GI" to "🇬🇮", "GL" to "🇬🇱",
        "GM" to "🇬🇲", "GN" to "🇬🇳", "GP" to "🇬🇵", "GQ" to "🇬🇶",
        "GR" to "🇬🇷", "GS" to "🇬🇸", "GT" to "🇬🇹", "GU" to "🇬🇺",
        "GW" to "🇬🇼", "GY" to "🇬🇾", "HK" to "🇭🇰", "HM" to "🇭🇲",
        "HN" to "🇭🇳", "HR" to "🇭🇷", "HT" to "🇭🇹", "HU" to "🇭🇺",
        "ID" to "🇮🇩", "IE" to "🇮🇪", "IL" to "🇮🇱", "IM" to "🇮🇲",
        "IN" to "🇮🇳", "IO" to "🇮🇴", "IQ" to "🇮🇶", "IR" to "🇮🇷",
        "IS" to "🇮🇸", "IT" to "🇮🇹", "JE" to "🇯🇪", "JM" to "🇯🇲",
        "JO" to "🇯🇴", "JP" to "🇯🇵", "KE" to "🇰🇪", "KG" to "🇰🇬",
        "KH" to "🇰🇭", "KI" to "🇰🇮", "KM" to "🇰🇲", "KN" to "🇰🇳",
        "KP" to "🇰🇵", "KR" to "🇰🇷", "KW" to "🇰🇼", "KY" to "🇰🇾",
        "KZ" to "🇰🇿", "LA" to "🇱🇦", "LB" to "🇱🇧", "LC" to "🇱🇨",
        "LI" to "🇱🇮", "LK" to "🇱🇰", "LR" to "🇱🇷", "LS" to "🇱🇸",
        "LT" to "🇱🇹", "LU" to "🇱🇺", "LV" to "🇱🇻", "LY" to "🇱🇾",
        "MA" to "🇲🇦", "MC" to "🇲🇨", "MD" to "🇲🇩", "ME" to "🇲🇪",
        "MF" to "🇲🇫", "MG" to "🇲🇬", "MH" to "🇲🇭", "MK" to "🇲🇰",
        "ML" to "🇲🇱", "MM" to "🇲🇲", "MN" to "🇲🇳", "MO" to "🇲🇴",
        "MP" to "🇲🇵", "MQ" to "🇲🇶", "MR" to "🇲🇷", "MS" to "🇲🇸",
        "MT" to "🇲🇹", "MU" to "🇲🇺", "MV" to "🇲🇻", "MW" to "🇲🇼",
        "MX" to "🇲🇽", "MY" to "🇲🇾", "MZ" to "🇲🇿", "NA" to "🇳🇦",
        "NC" to "🇳🇨", "NE" to "🇳🇪", "NF" to "🇳🇫", "NG" to "🇳🇬",
        "NI" to "🇳🇮", "NL" to "🇳🇱", "NO" to "🇳🇴", "NP" to "🇳🇵",
        "NR" to "🇳🇷", "NU" to "🇳🇺", "NZ" to "🇳🇿", "OM" to "🇴🇲",
        "PA" to "🇵🇦", "PE" to "🇵🇪", "PF" to "🇵🇫", "PG" to "🇵🇬",
        "PH" to "🇵🇭", "PK" to "🇵🇰", "PL" to "🇵🇱", "PM" to "🇵🇲",
        "PN" to "🇵🇳", "PR" to "🇵🇷", "PS" to "🇵🇸", "PT" to "🇵🇹",
        "PW" to "🇵🇼", "PY" to "🇵🇾", "QA" to "🇶🇦", "RE" to "🇷🇪",
        "RO" to "🇷🇴", "RS" to "🇷🇸", "RU" to "🇷🇺", "RW" to "🇷🇼",
        "SA" to "🇸🇦", "SB" to "🇸🇧", "SC" to "🇸🇨", "SD" to "🇸🇩",
        "SE" to "🇸🇪", "SG" to "🇸🇬", "SH" to "🇸🇭", "SI" to "🇸🇮",
        "SJ" to "🇸🇯", "SK" to "🇸🇰", "SL" to "🇸🇱", "SM" to "🇸🇲",
        "SN" to "🇸🇳", "SO" to "🇸🇴", "SR" to "🇸🇷", "SS" to "🇸🇸",
        "ST" to "🇸🇹", "SV" to "🇸🇻", "SX" to "🇸🇽", "SY" to "🇸🇾",
        "SZ" to "🇸🇿", "TC" to "🇹🇨", "TD" to "🇹🇩", "TF" to "🇹🇫",
        "TG" to "🇹🇬", "TH" to "🇹🇭", "TJ" to "🇹🇯", "TK" to "🇹🇰",
        "TL" to "🇹🇱", "TM" to "🇹🇲", "TN" to "🇹🇳", "TO" to "🇹🇴",
        "TR" to "🇹🇷", "TT" to "🇹🇹", "TV" to "🇹🇻", "TW" to "🇹🇼",
        "TZ" to "🇹🇿", "UA" to "🇺🇦", "UG" to "🇺🇬", "UM" to "🇺🇲",
        "US" to "🇺🇸", "UY" to "🇺🇾", "UZ" to "🇺🇿", "VA" to "🇻🇦",
        "VC" to "🇻🇨", "VE" to "🇻🇪", "VG" to "🇻🇬", "VI" to "🇻🇮",
        "VN" to "🇻🇳", "VU" to "🇻🇺", "WF" to "🇼🇫", "WS" to "🇼🇸",
        "YE" to "🇾🇪", "YT" to "🇾🇹", "ZA" to "🇿🇦", "ZM" to "🇿🇲",
        "ZW" to "🇿🇼"
    )

    /**
     * Get flag emoji for ISO 3166-1 Alpha-2 country code.
     * Returns fallback globe emoji if code not found.
     */
    fun getFlagForCountry(countryCode: String): String =
        COUNTRY_FLAGS[countryCode.uppercase()] ?: "🌍"

    /**
     * Extract country code from language tag (e.g., "es_MX" → "MX", "en-US" → "US").
     */
    fun extractCountryFromLanguage(lang: String): String? {
        val region = lang.substringAfter('_').ifEmpty { lang.substringAfter('-') }
        return if (region.length == 2) region.uppercase() else null
    }

    /**
     * Get flag emoji for a full language tag (e.g., "es_MX" → "🇲🇽").
     */
    fun getFlagForLanguage(lang: String): String {
        val country = extractCountryFromLanguage(lang)
        return if (country != null) getFlagForCountry(country) else getFlagForLanguageFamily(lang)
    }

    /**
     * Get flag for language family (fallback when no region).
     */
    private fun getFlagForLanguageFamily(lang: String): String {
        return when (lang.lowercase()) {
            "en" -> "🇬🇧"
            "pt" -> "🇵🇹"
            "es" -> "🇪🇸"
            "nl" -> "🇳🇱"
            "fr" -> "🇫🇷"
            "de" -> "🇩🇪"
            "it" -> "🇮🇹"
            "ku" -> "☀️"  // Kurdish - sun symbol
            "cy" -> "🏴"  // Welsh - black flag
            "ca" -> "🏴"  // Catalan - black flag
            else -> "🌍"
        }
    }
}
