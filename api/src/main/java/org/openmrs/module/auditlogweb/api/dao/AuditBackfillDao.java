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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.identity.IdentityColumnSupport;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.id.IdentityGenerator;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.hibernate.metamodel.spi.MetamodelImplementor;
import org.hibernate.persister.entity.AbstractEntityPersister;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.tool.schema.TargetType;
import org.hibernate.tool.schema.spi.ExceptionHandler;
import org.hibernate.tool.schema.spi.ExecutionOptions;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;
import org.hibernate.tool.schema.spi.SchemaManagementTool;
import org.hibernate.tool.schema.spi.SchemaMigrator;
import org.hibernate.tool.schema.spi.ScriptTargetOutput;
import org.hibernate.tool.schema.spi.TargetDescriptor;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.hibernate.HibernateSessionFactoryBean;
import org.openmrs.api.db.hibernate.envers.OpenmrsRevisionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * DAO class for the audit backfill
 */
@Repository("auditlogweb.auditBackfillDao")
@RequiredArgsConstructor
public class AuditBackfillDao {
	
	private static final Logger log = LoggerFactory.getLogger(AuditBackfillDao.class);
	
	private static final Set<String> ENVERS_TECHNICAL_COLUMNS = new HashSet<>(
	        Arrays.asList("REV", "REVTYPE", "REVEND", "REVEND_TSTMP"));
	
	private final SessionFactory sessionFactory;
	
	/**
	 * Resolves the base/audit table pairs for every {@code @Audited} entity in the metamodel, honouring
	 * the configurable Envers table prefix/suffix and any {@code @AuditTable} override.
	 */
	public List<TableMapping> resolveAuditedTableMappings() {
		SessionFactoryImplementor sfi = sessionFactory.unwrap(SessionFactoryImplementor.class);
		MetamodelImplementor metamodel = sfi.getMetamodel();
		String[] affixes = auditTableAffixes(sfi);
		String prefix = affixes[0];
		String suffix = affixes[1];
		
		List<TableMapping> result = new ArrayList<>();
		Set<String> seenAuditTables = new LinkedHashSet<>();
		for (EntityPersister persister : metamodel.entityPersisters().values()) {
			if (!(persister instanceof AbstractEntityPersister)) {
				continue;
			}
			Class<?> mappedClass = persister.getMappedClass();
			if (mappedClass == null || !mappedClass.isAnnotationPresent(Audited.class)) {
				continue;
			}
			
			AbstractEntityPersister aep = (AbstractEntityPersister) persister;
			String baseTable = unqualifiedTableName(aep.getTableName());
			String auditTable = deriveAuditTableName(mappedClass, baseTable, prefix, suffix);
			
			if (seenAuditTables.add(auditTable.toLowerCase(Locale.ROOT))) {
				result.add(new TableMapping(baseTable, auditTable));
			}
		}
		return result;
	}
	
	/**
	 * Derives the audit table name for an entity: the {@code @AuditTable} value if present, otherwise
	 * {@code prefix + baseTable + suffix}.
	 */
	String deriveAuditTableName(Class<?> mappedClass, String baseTable, String prefix, String suffix) {
		AuditTable auditTableAnnotation = mappedClass.getAnnotation(AuditTable.class);
		if (auditTableAnnotation != null && auditTableAnnotation.value() != null
		        && !auditTableAnnotation.value().isEmpty()) {
			return auditTableAnnotation.value();
		}
		return prefix + baseTable + suffix;
	}
	
	/**
	 * Orders the mappings so that any audit table is preceded by the audit tables it references via
	 * foreign keys (parents first). This matters for joined-subclass inheritance, where a child audit
	 * table has a composite (id, REV) foreign key to its parent audit table.
	 */
	public List<TableMapping> orderByAuditTableDependencies(List<TableMapping> mappings) {
		try (Session session = sessionFactory.openSession()) {
			return session.doReturningWork(
			    connection -> orderParentsBeforeChildren(mappings, readAuditTableParents(mappings, connection)));
		}
	}
	
	/**
	 * Creates the Envers revision table and any missing audit tables; without them every audited write
	 * fails. Audit tables whose base table exists are cloned from the base table's JDBC metadata (the
	 * real column types, immune to entity-annotation drift); the remaining tables — Envers' relation
	 * tables for unidirectional collections (e.g. Location_LocationAttribute), which have no base table
	 * — are created by Hibernate's schema migration restricted to exactly those table names.
	 * Idempotent: only tables that do not exist yet are created, each in its own try/catch so one
	 * failure does not stop the rest.
	 *
	 * @return the number of tables created and the Envers tables that are still missing afterwards
	 */
	public SchemaCreationResult createMissingAuditTables() {
		SessionFactoryImplementor sfi = sessionFactory.unwrap(SessionFactoryImplementor.class);
		
		int created;
		try (Session session = sessionFactory.openSession()) {
			created = session.doReturningWork(connection -> {
				DatabaseMetaData metaData = connection.getMetaData();
				String catalog = connection.getCatalog();
				String quote = identifierQuote(metaData);
				Set<String> existingTables = readExistingTableNames(metaData, catalog);
				Dialect dialect = sfi.getJdbcServices().getDialect();
				
				int count = createRevisionTableIfMissing(connection, sfi, dialect, existingTables, quote);
				count += createAuditTablesIfMissing(connection, metaData, catalog, dialect, existingTables, quote);
				return count;
			});
		}
		created += createBaselessTablesViaMigration(sfi);
		return new SchemaCreationResult(created, findMissingEnversTables(sfi));
	}
	
