/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.auditlogweb.api.dao;

import org.hibernate.envers.AuditTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openmrs.module.auditlogweb.api.dao.AuditBackfillDao.TableMapping;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditBackfillDaoTest {
	
	private AuditBackfillDao dao;
	
	@BeforeEach
	void setUp() {
		dao = new AuditBackfillDao(null);
	}
	
	@Test
	void shouldBuildInsertSqlWithRevtypeAndBacktickQuoting() {
		String sql = dao.buildBackfillInsertSql(new TableMapping("patient", "patient_aud"),
		    Arrays.asList("patient_id", "name"), Collections.singletonList("patient_id"), true, 7, "`");
		
		assertEquals("INSERT INTO `patient_aud` (`patient_id`, `name`, REV, REVTYPE) SELECT "
		        + "b.`patient_id`, b.`name`, 7, 0 FROM `patient` b WHERE NOT EXISTS "
		        + "(SELECT 1 FROM `patient_aud` a WHERE a.`patient_id` = b.`patient_id`)",
		    sql);
	}
	
	@Test
	void shouldBuildInsertSqlWithoutRevtypeAndNoQuoting() {
		String sql = dao.buildBackfillInsertSql(new TableMapping("obs_reference_range", "obs_reference_range_aud"),
		    Collections.singletonList("obs_id"), Collections.singletonList("obs_id"), false, 3, "");
		
		assertEquals("INSERT INTO obs_reference_range_aud (obs_id, REV) SELECT b.obs_id, 3 FROM obs_reference_range b "
		        + "WHERE NOT EXISTS (SELECT 1 FROM obs_reference_range_aud a WHERE a.obs_id = b.obs_id)",
		    sql);
	}
	
	@Test
	void shouldOrderParentAuditTableBeforeChild() {
		TableMapping child = new TableMapping("patient", "patient_aud");
		TableMapping parent = new TableMapping("person", "person_aud");
		Map<String, Set<String>> parents = new HashMap<>();
		parents.put("patient_aud", singleton("person_aud"));
		parents.put("person_aud", emptySet());
		
		List<TableMapping> ordered = dao.orderParentsBeforeChildren(Arrays.asList(child, parent), parents);
		
		assertEquals(Arrays.asList("person_aud", "patient_aud"), auditNames(ordered));
	}
	
	@Test
	void shouldPreserveOrderWhenNoDependencies() {
		TableMapping a = new TableMapping("a", "a_aud");
		TableMapping b = new TableMapping("b", "b_aud");
		Map<String, Set<String>> parents = new HashMap<>();
		parents.put("a_aud", emptySet());
		parents.put("b_aud", emptySet());
		
		List<TableMapping> ordered = dao.orderParentsBeforeChildren(Arrays.asList(a, b), parents);
		
		assertEquals(Arrays.asList("a_aud", "b_aud"), auditNames(ordered));
	}
	
	@Test
	void shouldFallBackToOriginalOrderOnDependencyCycle() {
		TableMapping a = new TableMapping("a", "a_aud");
		TableMapping b = new TableMapping("b", "b_aud");
		Map<String, Set<String>> parents = new HashMap<>();
		parents.put("a_aud", singleton("b_aud"));
		parents.put("b_aud", singleton("a_aud"));
		
		List<TableMapping> ordered = dao.orderParentsBeforeChildren(Arrays.asList(a, b), parents);
		
		assertEquals(Arrays.asList("a_aud", "b_aud"), auditNames(ordered));
	}
	
	@Test
	void shouldDeriveAuditTableNameFromPrefixAndSuffixWhenNoAnnotation() {
		assertEquals("aud_person_hist", dao.deriveAuditTableName(Plain.class, "person", "aud_", "_hist"));
		assertEquals("orders_audit", dao.deriveAuditTableName(Plain.class, "orders", "", "_audit"));
	}
	
	@Test
	void shouldDeriveAuditTableNameFromAuditTableAnnotation() {
		assertEquals("patient_appointment_revisions",
		    dao.deriveAuditTableName(Annotated.class, "patient_appointment", "", "_audit"));
	}
	
	@Test
	void shouldQuoteIdentifierAndEscapeEmbeddedQuoteCharacter() {
		assertEquals("`patient`", dao.quoteIdentifier("patient", "`"));
		assertEquals("patient", dao.quoteIdentifier("patient", ""));
		assertEquals("`we``ird`", dao.quoteIdentifier("we`ird", "`"));
		assertEquals("\"we\"\"ird\"", dao.quoteIdentifier("we\"ird", "\""));
	}
	
	@Test
	void shouldStripAuditAffixesFromAuditTableName() {
		assertEquals("role_privilege", dao.stripAuditAffixes("role_privilege_audit", "", "_audit"));
		assertEquals("person", dao.stripAuditAffixes("aud_person_hist", "aud_", "_hist"));
		assertEquals("person", dao.stripAuditAffixes("person_AUD", "", "_AUD"));
	}
	
	@Test
	void shouldReturnNullFromStripAuditAffixesWhenNameDoesNotMatch() {
		assertNull(dao.stripAuditAffixes("person", "", "_audit"));
		assertNull(dao.stripAuditAffixes("_audit", "", "_audit"));
		assertNull(dao.stripAuditAffixes("person_audit", "", ""));
		assertNull(dao.stripAuditAffixes(null, "", "_audit"));
	}
	
	@Test
	void shouldReattachSizeArgumentsToSizedColumnTypes() {
		assertEquals("VARCHAR(50)", dao.columnTypeDdl("VARCHAR", 50, 0));
		assertEquals("CHAR(38)", dao.columnTypeDdl("CHAR", 38, 0));
		assertEquals("DECIMAL(10, 2)", dao.columnTypeDdl("DECIMAL", 10, 2));
	}
	
	@Test
	void shouldPassThroughUnsizedColumnTypes() {
		assertEquals("datetime", dao.columnTypeDdl("datetime", 19, 0));
		assertEquals("INT", dao.columnTypeDdl("INT", 10, 0));
		assertEquals("TEXT", dao.columnTypeDdl("TEXT", 65535, 0));
	}
	
	@Test
	void shouldBuildCreateRevisionTableSql() {
		List<AuditBackfillDao.ColumnDefinition> columns = Arrays.asList(
		    new AuditBackfillDao.ColumnDefinition("timestamp", "bigint", false),
		    new AuditBackfillDao.ColumnDefinition("changedBy", "integer", true),
		    new AuditBackfillDao.ColumnDefinition("changedOn", "datetime", true));
		
		String sql = dao.buildCreateRevisionTableSql("revision_entity", "id", "integer not null auto_increment", columns,
		    "`");
		
		assertEquals("CREATE TABLE `revision_entity` (`id` integer not null auto_increment, "
		        + "`timestamp` bigint NOT NULL, `changedBy` integer, `changedOn` datetime, PRIMARY KEY (`id`))",
		    sql);
	}
	
	@Test
	void shouldBuildCreateAuditTableSqlWithCompositePrimaryKey() {
		List<AuditBackfillDao.ColumnDefinition> columns = Arrays.asList(
		    new AuditBackfillDao.ColumnDefinition("person_id", "INT", true),
		    new AuditBackfillDao.ColumnDefinition("gender", "VARCHAR(50)", true));
		
		String sql = dao.buildCreateAuditTableSql(new TableMapping("person", "person_audit"), columns,
		    Collections.singletonList("person_id"), "integer", "tinyint", "`");
		
		assertEquals("CREATE TABLE `person_audit` (`person_id` INT, `gender` VARCHAR(50), "
		        + "REV integer NOT NULL, REVTYPE tinyint, PRIMARY KEY (`person_id`, REV))",
		    sql);
	}
	
	@Test
	void shouldBuildCreateAuditTableSqlWithoutPrimaryKeyWhenBaseTableHasNone() {
		List<AuditBackfillDao.ColumnDefinition> columns = Collections
		        .singletonList(new AuditBackfillDao.ColumnDefinition("obs_id", "INT", true));
		
		String sql = dao.buildCreateAuditTableSql(new TableMapping("obs_reference_range", "obs_reference_range_audit"),
		    columns, Collections.emptyList(), "integer", "tinyint", "");
		
		assertEquals("CREATE TABLE obs_reference_range_audit (obs_id INT, REV integer NOT NULL, REVTYPE tinyint)", sql);
	}
	
	@Test
	void shouldBuildCreateRevIndexSql() {
		TableMapping mapping = new TableMapping("person", "person_audit");
		
		assertEquals("CREATE INDEX `person_audit_rev` ON `person_audit` (REV)", dao.buildCreateRevIndexSql(mapping, "`"));
		assertEquals("CREATE INDEX person_audit_rev ON person_audit (REV)", dao.buildCreateRevIndexSql(mapping, ""));
	}
	
	@Test
	void shouldBuildAddColumnSql() {
		TableMapping mapping = new TableMapping("person", "person_audit");
		AuditBackfillDao.ColumnDefinition column = new AuditBackfillDao.ColumnDefinition("new_col", "VARCHAR(50)", true);
		
		assertEquals("ALTER TABLE `person_audit` ADD `new_col` VARCHAR(50)", dao.buildAddColumnSql(mapping, column, "`"));
		assertEquals("ALTER TABLE person_audit ADD new_col VARCHAR(50)", dao.buildAddColumnSql(mapping, column, ""));
	}
	
	@Test
	void shouldKeepOnlyMappingsWhoseBaseTableExists() {
		TableMapping withBase = new TableMapping("role_privilege", "role_privilege_AUD");
		TableMapping withoutBase = new TableMapping("Location_LocationAttribute", "Location_LocationAttribute_AUD");
		Set<String> existingTables = new java.util.HashSet<>(Arrays.asList("role_privilege", "location", "person"));
		
		List<TableMapping> result = dao.filterMappingsWithExistingBase(Arrays.asList(withBase, withoutBase), existingTables);
		
		assertEquals(Collections.singletonList("role_privilege_AUD"), auditNames(result));
	}
	
	@Test
	void shouldRecognizeRevisionAndAuditTablesAsEnversTables() {
		assertTrue(dao.isEnversTable("revision_entity", "", "_AUD"));
		assertTrue(dao.isEnversTable("REVINFO", "", "_AUD"));
		assertTrue(dao.isEnversTable("person_AUD", "", "_AUD"));
		assertTrue(dao.isEnversTable("Location_LocationAttribute_audit", "", "_audit"));
	}
	
	@Test
	void shouldNotRecognizeBaseTablesAsEnversTables() {
		assertFalse(dao.isEnversTable("person", "", "_AUD"));
		assertFalse(dao.isEnversTable("location_attribute", "", "_audit"));
	}
	
	private static List<String> auditNames(List<TableMapping> mappings) {
		return mappings.stream().map(TableMapping::getAuditTable).collect(java.util.stream.Collectors.toList());
	}
	
	private static class Plain {}
	
	@AuditTable("patient_appointment_revisions")
	private static class Annotated {}
	
}
