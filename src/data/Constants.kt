package com.fantamomo.slack.approver.data

object Constants {
    // yeah, you right, it's from AI, but it looks good as bullet points in a list
    const val BULLET = "•"

    const val MARKETPLACE_URL = "https://slack.com/marketplace/"

    val DANGEROUS_DOMAINS = listOf(
        // slack domains
        "slack.com",
        "slack.dev",

        // hackclub domains
        "bank.engineering",
        "bulckcah.com",
        "dinosaurbbq.org",
        "hack.af",
        "hack.club",
//        "hackclub.app",
        "hackclub.com",
        "hackclub.community",
        "hackclub.dev",
        "hackclub.io",
        "hackclub.org",
        "hackedu.us",
        "hackfoundation.org",
        "nonprofit.new",
        "scrap.dev",
        "scrapbook.dev",
    )

    init {
        require(DANGEROUS_DOMAINS.all { it.isNotEmpty() }) { "All dangerous domains must be non-empty strings" }
        require(DANGEROUS_DOMAINS.all { it.contains(".") }) { "All dangerous domains must contain a dot" }
    }
}