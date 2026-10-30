package com.harshkatariya.fieldflow.core

import java.time.Instant

/** Stable fictional records shared by local demo and development server. */
object Seed {
    private val anchor = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
    private const val day = 86_400_000L
    val technicians = listOf(Technician("tech-alex", "Alex Morgan", "alex@fieldflow.example"), Technician("tech-riley", "Riley Chen", "riley@fieldflow.example"))
    private val categories = listOf("HVAC", "Electrical", "Pumps", "Generators", "Safety")
    val templates = categories.mapIndexed { index, category ->
        Template("template-${index + 1}", "$category routine inspection", listOf(
            Question("visual", "Housing and connections", QuestionType.PASS_FAIL_NA),
            Question("operational", "Equipment operates safely", QuestionType.YES_NO),
            Question("reading", if (category == "Electrical") "Supply voltage" else "Operating temperature", QuestionType.NUMBER,
                unit = if (category == "Electrical") "V" else "°C", min = -50.0, max = 1000.0),
            Question("notes", "Service observations", QuestionType.TEXT, required = false),
            Question("evidence", "Safety label condition", QuestionType.PASS_FAIL_NA, photoRequired = index == 4)
        ))
    }
    private val names = listOf("Rooftop air handler", "Distribution panel", "Chilled water pump", "Standby generator", "Emergency wash station", "Workshop extraction unit", "Motor control cabinet", "Transfer pump", "Backup generator", "Fire suppression cabinet", "Packaging conveyor", "Compressor drive")
    val assets = names.mapIndexed { i, name -> Asset("asset-${i + 1}", "FF-${(i + 1).toString().padStart(3, '0')}", name,
        if (i >= 10) "Industrial machinery" else categories[i % 5], listOf("North plant · Bay A", "South plant · Level 2", "Utility yard · Zone C")[i % 3],
        "Northstar / NX-${100 + i}", if (i == 2) "ATTENTION" else "OPERATIONAL", anchor - (7 + i) * day, anchor + (i - 3) * day) }
    val workOrders = (0..9).map { i -> WorkOrder("wo-${i + 1}", assets[i].id, technicians[if (i < 7) 0 else 1].id,
        templates[i % 5].id, "${categories[i % 5]} preventive service", Severity.entries[i % 4], anchor + (i - 2) * day) }
    val history = (0..2).map { i ->
        val w = workOrders[i]
        Inspection("history-${i + 1}", w.id, w.assetId, w.technicianId, w.templateId,
            answers = listOf(Answer("visual", if (i == 1) "FAIL" else "PASS"), Answer("operational", "YES"), Answer("reading", "24"), Answer("notes", "Scheduled maintenance completed."), Answer("evidence", "PASS")),
            defects = if (i == 1) listOf(Defect("historical-defect", "Protective panel needs replacement", Severity.MEDIUM, anchor - 8 * day)) else emptyList(),
            status = InspectionStatus.SUBMITTED, startedAt = anchor - (7 + i) * day, submittedAt = anchor - (7 + i) * day + 600_000, version = 1)
    }
}