	/**
	 * Every Envers table the metamodel expects (the revision table plus all audit tables) that still
	 * does not exist in the database. Non-empty means audited writes to those tables will fail.
	 */
	private List<String> findMissingEnversTables(SessionFactoryImplementor sfi) {
		Set<String> expectedTables = new LinkedHashSet<>();
		AbstractEntityPersister revisionPersister = findRevisionEntityPersister(sfi);
		if (revisionPersister != null) {
			expectedTables.add(unqualifiedTableName(revisionPersister.getTableName()).toLowerCase(Locale.ROOT));
		} else {
			expectedTables.add("revision_entity");
		}
		for (TableMapping mapping : resolveAllAuditTableMappings()) {
			expectedTables.add(mapping.auditTable.toLowerCase(Locale.ROOT));
		}
		return new ArrayList<>(missingTables(expectedTables));
	}
	
	/**
	 * Creates the revision table (revision_entity) if it is missing, deriving its DDL from the
	 * {@link OpenmrsRevisionEntity} persister so names and types follow core's actual mapping.
	 *
	 * @return 1 if the table was created, 0 otherwise
	 */
	private int createRevisionTableIfMissing(Connection connection, SessionFactoryImplementor sfi, Dialect dialect,
	        Set<String> existingTables, String quote) {
		AbstractEntityPersister persister = findRevisionEntityPersister(sfi);
		if (persister == null) {
			log.warn("Could not locate the Envers revision entity in the metamodel; skipping revision table creation.");
			return 0;
		}
		String tableName = unqualifiedTableName(persister.getTableName());
		if (existingTables.contains(tableName.toLowerCase(Locale.ROOT))) {
			return 0;
		}
		try {
			String idColumn = persister.getIdentifierColumnNames()[0];
			String idColumnDdl = revisionIdColumnDdl(persister, dialect, sfi);
			
			List<ColumnDefinition> propertyColumns = new ArrayList<>();
			String[] propertyNames = persister.getPropertyNames();
			boolean[] nullability = persister.getPropertyNullability();
			for (int i = 0; i < propertyNames.length; i++) {
				String column = persister.getPropertyColumnNames(i)[0];
				int sqlType = persister.getPropertyTypes()[i].sqlTypes(sfi)[0];
				String typeDdl = dialect.getTypeName(sqlType, Column.DEFAULT_LENGTH, Column.DEFAULT_PRECISION,
				    Column.DEFAULT_SCALE);
				propertyColumns.add(new ColumnDefinition(column, typeDdl, nullability[i]));
			}
			
			String sql = buildCreateRevisionTableSql(tableName, idColumn, idColumnDdl, propertyColumns, quote);
			try (Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}
			existingTables.add(tableName.toLowerCase(Locale.ROOT));
			log.warn("Created Envers revision table {}.", tableName);
			return 1;
		}
		catch (Exception e) {
			log.warn("Could not create Envers revision table {}", tableName, e);
			return 0;
		}
	}
	
	private AbstractEntityPersister findRevisionEntityPersister(SessionFactoryImplementor sfi) {
		for (EntityPersister persister : sfi.getMetamodel().entityPersisters().values()) {
			if (persister instanceof AbstractEntityPersister
			        && OpenmrsRevisionEntity.class.equals(persister.getMappedClass())) {
				return (AbstractEntityPersister) persister;
			}
		}
		return null;
	}
	
	/**
	 * DDL for the revision id column: identity/auto-increment when the mapping uses an identity
	 * generator (the MySQL case), a plain NOT NULL column otherwise.
	 */
	private String revisionIdColumnDdl(AbstractEntityPersister persister, Dialect dialect, SessionFactoryImplementor sfi) {
		int idSqlType = persister.getIdentifierType().sqlTypes(sfi)[0];
		if (persister.getIdentifierGenerator() instanceof IdentityGenerator) {
			IdentityColumnSupport identity = dialect.getIdentityColumnSupport();
			if (identity.hasDataTypeInIdentityColumn()) {
				return dialect.getTypeName(idSqlType) + " " + identity.getIdentityColumnString(idSqlType);
			}
			return identity.getIdentityColumnString(idSqlType);
		}
		return dialect.getTypeName(idSqlType) + " not null";
	}
	
