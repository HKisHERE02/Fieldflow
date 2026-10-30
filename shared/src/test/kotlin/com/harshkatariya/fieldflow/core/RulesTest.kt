package com.harshkatariya.fieldflow.core

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private val template = Seed.templates.first()
    private val valid = Seed.history.first().copy(id = "test", status = InspectionStatus.DRAFT, version = 0, submittedAt = null)
    @Test fun validChecklist() { assertTrue(Rules.validate(valid, template).isEmpty()) }
    @Test fun missingRequiredAnswers() { assertEquals(4, Rules.validate(valid.copy(answers = emptyList()), template).size) }
    @Test fun invalidNumber() { assertTrue(Rules.validate(valid.copy(answers = valid.answers.map { if (it.questionId == "reading") it.copy(value = "NaN") else it }), template).isNotEmpty()) }
    @Test fun rangeEnforced() { assertTrue(Rules.validate(valid.copy(answers = valid.answers.map { if (it.questionId == "reading") it.copy(value = "1001") else it }), template).isNotEmpty()) }
    @Test fun invalidChoice() { assertTrue(Rules.validate(valid.copy(answers = valid.answers.map { if (it.questionId == "visual") it.copy(value = "MAYBE") else it }), template).isNotEmpty()) }
    @Test fun failureNeedsDefect() { assertTrue(Rules.validate(valid.copy(answers = valid.answers.map { if (it.questionId == "visual") it.copy(value = "FAIL") else it }), template).any { "defect" in it }) }
    @Test fun requiredPhoto() { assertTrue(Rules.validate(valid.copy(templateId = "template-5"), Seed.templates.last()).any { "photo required" in it }) }
    @Test fun duplicateAnswers() { assertTrue(Rules.validate(valid.copy(answers = valid.answers + valid.answers.first()), template).contains("Duplicate answers")) }
    @Test fun unknownQuestion() { assertTrue(Rules.validate(valid.copy(answers = valid.answers + Answer("unknown", "PASS")), template).contains("Unknown question")) }
    @Test fun submitTransitions() { val result = Rules.submit(valid, template, now()); assertEquals(InspectionStatus.SUBMITTED, result.status); assertNotNull(result.submittedAt) }
    @Test(expected = IllegalArgumentException::class) fun duplicateSubmitRejected() { Rules.submit(Rules.submit(valid, template, now()), template, now()) }
    @Test fun serializationRoundTrip() { val op = Operation("id", valid, 0, now()); assertEquals(op, Wire.decodeFromString<Operation>(Wire.encodeToString(op))) }
    @Test fun retryClassification() { assertTrue(Rules.retryable(503)); assertTrue(Rules.retryable(429)); assertFalse(Rules.retryable(400)); assertFalse(Rules.retryable(401)); assertFalse(Rules.retryable(409)) }
    @Test fun sessionExpiry() { val s = Session(Seed.technicians.first(), expiresAt = 100); assertTrue(Rules.validSession(s, 99)); assertFalse(Rules.validSession(s, 100)) }
    @Test fun searchByCodeLocationCase() { assertTrue(Rules.matches(Seed.assets.first(), "ff-001")); assertTrue(Rules.matches(Seed.assets.first(), "north plant")); assertFalse(Rules.matches(Seed.assets.first(), "missing")) }
    @Test fun deterministicRelationships() { assertEquals(12, Seed.assets.size); assertEquals(10, Seed.workOrders.size); assertEquals(5, Seed.templates.size); assertEquals(2, Seed.technicians.size); assertEquals(12, Seed.assets.map { it.id }.distinct().size); Seed.workOrders.forEach { w -> assertTrue(Seed.assets.any { it.id == w.assetId }); assertTrue(Seed.templates.any { it.id == w.templateId }) } }
    @Test fun invalidLocation() { assertTrue(Rules.validate(valid.copy(latitude = 100.0, longitude = 0.0), template).contains("Invalid location")) }
    @Test fun defectsValidated() { assertTrue(Rules.validate(valid.copy(defects = listOf(Defect("d", "bad", Severity.HIGH, now()))), template).isNotEmpty()) }
}
