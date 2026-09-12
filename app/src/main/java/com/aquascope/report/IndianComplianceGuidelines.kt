package com.aquascope.report

import com.aquascope.baseline.AnomalyThresholds

/**
 * Result-linked awareness notes under commonly cited Indian frameworks.
 * Not legal advice — users should consult a qualified advocate for their state/facts.
 */
object IndianComplianceGuidelines {

    enum class RiskBand {
        NORMAL,
        ELEVATED,
        ANOMALY
    }

    fun riskBand(report: SessionReport): RiskBand = when {
        report.anomalyCount > 0 -> RiskBand.ANOMALY
        report.elevatedCount > 0 -> RiskBand.ELEVATED
        else -> RiskBand.NORMAL
    }

    fun riskTitle(band: RiskBand): String = when (band) {
        RiskBand.NORMAL -> "LOW RISK — ROUTINE MONITORING"
        RiskBand.ELEVATED -> "MODERATE RISK — DOCUMENT & NOTIFY"
        RiskBand.ANOMALY -> "HIGH RISK — PROMPT NOTICE & REMEDIATION"
    }

    fun executiveSummary(report: SessionReport): String {
        val band = riskBand(report)
        val base = ReportGenerator.overallAssessment(report)
        return when (band) {
            RiskBand.NORMAL ->
                "$base Acoustic readings are within the current dry baseline. " +
                    "Keep this report as a dated record of condition under ordinary wear-and-tear principles."
            RiskBand.ELEVATED ->
                "$base Elevated acoustic deviation can indicate early dampness or contact change. " +
                    "Under Indian property practice, early written notice and evidence preservation protect " +
                    "both occupants and owners if a dispute later arises."
            RiskBand.ANOMALY ->
                "$base One or more points show strong moisture-like deviation. " +
                    "Treat this as a potential seepage/leak indicator: give prompt written notice, " +
                    "limit further damage, and obtain a licensed technical inspection before structural or finish works."
        }
    }

    /** Ordered guideline bullets tailored to overall band. */
    fun actionGuidelines(report: SessionReport): List<String> {
        val band = riskBand(report)
        val common = listOf(
            "Preserve this AquaScope PDF, photographs, and timestamps as contemporaneous evidence of condition.",
            "This screening is acoustic/comparative — it is not a substitute for a licensed plumber, " +
                "civil engineer, or forensic damp survey."
        )
        val specific = when (band) {
            RiskBand.NORMAL -> listOf(
                "Continue periodic scans at the same points to build a trend. Sudden jumps matter more than a single normal reading.",
                "If the premises are let, occupants should still report visible damp or plumbing faults promptly " +
                    "(Transfer of Property Act, 1882 — Section 108: lessee’s duty to notify defects and lessor’s repair duties, " +
                    "subject to contract and local usage).",
                "For newly purchased apartments, keep handover documents ready; RERA defect liability (Section 14(3)) " +
                    "generally runs for five years from possession for structural defects and workmanship defects " +
                    "including seepage/leakage attributed to construction.",
                "Follow National Building Code of India (NBC) good practice: keep wet areas ventilated, " +
                    "do not block weep holes, and avoid concealing active damp with paint alone."
            )
            RiskBand.ELEVATED -> listOf(
                "Within 24–48 hours: photograph all affected surfaces (wide + close), note smell/staining, " +
                    "and send written notice (email/registered post/WhatsApp to recorded address) to the " +
                    "landlord, society/association, or promoter as applicable.",
                "Ask for inspection and remedial waterproofing/plumbing under the lease, society bye-laws, " +
                    "or sale agreement. State tenancy laws / Model Tenancy Act principles typically place " +
                    "structural, plumbing, and waterproofing repairs on the lessor/owner unless misuse is proven.",
                "Under Transfer of Property Act, 1882 — Section 108(f): if the lessor, after notice, neglects " +
                    "repairs they are bound to make, the lessee may (absent contrary contract/local usage) " +
                    "carry out those repairs and recover reasonable cost — obtain quotes and keep invoices.",
                "If you are an allottee within five years of possession and seepage appears linked to " +
                    "workmanship/structure, notify the promoter in writing citing RERA Act, 2016 — Section 14(3) " +
                    "(defect liability). Escalation lies with the State RERA authority.",
                "Do not strip finishes aggressively before documentation; avoid electrical risk near wet walls."
            )
            RiskBand.ANOMALY -> listOf(
                "Immediate steps: isolate water supply to the suspect line if safe; move valuables; " +
                    "photograph damage; generate and retain this report.",
                "Serve formal written notice the same day to landlord / society / builder (as applicable), " +
                    "describing location of flagged points, scores, and requesting urgent inspection.",
                "Engage a licensed plumber and, for ceiling/structural damp, a competent civil/structural " +
                    "professional. Request a written technical note — useful for RERA, consumer, or insurance claims.",
                "RERA Act, 2016 — Section 14(3): for allottees, structural defects or workmanship defects " +
                    "(including seepage) notified within five years of possession must be rectified by the promoter; " +
                    "file a RERA complaint if notice is ignored.",
                "Transfer of Property Act, 1882 — Section 108: lessor repair and disclosure duties; lessee " +
                    "notice and care duties. Section 108(f) repair-and-deduct is available only after notice and " +
                    "only for repairs the lessor is bound to make — take legal advice before withholding rent.",
                "Consumer Protection Act, 2019 may apply where a service provider (builder, plumber, society " +
                    "maintenance contractor) shows deficiency in service — keep all bills and notices.",
                "Check home insurance / society insurance for sudden water damage; late notice can prejudice claims.",
                "NBC / local municipal bye-laws expect buildings to remain free of persistent damp that " +
                    "affects habitability; persistent untreated seepage into neighbouring flats can create " +
                    "civil liability between co-owners — notify the association early."
            )
        }
        return specific + common
    }

    fun legalFrameworkNotes(): List<String> = listOf(
        "Transfer of Property Act, 1882 — Section 108 (rights & liabilities of lessor and lessee; notice and repairs).",
        "Real Estate (Regulation and Development) Act, 2016 — Section 14(3) (five-year defect liability for allottees).",
        "State tenancy statutes / Model Tenancy Act, 2021 principles (allocation of structural & plumbing repairs).",
        "Consumer Protection Act, 2019 (deficiency in service — where a service relationship exists).",
        "National Building Code of India (NBC) — dampness, waterproofing and habitability good practice.",
        "Local municipal / apartment ownership / society bye-laws may impose additional notice and repair duties."
    )

    fun disclaimer(): String =
        "DISCLAIMER: AquaScope provides non-invasive acoustic screening for awareness only. " +
            "Scores are comparative against user-taught baselines and are not a certified moisture meter reading, " +
            "structural certificate, or legal determination of liability. References to Indian statutes are " +
            "general information for the user’s jurisdiction awareness and do not constitute legal advice. " +
            "Obligations vary by state law, contract, and facts. Consult a qualified advocate, " +
            "licensed engineer, and your insurer before acting on repair-and-deduct, rent withholding, " +
            "or regulatory complaints."
}
