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

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.module.Module;
import org.openmrs.module.ModuleFactory;
import org.openmrs.module.auditlogweb.api.dao.AuditBackfillDao;
import org.openmrs.module.auditlogweb.api.dao.AuditBackfillDao.ColumnSyncResult;
import org.openmrs.module.auditlogweb.api.dao.AuditBackfillDao.SchemaCreationResult;
import org.openmrs.module.auditlogweb.api.dao.AuditBackfillDao.TableMapping;
import org.openmrs.module.auditlogweb.api.utils.EnversUtils;
import org.openmrs.util.OpenmrsConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service class for the audit backfill
 */
@Component("auditlogweb.auditBackfillService")
@RequiredArgsConstructor
public class AuditBackfillService {
	
	private static final Logger log = LoggerFactory.getLogger(AuditBackfillService.class);
	
	public static final String GP_BACKFILL_ENABLED = "auditlogweb.backfillExistingData.enabled";
	
	public static final String GP_BACKFILL_COMPLETED = "auditlogweb.backfillExistingData.completed";
	
	public static final String GP_BACKFILL_REVISION = "auditlogweb.backfillExistingData.revision";
	
	public static final String GP_COLUMN_SYNC_FINGERPRINT = "auditlogweb.auditColumnSync.versionFingerprint";
	
	private final AuditBackfillDao auditBackfillDao;
	
	/**
	 * Creates any missing Envers audit tables if Envers is enabled; a no-op when all tables already
	 * exist. Not gated behind a global property: the tables are required for the module to function at
	 * all, creation is idempotent, and only tables that do not exist are touched.
	 */
	public void createMissingAuditTablesIfEnabled() {
		if (!EnversUtils.isEnversEnabled()) {
			log.info("Envers is disabled (hibernate.integration.envers.enabled != true); skipping audit table creation.");
			return;
		}
		SchemaCreationResult result = auditBackfillDao.createMissingAuditTables();
		if (result.getCreated() > 0) {
			log.warn("Created {} missing Envers audit table(s).", result.getCreated());
		}
		if (!result.getMissingTables().isEmpty()) {
			log.error("{} Envers table(s) could not be created and audited writes to them will fail: {}",
			    result.getMissingTables().size(), result.getMissingTables());
		}
	}
	
	/**
	 * Adds any base-table columns missing from existing audit tables, but only when the platform or
	 * module versions differ from those recorded at the last clean sweep — audited base tables gain
	 * columns through platform and module upgrades, both of which change the version fingerprint. The
	 * fingerprint is only recorded when the sweep had no failures, so failed tables are retried and
	 * re-reported on every startup until repaired.
	 */
	public void syncAuditColumnsIfVersionsChanged() {
		if (!EnversUtils.isEnversEnabled()) {
			log.info("Envers is disabled (hibernate.integration.envers.enabled != true); skipping audit column sync.");
			return;
		}
		AdministrationService administrationService = Context.getAdministrationService();
		String currentFingerprint = currentVersionFingerprint();
		String lastSyncedFingerprint = administrationService.getGlobalProperty(GP_COLUMN_SYNC_FINGERPRINT, "");
		if (currentFingerprint.equals(lastSyncedFingerprint)) {
			return;
		}
		
		ColumnSyncResult result = auditBackfillDao.addMissingAuditColumns();
		if (result.getColumnsAdded() > 0) {
			log.warn("Platform or module versions changed: added {} missing column(s) to existing Envers audit tables.",
			    result.getColumnsAdded());
		}
		if (!result.getFailedTables().isEmpty()) {
			log.error(
			    "Column sync failed for {} audit table(s); audited writes to them may fail: {}. The sync retries on the next startup.",
			    result.getFailedTables().size(), result.getFailedTables());
			return;
		}
		administrationService.setGlobalProperty(GP_COLUMN_SYNC_FINGERPRINT, currentFingerprint);
	}
	