	/**
	 * Creates every missing audit table whose base table exists, cloning the base table's columns from
	 * JDBC metadata.
	 *
	 * @return the number of audit tables created
	 */
	private int createAuditTablesIfMissing(Connection connection, DatabaseMetaData metaData, String catalog, Dialect dialect,
	        Set<String> existingTables, String quote) {
		String revColumnType = dialect.getTypeName(Types.INTEGER);
		String revTypeColumnType = dialect.getTypeName(Types.TINYINT);
		
		int created = 0;
		for (TableMapping mapping : resolveAllAuditTableMappings()) {
			if (existingTables.contains(mapping.auditTable.toLowerCase(Locale.ROOT))) {
				continue;
			}
			if (!existingTables.contains(mapping.baseTable.toLowerCase(Locale.ROOT))) {
				log.debug("Base table {} does not exist; audit table {} is left to the schema migration pass.",
				    mapping.baseTable, mapping.auditTable);
				continue;
			}
			try {
				List<ColumnDefinition> baseColumns = readColumnDefinitions(metaData, catalog, mapping.baseTable);
				List<String> pkColumns = getPrimaryKeyColumns(metaData, catalog, mapping.baseTable);
				String sql = buildCreateAuditTableSql(mapping, baseColumns, pkColumns, revColumnType, revTypeColumnType,
				    quote);
				try (Statement statement = connection.createStatement()) {
					statement.execute(sql);
				}
				existingTables.add(mapping.auditTable.toLowerCase(Locale.ROOT));
				created++;
				log.info("Created Envers audit table {}.", mapping.auditTable);
				try (Statement statement = connection.createStatement()) {
					statement.execute(buildCreateRevIndexSql(mapping, quote));
				}
				catch (Exception e) {
					log.warn("Could not create REV index on {}", mapping.auditTable, e);
				}
			}
			catch (Exception e) {
				log.warn("Could not create audit table {}", mapping.auditTable, e);
			}
		}
		return created;
	}
	
	/**
	 * All base/audit table pairs Envers writes to: the {@code @Audited} entities from
	 * {@link #resolveAuditedTableMappings()} plus the dynamic audit entities Envers registers for
	 * collection/join middle tables (e.g. role_privilege), which the annotation scan cannot see.
	 */
	public List<TableMapping> resolveAllAuditTableMappings() {
		SessionFactoryImplementor sfi = sessionFactory.unwrap(SessionFactoryImplementor.class);
		List<TableMapping> mappings = new ArrayList<>(resolveAuditedTableMappings());
		
		Set<String> seenAuditTables = new HashSet<>();
		for (TableMapping mapping : mappings) {
			seenAuditTables.add(mapping.auditTable.toLowerCase(Locale.ROOT));
		}
		
		String[] affixes = auditTableAffixes(sfi);
		for (EntityPersister persister : sfi.getMetamodel().entityPersisters().values()) {
			if (!(persister instanceof AbstractEntityPersister)) {
				continue;
			}
			String table = unqualifiedTableName(((AbstractEntityPersister) persister).getTableName());
			String baseTable = stripAuditAffixes(table, affixes[0], affixes[1]);
			if (baseTable != null && seenAuditTables.add(table.toLowerCase(Locale.ROOT))) {
				mappings.add(new TableMapping(baseTable, table));
			}
		}
		return mappings;
	}
	
	/**
	 * Adds to every existing audit table the base-table columns it lacks, cloned nullable from the base
	 * table's JDBC metadata — add-only, never drops or retypes. Heals audit tables that went stale
	 * because a platform upgrade added columns to their base table; without this, Envers inserts naming
	 * a new column fail against the old audit table. A column whose type diverges from the base column
	 * (e.g. the base was widened by a platform upgrade) is reported as a failure rather than altered,
	 * so the mismatch stays visible at every startup until repaired.
	 *
	 * @return the number of columns added and the audit tables whose sync failed
	 */
	public ColumnSyncResult addMissingAuditColumns() {
		try (Session session = sessionFactory.openSession()) {
			return session.doReturningWork(connection -> {
				DatabaseMetaData metaData = connection.getMetaData();
				String catalog = connection.getCatalog();
				String quote = identifierQuote(metaData);
				Set<String> existingTables = readExistingTableNames(metaData, catalog);
				
				int added = 0;
				List<String> failedTables = new ArrayList<>();
				for (TableMapping mapping : resolveAllAuditTableMappings()) {
					if (!existingTables.contains(mapping.auditTable.toLowerCase(Locale.ROOT))) {
						continue;
					}
					if (!existingTables.contains(mapping.baseTable.toLowerCase(Locale.ROOT))) {
						log.debug("No base table {} to sync columns from; skipping {}.", mapping.baseTable,
						    mapping.auditTable);
						continue;
					}
					try {
						Map<String, String> auditColumnTypes = readColumnTypes(metaData, catalog, mapping.auditTable);
						List<String> mismatchedColumns = new ArrayList<>();
						for (ColumnDefinition column : readColumnDefinitions(metaData, catalog, mapping.baseTable)) {
							String auditType = auditColumnTypes.get(column.name.toLowerCase(Locale.ROOT));
							if (auditType == null) {
								try (Statement statement = connection.createStatement()) {
									statement.execute(buildAddColumnSql(mapping, column, quote));
								}
								added++;
								log.warn("Added column {} to audit table {}.", column.name, mapping.auditTable);
							} else if (!auditType.equalsIgnoreCase(column.typeDdl)) {
								mismatchedColumns.add(column.name + " is " + auditType + ", base is " + column.typeDdl);
							}
						}
						if (!mismatchedColumns.isEmpty()) {
							failedTables.add(mapping.auditTable);
							log.error(
							    "Audit table {} has column type(s) diverging from base table {} and must be widened manually: {}",
							    mapping.auditTable, mapping.baseTable, mismatchedColumns);
						}
						try {
							if (!hasRevLedIndex(metaData, catalog, mapping.auditTable)) {
								log.warn("Adding REV index to audit table {} (may take a while on large tables)...",
								    mapping.auditTable);
								try (Statement statement = connection.createStatement()) {
									statement.execute(buildCreateRevIndexSql(mapping, quote));
								}
							}
						}
						catch (Exception e) {
							log.warn("Could not add REV index to {}", mapping.auditTable, e);
						}
					}
					catch (Exception e) {
						failedTables.add(mapping.auditTable);
						log.warn("Could not sync columns of audit table {}", mapping.auditTable, e);
					}
				}
				return new ColumnSyncResult(added, failedTables);
			});
		}
	}
	
