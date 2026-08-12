/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.auditlogweb.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.auditlogweb.api.dao.AuditBackfillDao;
import org.openmrs.module.auditlogweb.api.utils.EnversUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuditBackfillServiceTest {
	
	private AuditBackfillDao auditBackfillDao;
	
	private AdministrationService administrationService;
	
	private AuditBackfillService service;
	
	@BeforeEach
	void setUp() {
		auditBackfillDao = mock(AuditBackfillDao.class);
		administrationService = mock(AdministrationService.class);
		service = new AuditBackfillService(auditBackfillDao);
	}
	
	@Test
	void shouldSkipBackfillWhenEnversDisabled() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(false);
			
			service.backfillExistingDataIfEnabled();
			
			verifyNoInteractions(auditBackfillDao);
		}
	}
	
	@Test
	void shouldSkipAuditTableCreationWhenEnversDisabled() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(false);
			
			service.createMissingAuditTablesIfEnabled();
			
			verifyNoInteractions(auditBackfillDao);
		}
	}
	
	@Test
	void shouldCreateMissingAuditTablesWhenEnversEnabled() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(true);
			when(auditBackfillDao.createMissingAuditTables())
			        .thenReturn(new AuditBackfillDao.SchemaCreationResult(3, Collections.emptyList()));
			
			service.createMissingAuditTablesIfEnabled();
			
			verify(auditBackfillDao).createMissingAuditTables();
		}
	}
	
	@Test
	void shouldNotFailWhenSomeAuditTablesCouldNotBeCreated() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(true);
			when(auditBackfillDao.createMissingAuditTables()).thenReturn(
			    new AuditBackfillDao.SchemaCreationResult(0, Arrays.asList("person_aud", "revision_entity")));
			
			service.createMissingAuditTablesIfEnabled();
			
			verify(auditBackfillDao).createMissingAuditTables();
		}
	}
	
	@Test
	void shouldSkipColumnSyncWhenEnversDisabled() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(false);
			
			service.syncAuditColumnsIfVersionsChanged();
			
			verifyNoInteractions(auditBackfillDao);
		}
	}
	
	@Test
	void shouldSkipColumnSyncWhenVersionFingerprintUnchanged() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class);
		        MockedStatic<Context> context = mockStatic(Context.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(true);
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_COLUMN_SYNC_FINGERPRINT, ""))
			        .thenReturn(service.currentVersionFingerprint());
			
			service.syncAuditColumnsIfVersionsChanged();
			
			verifyNoInteractions(auditBackfillDao);
			verify(administrationService, never()).setGlobalProperty(eq(AuditBackfillService.GP_COLUMN_SYNC_FINGERPRINT),
			    anyString());
		}
	}
	
	@Test
	void shouldIncludeModuleVersionsInTheFingerprint() {
		try (MockedStatic<ModuleFactory> modules = mockStatic(ModuleFactory.class)) {
			Module fhir = new Module("FHIR");
			fhir.setModuleId("fhir2");
			fhir.setVersion("2.5.0");
			modules.when(ModuleFactory::getStartedModules).thenReturn(Collections.singletonList(fhir));
			
			assertTrue(service.currentVersionFingerprint().contains("fhir2:2.5.0"));
		}
	}
	
	@Test
	void shouldSyncColumnsAndRecordFingerprintWhenVersionsChanged() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class);
		        MockedStatic<Context> context = mockStatic(Context.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(true);
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_COLUMN_SYNC_FINGERPRINT, ""))
			        .thenReturn("some-older-fingerprint");
			when(auditBackfillDao.addMissingAuditColumns())
			        .thenReturn(new AuditBackfillDao.ColumnSyncResult(2, Collections.emptyList()));
			
			service.syncAuditColumnsIfVersionsChanged();
			
			verify(auditBackfillDao).addMissingAuditColumns();
			verify(administrationService).setGlobalProperty(AuditBackfillService.GP_COLUMN_SYNC_FINGERPRINT,
			    service.currentVersionFingerprint());
		}
	}
	
	@Test
	void shouldNotRecordFingerprintWhenColumnSyncFailedForSomeTables() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class);
		        MockedStatic<Context> context = mockStatic(Context.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(true);
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_COLUMN_SYNC_FINGERPRINT, ""))
			        .thenReturn("some-older-fingerprint");
			when(auditBackfillDao.addMissingAuditColumns())
			        .thenReturn(new AuditBackfillDao.ColumnSyncResult(1, Collections.singletonList("person_aud")));
			
			service.syncAuditColumnsIfVersionsChanged();
			
			// the fingerprint must not advance, so the sync retries on the next startup
			verify(administrationService, never()).setGlobalProperty(eq(AuditBackfillService.GP_COLUMN_SYNC_FINGERPRINT),
			    anyString());
		}
	}
	
	@Test
	void shouldSkipBackfillWhenFeatureNotEnabled() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class);
		        MockedStatic<Context> context = mockStatic(Context.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(true);
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_ENABLED, "false"))
			        .thenReturn("false");
			
			service.backfillExistingDataIfEnabled();
			
			verifyNoInteractions(auditBackfillDao);
			verify(administrationService, never()).setGlobalProperty(eq(AuditBackfillService.GP_BACKFILL_COMPLETED),
			    anyString());
		}
	}
	
	@Test
	void shouldSkipBackfillWhenAlreadyCompleted() {
		try (MockedStatic<EnversUtils> envers = mockStatic(EnversUtils.class);
		        MockedStatic<Context> context = mockStatic(Context.class)) {
			envers.when(EnversUtils::isEnversEnabled).thenReturn(true);
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_ENABLED, "false"))
			        .thenReturn("true");
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_COMPLETED, "false"))
			        .thenReturn("true");
			
			service.backfillExistingDataIfEnabled();
			
			verifyNoInteractions(auditBackfillDao);
			verify(administrationService, never()).setGlobalProperty(eq(AuditBackfillService.GP_BACKFILL_COMPLETED),
			    anyString());
		}
	}
	
	@Test
	void shouldReturnNullFromReuseRevisionIdWhenNoStoredRevision() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("");
			
			assertNull(service.reuseRevisionId());
			verify(auditBackfillDao, never()).revisionExists(anyInt());
		}
	}
	
	@Test
	void shouldReturnNullFromReuseRevisionIdWhenStoredValueIsNotNumeric() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("abc");
			
			assertNull(service.reuseRevisionId());
			verify(auditBackfillDao, never()).revisionExists(anyInt());
		}
	}
	
	@Test
	void shouldReturnIdFromReuseRevisionIdWhenRevisionExists() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("5");
			when(auditBackfillDao.revisionExists(5)).thenReturn(true);
			
			assertTrue(service.reuseRevisionId() == 5);
		}
	}
	
	@Test
	void shouldReturnNullFromReuseRevisionIdWhenRevisionMissing() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("5");
			when(auditBackfillDao.revisionExists(5)).thenReturn(false);
			
			assertNull(service.reuseRevisionId());
		}
	}
	
	@Test
	void shouldReturnTrueWhenRevisionMatchesStoredBaseline() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("7");
			
			assertTrue(service.isBaselineRevision(7));
		}
	}
	
	@Test
	void shouldReturnFalseWhenRevisionDiffersFromStoredBaseline() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("7");
			
			assertFalse(service.isBaselineRevision(8));
		}
	}
	
	@Test
	void shouldReturnFalseWhenNoStoredBaselineRevision() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("");
			
			assertFalse(service.isBaselineRevision(7));
		}
	}
	
	@Test
	void shouldReturnFalseWhenStoredBaselineRevisionIsNotNumeric() {
		try (MockedStatic<Context> context = mockStatic(Context.class)) {
			context.when(Context::getAdministrationService).thenReturn(administrationService);
			when(administrationService.getGlobalProperty(AuditBackfillService.GP_BACKFILL_REVISION, "")).thenReturn("abc");
			
			assertFalse(service.isBaselineRevision(7));
		}
	}
	
}