	/**
	 * The platform version plus every started module's id and version, sorted — changes whenever the
	 * platform or any module is upgraded, installed or removed, the events that can alter audited base
	 * tables.
	 */
	String currentVersionFingerprint() {
		StringBuilder fingerprint = new StringBuilder(
		        OpenmrsConstants.OPENMRS_VERSION != null ? OpenmrsConstants.OPENMRS_VERSION : "");
		List<String> moduleVersions = new ArrayList<>();
		for (Module module : ModuleFactory.getStartedModules()) {
			moduleVersions.add(module.getModuleId() + ":" + module.getVersion());
		}
		Collections.sort(moduleVersions);
		for (String moduleVersion : moduleVersions) {
			fingerprint.append('|').append(moduleVersion);
		}
		return fingerprint.toString();
	}
	
	/**
	 * Runs the backfill if Envers is enabled, the feature flag is on, and it has not already run.
	 */
	public void backfillExistingDataIfEnabled() {
		if (!EnversUtils.isEnversEnabled()) {
			log.info("Envers is disabled (hibernate.integration.envers.enabled != true); skipping audit backfill.");
			return;
		}
		
		AdministrationService administrationService = Context.getAdministrationService();
		
		if (!Boolean.parseBoolean(administrationService.getGlobalProperty(GP_BACKFILL_ENABLED, "false"))) {
			log.info("{} is not true; skipping audit backfill.", GP_BACKFILL_ENABLED);
			return;
		}
		if (Boolean.parseBoolean(administrationService.getGlobalProperty(GP_BACKFILL_COMPLETED, "false"))) {
			log.info("Audit backfill already completed ({}=true); skipping.", GP_BACKFILL_COMPLETED);
			return;
		}
		
		List<TableMapping> mappings = auditBackfillDao.resolveBackfillableTableMappings();
		if (mappings.isEmpty()) {
			log.warn("No audited entities resolved from the metamodel; aborting audit backfill.");
			return;
		}
		
		log.warn("Starting one-time audit backfill of existing data into {} audited tables...", mappings.size());
		
		Integer revId = reuseRevisionId();
		if (revId == null) {
			revId = auditBackfillDao.createBaselineRevision();
			administrationService.setGlobalProperty(GP_BACKFILL_REVISION, String.valueOf(revId));
		}
		int revision = revId;
		
		boolean allSucceeded = true;
		for (TableMapping mapping : auditBackfillDao.orderByAuditTableDependencies(mappings)) {
			try {
				long insertedRows = auditBackfillDao.backfillTable(mapping, revision);
				if (insertedRows > 0) {
					log.info("Audit backfill: {} -> {} ({} rows).", mapping.getBaseTable(), mapping.getAuditTable(),
					    insertedRows);
				}
			}
			catch (Exception e) {
				allSucceeded = false;
				log.warn("Audit backfill skipped for {} -> {}: {}", mapping.getBaseTable(), mapping.getAuditTable(),
				    describeRootCause(e));
			}
		}
		
		if (allSucceeded) {
			administrationService.setGlobalProperty(GP_BACKFILL_COMPLETED, "true");
			log.warn("Audit backfill finished at revision {}.", revision);
		} else {
			log.warn("Audit backfill did not complete for all tables; {} stays false so it resumes on next startup.",
			    GP_BACKFILL_COMPLETED);
		}
	}
	
	Integer reuseRevisionId() {
		String storedRevisionId = Context.getAdministrationService().getGlobalProperty(GP_BACKFILL_REVISION, "");
		if (StringUtils.isBlank(storedRevisionId)) {
			return null;
		}
		try {
			int revId = Integer.parseInt(storedRevisionId.trim());
			return auditBackfillDao.revisionExists(revId) ? revId : null;
		}
		catch (NumberFormatException e) {
			return null;
		}
	}
	
	/**
	 * Determines whether the given revision is the baseline created by the one-time backfill process.
	 */
	public boolean isBaselineRevision(int revisionId) {
		String storedRevisionId = Context.getAdministrationService().getGlobalProperty(GP_BACKFILL_REVISION, "");
		if (StringUtils.isBlank(storedRevisionId)) {
			return false;
		}
		try {
			return Integer.parseInt(storedRevisionId.trim()) == revisionId;
		}
		catch (NumberFormatException e) {
			return false;
		}
	}
	
	private String describeRootCause(Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}
	
}