	/**
	 * Whether the table has any index whose leading column is REV (the composite PK does not count).
	 */
	private boolean hasRevLedIndex(DatabaseMetaData md, String catalog, String table) throws SQLException {
		try (ResultSet rs = md.getIndexInfo(catalog, null, table, false, false)) {
			while (rs.next()) {
				if ("REV".equalsIgnoreCase(rs.getString("COLUMN_NAME")) && rs.getShort("ORDINAL_POSITION") == 1) {
					return true;
				}
			}
		}
		return false;
	}
	
	/** The table's columns as lowercased name to complete type DDL. */
	private Map<String, String> readColumnTypes(DatabaseMetaData md, String catalog, String table) throws SQLException {
		Map<String, String> types = new HashMap<>();
		for (ColumnDefinition column : readColumnDefinitions(md, catalog, table)) {
			types.put(column.name.toLowerCase(Locale.ROOT), column.typeDdl);
		}
		return types;
	}
	
	/** ALTER statement adding one base-table column to its audit table, nullable. */
	String buildAddColumnSql(TableMapping mapping, ColumnDefinition column, String quote) {
		return "ALTER TABLE " + quoteIdentifier(mapping.auditTable, quote) + " ADD " + quoteIdentifier(column.name, quote)
		        + " " + column.typeDdl;
	}
	
	/**
	 * The mappings the backfill can copy rows into: every Envers audit table whose base table exists
	 * and has a primary key (the idempotent insert joins on it). Excluded tables — relation audit
	 * tables without a base table (e.g. Location_LocationAttribute) and tables whose base has no
	 * primary key (e.g. concept_name_tag_map) — simply get no baseline rows.
	 */
	public List<TableMapping> resolveBackfillableTableMappings() {
		List<TableMapping> mappings = resolveAllAuditTableMappings();
		try (Session session = sessionFactory.openSession()) {
			return session.doReturningWork(connection -> {
				DatabaseMetaData metaData = connection.getMetaData();
				String catalog = connection.getCatalog();
				Set<String> existingTables = readExistingTableNames(metaData, catalog);
				
				List<TableMapping> result = new ArrayList<>();
				for (TableMapping mapping : filterMappingsWithExistingBase(mappings, existingTables)) {
					if (getPrimaryKeyColumns(metaData, catalog, mapping.baseTable).isEmpty()) {
						log.info("Base table {} has no primary key; skipping backfill of {}.", mapping.baseTable,
						    mapping.auditTable);
						continue;
					}
					result.add(mapping);
				}
				return result;
			});
		}
	}
	
	List<TableMapping> filterMappingsWithExistingBase(List<TableMapping> mappings, Set<String> existingTables) {
		List<TableMapping> result = new ArrayList<>();
		for (TableMapping mapping : mappings) {
			if (existingTables.contains(mapping.baseTable.toLowerCase(Locale.ROOT))) {
				result.add(mapping);
			} else {
				log.debug("No base table {} to backfill from; skipping {}.", mapping.baseTable, mapping.auditTable);
			}
		}
		return result;
	}
	
	/**
	 * Creates whatever Envers tables are still missing after the clone pass — the relation audit tables
	 * that have no base table — via Hibernate's schema migration over the boot-time metadata,
	 * restricted to exactly those table names.
	 *
	 * @return the number of tables the migration created
	 */
	private int createBaselessTablesViaMigration(SessionFactoryImplementor sfi) {
		Metadata metadata = bootMetadata();
		if (metadata == null) {
			log.warn("Hibernate boot metadata is unavailable; cannot create audit tables that have no base table.");
			return 0;
		}
		String[] affixes = auditTableAffixes(sfi);
		Set<String> expectedTables = expectedEnversTables(metadata, affixes[0], affixes[1]);
		Set<String> missingBefore = missingTables(expectedTables);
		if (missingBefore.isEmpty()) {
			return 0;
		}
		
		log.warn("Creating {} remaining Envers table(s) via Hibernate schema migration...", missingBefore.size());
		runEnversSchemaMigration(metadata, sfi, missingBefore);
		
		Set<String> missingAfter = missingTables(expectedTables);
		for (String table : missingBefore) {
			if (!missingAfter.contains(table)) {
				log.info("Created Envers table {} via Hibernate schema migration.", table);
			}
		}
		return missingBefore.size() - missingAfter.size();
	}
	
	/**
	 * The boot-time Hibernate metadata (which includes the Envers audit entity mappings), captured by
	 * core's session factory bean at bootstrap; null when it cannot be obtained.
	 */
	private Metadata bootMetadata() {
		try {
			List<HibernateSessionFactoryBean> beans = Context.getRegisteredComponents(HibernateSessionFactoryBean.class);
			return beans.isEmpty() ? null : beans.get(0).getMetadata();
		}
		catch (Exception e) {
			log.warn("Could not access the Hibernate session factory bean", e);
			return null;
		}
	}
	
	/** The lowercased unqualified names of every Envers table mapped in the metadata. */
	Set<String> expectedEnversTables(Metadata metadata, String prefix, String suffix) {
		Set<String> result = new LinkedHashSet<>();
		for (Table table : metadata.collectTableMappings()) {
			String name = unqualifiedTableName(table.getName());
			if (isEnversTable(name, prefix, suffix)) {
				result.add(name.toLowerCase(Locale.ROOT));
			}
		}
		return result;
	}
	
	/** Which of the expected tables do not exist in the database (lowercased names). */
	private Set<String> missingTables(Set<String> expectedTables) {
		try (Session session = sessionFactory.openSession()) {
			return session.doReturningWork(connection -> {
				Set<String> missing = new LinkedHashSet<>(expectedTables);
				missing.removeAll(readExistingTableNames(connection.getMetaData(), connection.getCatalog()));
				return missing;
			});
		}
	}
	
	/**
	 * Whether a table is one Envers writes to: the revision table, or any table following the
	 * configured audit prefix/suffix naming convention.
	 */
	boolean isEnversTable(String tableName, String prefix, String suffix) {
		String lower = tableName.toLowerCase(Locale.ROOT);
		if ("revision_entity".equals(lower) || "revinfo".equals(lower)) {
			return true;
		}
		return stripAuditAffixes(tableName, prefix, suffix) != null;
	}
	
	/**
	 * Runs Hibernate's schema migration (the mechanism behind hbm2ddl.auto=update) restricted to
	 * exactly the given table names, so it cannot touch any other table. Failures are logged per
	 * statement; missing tables are reported by the caller's before/after comparison.
	 */
	private void runEnversSchemaMigration(Metadata metadata, SessionFactoryImplementor sfi, Set<String> tableNames) {
		Map<String, Object> settings = new HashMap<>(sfi.getProperties());
		settings.put(AvailableSettings.HBM2DDL_FILTER_PROVIDER, new NamedTableSchemaFilterProvider(tableNames));
		
		SchemaMigrator migrator = sfi.getServiceRegistry().getService(SchemaManagementTool.class)
		        .getSchemaMigrator(settings);
		
		ExecutionOptions options = new ExecutionOptions() {
			
			@Override
			public Map getConfigurationValues() {
				return settings;
			}
			
			@Override
			public boolean shouldManageNamespaces() {
				return false;
			}
			
			@Override
			public ExceptionHandler getExceptionHandler() {
				return exception -> log.warn("Envers schema migration issue", exception);
			}
		};
		TargetDescriptor targetDescriptor = new TargetDescriptor() {
			
			@Override
			public EnumSet<TargetType> getTargetTypes() {
				return EnumSet.of(TargetType.DATABASE);
			}
			
			@Override
			public ScriptTargetOutput getScriptTargetOutput() {
				return null;
			}
		};
		migrator.doMigration(metadata, options, targetDescriptor);
	}
	
	/** Restricts Hibernate's schema tooling to an explicit set of (lowercased) table names. */
	private final class NamedTableSchemaFilterProvider implements SchemaFilterProvider, SchemaFilter {
		
		private final Set<String> tableNames;
		
		private NamedTableSchemaFilterProvider(Set<String> tableNames) {
			this.tableNames = tableNames;
		}
		
		@Override
		public SchemaFilter getCreateFilter() {
			return this;
		}
		
		@Override
		public SchemaFilter getDropFilter() {
			return this;
		}
		
		@Override
		public SchemaFilter getMigrateFilter() {
			return this;
		}
		
		@Override
		public SchemaFilter getValidateFilter() {
			return this;
		}
		
		@Override
		public boolean includeNamespace(Namespace namespace) {
			return true;
		}
		
		@Override
		public boolean includeTable(Table table) {
			return tableNames.contains(unqualifiedTableName(table.getName()).toLowerCase(Locale.ROOT));
		}
		
		@Override
		public boolean includeSequence(Sequence sequence) {
			return false;
		}
	}
	
	/**
	 * The Envers audit table prefix and suffix, read from the SessionFactory's merged settings (which
	 * include both core's hibernate.default.properties and the runtime properties). Falls back to
	 * Envers' own defaults: empty prefix and {@code _AUD} — note that stock OpenMRS 2.7/2.8 does not
	 * override the suffix, so {@code _audit} must never be assumed.
	 */
	String[] auditTableAffixes(SessionFactoryImplementor sfi) {
		Map<String, Object> settings = sfi.getProperties();
		Object prefix = settings.get("org.hibernate.envers.audit_table_prefix");
		Object suffix = settings.get("org.hibernate.envers.audit_table_suffix");
		return new String[] { prefix != null ? prefix.toString() : "", suffix != null ? suffix.toString() : "_AUD" };
	}
	
	/**
	 * Derives the base table name from an audit table name by stripping the configured prefix and
	 * suffix, or returns null when the name does not follow the audit naming convention.
	 */
	String stripAuditAffixes(String tableName, String prefix, String suffix) {
		if (tableName == null || (prefix.isEmpty() && suffix.isEmpty())) {
			return null;
		}
		if (!tableName.startsWith(prefix) || !tableName.endsWith(suffix)
		        || tableName.length() <= prefix.length() + suffix.length()) {
			return null;
		}
		return tableName.substring(prefix.length(), tableName.length() - suffix.length());
	}
	
	private Map<String, Set<String>> readAuditTableParents(List<TableMapping> mappings, Connection connection)
	        throws SQLException {
		DatabaseMetaData md = connection.getMetaData();
		String catalog = connection.getCatalog();
		
		Set<String> auditTableNames = new HashSet<>();
		for (TableMapping mapping : mappings) {
			auditTableNames.add(mapping.auditTable.toLowerCase(Locale.ROOT));
		}
		
		Map<String, Set<String>> parentsByAuditTable = new HashMap<>();
		for (TableMapping mapping : mappings) {
			String child = mapping.auditTable.toLowerCase(Locale.ROOT);
			Set<String> parents = new HashSet<>();
			try (ResultSet rs = md.getImportedKeys(catalog, null, mapping.auditTable)) {
				while (rs.next()) {
					String referenced = rs.getString("PKTABLE_NAME");
					if (referenced == null) {
						continue;
					}
					String parent = referenced.toLowerCase(Locale.ROOT);
					if (!parent.equals(child) && auditTableNames.contains(parent)) {
						parents.add(parent);
					}
				}
			}
			parentsByAuditTable.put(child, parents);
		}
		return parentsByAuditTable;
	}
	
	/**
	 * Reorders the mappings so that every audit table comes after the audit tables it depends on (its
	 * parents).
	 */
	List<TableMapping> orderParentsBeforeChildren(List<TableMapping> mappings,
	        Map<String, Set<String>> parentsByAuditTable) {
		List<TableMapping> ordered = new ArrayList<>();
		Set<String> emitted = new HashSet<>();
		List<TableMapping> remaining = new ArrayList<>(mappings);
		boolean progress = true;
		while (!remaining.isEmpty() && progress) {
			progress = false;
			Iterator<TableMapping> it = remaining.iterator();
			while (it.hasNext()) {
				TableMapping mapping = it.next();
				String name = mapping.auditTable.toLowerCase(Locale.ROOT);
				Set<String> parents = parentsByAuditTable.getOrDefault(name, Collections.emptySet());
				if (emitted.containsAll(parents)) {
					ordered.add(mapping);
					emitted.add(name);
					it.remove();
					progress = true;
				}
			}
		}
		ordered.addAll(remaining);
		return ordered;
	}
	
	/** Whether a revision_entity row with the given id exists. */
	public boolean revisionExists(int revisionId) {
		try (Session session = sessionFactory.openSession()) {
			return session.get(OpenmrsRevisionEntity.class, revisionId) != null;
		}
	}
	
	/** Creates and commits a single baseline revision row and returns its generated id. */
	public int createBaselineRevision() {
		try (Session session = sessionFactory.openSession()) {
			Transaction tx = session.beginTransaction();
			try {
				OpenmrsRevisionEntity revision = new OpenmrsRevisionEntity();
				revision.setTimestamp(System.currentTimeMillis());
				revision.setChangedOn(new Date());
				session.save(revision);
				tx.commit();
				return revision.getId();
			}
			catch (RuntimeException e) {
				safeRollback(tx);
				throw e;
			}
		}
	}
	
	/**
	 * Copies rows that are not yet present in the audit table, stamping them with the baseline
	 * revision. Runs in its own transaction so a failure is bounded to this table.
	 * 
	 * @return the number of rows inserted
	 */
	public long backfillTable(TableMapping mapping, int revisionId) {
		try (Session session = sessionFactory.openSession()) {
			Transaction tx = session.beginTransaction();
			try {
				long insertedRows = session.doReturningWork(connection -> executeBackfill(connection, mapping, revisionId));
				tx.commit();
				return insertedRows;
			}
			catch (RuntimeException e) {
				safeRollback(tx);
				throw e;
			}
		}
	}
	
	private long executeBackfill(Connection connection, TableMapping mapping, int revId) throws SQLException {
		DatabaseMetaData md = connection.getMetaData();
		String catalog = connection.getCatalog();
		String quote = identifierQuote(md);
		
		List<String> auditColumns = getColumnNames(md, catalog, mapping.auditTable);
		if (auditColumns.isEmpty()) {
			throw new IllegalStateException("audit columns not found");
		}
		Set<String> baseColumns = toLowerCaseSet(getColumnNames(md, catalog, mapping.baseTable));
		Set<String> auditColumnsLower = toLowerCaseSet(auditColumns);
		
		List<String> dataColumns = new ArrayList<>();
		for (String column : auditColumns) {
			if (ENVERS_TECHNICAL_COLUMNS.contains(column.toUpperCase(Locale.ROOT))) {
				continue;
			}
			if (baseColumns.contains(column.toLowerCase(Locale.ROOT))) {
				dataColumns.add(column);
			}
		}
		if (dataColumns.isEmpty()) {
			throw new IllegalStateException("no common data columns between base and audit table");
		}
		
		boolean hasRevType = auditColumnsLower.contains("revtype");
		List<String> joinColumns = new ArrayList<>();
		for (String pk : getPrimaryKeyColumns(md, catalog, mapping.baseTable)) {
			if (auditColumnsLower.contains(pk.toLowerCase(Locale.ROOT))) {
				joinColumns.add(pk);
			}
		}
		if (joinColumns.isEmpty()) {
			throw new IllegalStateException("no shared key columns between base and audit table");
		}
		
		String sql = buildBackfillInsertSql(mapping, dataColumns, joinColumns, hasRevType, revId, quote);
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			return ps.executeUpdate();
		}
	}
	
	/**
	 * Builds the idempotent statement that copies base rows into the audit table at the given revision.
	 */
	String buildBackfillInsertSql(TableMapping mapping, List<String> dataColumns, List<String> joinColumns,
	        boolean hasRevType, int revId, String quote) {
		StringBuilder sql = new StringBuilder("INSERT INTO ").append(quoteIdentifier(mapping.auditTable, quote))
		        .append(" (");
		for (String column : dataColumns) {
			sql.append(quoteIdentifier(column, quote)).append(", ");
		}
		sql.append("REV").append(hasRevType ? ", REVTYPE) SELECT " : ") SELECT ");
		for (String column : dataColumns) {
			sql.append("b.").append(quoteIdentifier(column, quote)).append(", ");
		}
		sql.append(revId).append(hasRevType ? ", 0" : "").append(" FROM ").append(quoteIdentifier(mapping.baseTable, quote))
		        .append(" b WHERE NOT EXISTS (SELECT 1 FROM ").append(quoteIdentifier(mapping.auditTable, quote))
		        .append(" a WHERE ");
		for (int i = 0; i < joinColumns.size(); i++) {
			if (i > 0) {
				sql.append(" AND ");
			}
			sql.append("a.").append(quoteIdentifier(joinColumns.get(i), quote)).append(" = b.")
			        .append(quoteIdentifier(joinColumns.get(i), quote));
		}
		sql.append(")");
		return sql.toString();
	}
	
	/**
	 * Builds the CREATE TABLE statement for the Envers revision table from persister-derived inputs.
	 */
	String buildCreateRevisionTableSql(String tableName, String idColumn, String idColumnDdl,
	        List<ColumnDefinition> propertyColumns, String quote) {
		StringBuilder sql = new StringBuilder("CREATE TABLE ").append(quoteIdentifier(tableName, quote)).append(" (");
		sql.append(quoteIdentifier(idColumn, quote)).append(' ').append(idColumnDdl);
		for (ColumnDefinition column : propertyColumns) {
			sql.append(", ").append(quoteIdentifier(column.name, quote)).append(' ').append(column.typeDdl);
			if (!column.nullable) {
				sql.append(" NOT NULL");
			}
		}
		sql.append(", PRIMARY KEY (").append(quoteIdentifier(idColumn, quote)).append("))");
		return sql.toString();
	}
	
	/**
	 * Builds the CREATE TABLE statement for one audit table: all base columns (nullable), plus REV and
	 * REVTYPE, with PRIMARY KEY (base PK columns, REV). No defaults, no auto-increment, no unique
	 * indexes (a cloned unique index would break multiple revisions of the same row) and no foreign
	 * keys — unlike Envers' own DDL, whose REV foreign key gives it a REV index for free; the separate
	 * statement from {@link #buildCreateRevIndexSql} replaces that index.
	 */
	String buildCreateAuditTableSql(TableMapping mapping, List<ColumnDefinition> baseColumns, List<String> basePkColumns,
	        String revColumnType, String revTypeColumnType, String quote) {
		StringBuilder sql = new StringBuilder("CREATE TABLE ").append(quoteIdentifier(mapping.auditTable, quote))
		        .append(" (");
		for (ColumnDefinition column : baseColumns) {
			sql.append(quoteIdentifier(column.name, quote)).append(' ').append(column.typeDdl).append(", ");
		}
		sql.append("REV ").append(revColumnType).append(" NOT NULL, REVTYPE ").append(revTypeColumnType);
		if (!basePkColumns.isEmpty()) {
			sql.append(", PRIMARY KEY (");
			for (String pkColumn : basePkColumns) {
				sql.append(quoteIdentifier(pkColumn, quote)).append(", ");
			}
			sql.append("REV)");
		}
		sql.append(")");
		return sql.toString();
	}
	
	/**
	 * Builds the CREATE INDEX statement for the REV column. The composite primary key (base PK, REV)
	 * cannot serve revision-only lookups such as getEntitiesModifiedInRevision, which filter on REV
	 * without the base id; without this index those queries scan the whole audit table.
	 */
	String buildCreateRevIndexSql(TableMapping mapping, String quote) {
		return "CREATE INDEX " + quoteIdentifier(mapping.auditTable + "_rev", quote) + " ON "
		        + quoteIdentifier(mapping.auditTable, quote) + " (REV)";
	}
	
	/** Reads the column definitions of a table from JDBC metadata; audit clones are always nullable. */
	private List<ColumnDefinition> readColumnDefinitions(DatabaseMetaData md, String catalog, String table)
	        throws SQLException {
		List<ColumnDefinition> columns = new ArrayList<>();
		try (ResultSet rs = md.getColumns(catalog, null, escapeMetadataPattern(md, table), "%")) {
			while (rs.next()) {
				String typeDdl = columnTypeDdl(rs.getString("TYPE_NAME"), rs.getInt("COLUMN_SIZE"),
				    rs.getInt("DECIMAL_DIGITS"));
				columns.add(new ColumnDefinition(rs.getString("COLUMN_NAME"), typeDdl, true));
			}
		}
		return columns;
	}
	
	/**
	 * Re-attaches the size arguments JDBC strips from sized types; every other TYPE_NAME comes back
	 * complete and passes through unchanged.
	 */
	String columnTypeDdl(String typeName, int size, int digits) {
		String upper = typeName == null ? "" : typeName.toUpperCase(Locale.ROOT);
		if ("VARCHAR".equals(upper) || "CHAR".equals(upper) || "VARBINARY".equals(upper) || "BINARY".equals(upper)) {
			return typeName + "(" + size + ")";
		}
		if ("DECIMAL".equals(upper) || "NUMERIC".equals(upper)) {
			return typeName + "(" + size + ", " + digits + ")";
		}
		return typeName;
	}
	
	private List<String> getColumnNames(DatabaseMetaData md, String catalog, String table) throws SQLException {
		List<String> columns = new ArrayList<>();
		try (ResultSet rs = md.getColumns(catalog, null, escapeMetadataPattern(md, table), "%")) {
			while (rs.next()) {
				columns.add(rs.getString("COLUMN_NAME"));
			}
		}
		return columns;
	}
	
	/**
	 * JDBC metadata methods treat table names as LIKE patterns, so the underscores in OpenMRS table
	 * names must be escaped or person_name also matches personXname.
	 */
	private String escapeMetadataPattern(DatabaseMetaData md, String name) throws SQLException {
		String escape = md.getSearchStringEscape();
		if (escape == null || escape.isEmpty()) {
			return name;
		}
		return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
	}
	
	private List<String> getPrimaryKeyColumns(DatabaseMetaData md, String catalog, String table) throws SQLException {
		TreeMap<Short, String> ordered = new TreeMap<>();
		try (ResultSet rs = md.getPrimaryKeys(catalog, null, table)) {
			while (rs.next()) {
				ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
			}
		}
		return new ArrayList<>(ordered.values());
	}
	
	private Set<String> toLowerCaseSet(List<String> values) {
		Set<String> set = new HashSet<>();
		for (String value : values) {
			set.add(value.toLowerCase(Locale.ROOT));
		}
		return set;
	}
	
	private String unqualifiedTableName(String tableName) {
		String name = tableName.replace("`", "").replace("\"", "");
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1) : name;
	}
	
	String quoteIdentifier(String identifier, String quote) {
		if (quote.isEmpty()) {
			return identifier;
		}
		return quote + identifier.replace(quote, quote + quote) + quote;
	}
	
	private void safeRollback(Transaction tx) {
		try {
			if (tx != null && tx.isActive()) {
				tx.rollback();
			}
		}
		catch (RuntimeException e) {
			log.warn("Rollback failed during audit backfill", e);
		}
	}
	
	private Set<String> readExistingTableNames(DatabaseMetaData metaData, String catalog) throws SQLException {
		Set<String> tables = new HashSet<>();
		
		try (ResultSet resultSet = metaData.getTables(catalog, null, "%", new String[] { "TABLE" })) {
			while (resultSet.next()) {
				tables.add(resultSet.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
			}
		}
		return tables;
	}
	
	private String identifierQuote(DatabaseMetaData metaData) throws SQLException {
		String quote = metaData.getIdentifierQuoteString();
		return StringUtils.isNotBlank(quote) ? quote : "";
	}
	
	/** Resolved base/audit table pair for one audited entity. */
	public static final class TableMapping {
		
		private final String baseTable;
		
		private final String auditTable;
		
		public TableMapping(String baseTable, String auditTable) {
			this.baseTable = baseTable;
			this.auditTable = auditTable;
		}
		
		public String getBaseTable() {
			return baseTable;
		}
		
		public String getAuditTable() {
			return auditTable;
		}
	}
	
	/** Outcome of {@link #createMissingAuditTables()}: tables created and tables still missing. */
	@RequiredArgsConstructor
	@Getter
	public static final class SchemaCreationResult {
		
		private final int created;
		
		private final List<String> missingTables;
	}
	
	/**
	 * Outcome of {@link #addMissingAuditColumns()}: columns added and audit tables whose sync failed.
	 */
	@RequiredArgsConstructor
	@Getter
	public static final class ColumnSyncResult {
		
		private final int columnsAdded;
		
		private final List<String> failedTables;
	}
	
	/** One column of a CREATE TABLE statement: name, complete type DDL, and nullability. */
	@RequiredArgsConstructor
	static final class ColumnDefinition {
		
		final String name;
		
		final String typeDdl;
		
		final boolean nullable;
	}
	
}
