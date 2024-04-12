/*
 * Copyright 2004, 2005, 2006, 2017 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.acls.jdbc;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.acls.domain.AccessControlEntryImpl;
import org.springframework.security.acls.domain.AclAuthorizationStrategy;
import org.springframework.security.acls.domain.AclImpl;
import org.springframework.security.acls.domain.AuditLogger;
import org.springframework.security.acls.domain.DefaultPermissionFactory;
import org.springframework.security.acls.domain.DefaultPermissionGrantingStrategy;
import org.springframework.security.acls.domain.GrantedAuthoritySid;
import org.springframework.security.acls.domain.ObjectIdentityRetrievalStrategyImpl;
import org.springframework.security.acls.domain.PermissionFactory;
import org.springframework.security.acls.domain.PrincipalSid;
import org.springframework.security.acls.model.AccessControlEntry;
import org.springframework.security.acls.model.Acl;
import org.springframework.security.acls.model.AclCache;
import org.springframework.security.acls.model.MutableAcl;
import org.springframework.security.acls.model.NotFoundException;
import org.springframework.security.acls.model.ObjectIdentity;
import org.springframework.security.acls.model.ObjectIdentityGenerator;
import org.springframework.security.acls.model.Permission;
import org.springframework.security.acls.model.PermissionGrantingStrategy;
import org.springframework.security.acls.model.Sid;
import org.springframework.security.acls.model.UnloadedSidException;
import org.springframework.security.util.FieldUtils;
import org.springframework.util.Assert;

/**
 * Performs lookups in a manner that is compatible with ANSI SQL.
 * <p>
 * NB: This implementation does attempt to provide reasonably optimised lookups - within
 * the constraints of a normalised database and standard ANSI SQL features. If you are
 * willing to sacrifice either of these constraints (e.g. use a particular database
 * feature such as hierarchical queries or materalized views, or reduce normalisation) you
 * are likely to achieve better performance. In such situations you will need to provide
 * your own custom <code>LookupStrategy</code>. This class does not support subclassing,
 * as it is likely to change in future releases and therefore subclassing is unsupported.
 * <p>
 * There are two SQL queries executed, one in the <tt>lookupPrimaryKeys</tt> method and
 * one in <tt>lookupObjectIdentities</tt>. These are built from the same select and "order
 * by" clause, using a different where clause in each case. In order to use custom schema
 * or column names, each of these SQL clauses can be customized, but they must be
 * consistent with each other and with the expected result set generated by the default
 * values.
 *
 * @author Ben Alex
 */
public class BasicLookupStrategy implements LookupStrategy {

	private static final String DEFAULT_SELECT_CLAUSE_COLUMNS = "select acl_object_identity.object_id_identity, "
			+ "acl_entry.ace_order,  " + "acl_object_identity.id as acl_id, " + "acl_object_identity.parent_object, "
			+ "acl_object_identity.entries_inheriting, " + "acl_entry.id as ace_id, " + "acl_entry.mask,  "
			+ "acl_entry.granting,  " + "acl_entry.audit_success, " + "acl_entry.audit_failure,  "
			+ "acl_sid.principal as ace_principal, " + "acl_sid.sid as ace_sid,  "
			+ "acli_sid.principal as acl_principal, " + "acli_sid.sid as acl_sid, " + "acl_class.class ";

	private static final String DEFAULT_SELECT_CLAUSE_ACL_CLASS_ID_TYPE_COLUMN = ", acl_class.class_id_type  ";

	private static final String DEFAULT_SELECT_CLAUSE_FROM = "from acl_object_identity "
			+ "left join acl_sid acli_sid on acli_sid.id = acl_object_identity.owner_sid "
			+ "left join acl_class on acl_class.id = acl_object_identity.object_id_class   "
			+ "left join acl_entry on acl_object_identity.id = acl_entry.acl_object_identity "
			+ "left join acl_sid on acl_entry.sid = acl_sid.id  " + "where ( ";

	public static final String DEFAULT_SELECT_CLAUSE = DEFAULT_SELECT_CLAUSE_COLUMNS + DEFAULT_SELECT_CLAUSE_FROM;

	public static final String DEFAULT_ACL_CLASS_ID_SELECT_CLAUSE = DEFAULT_SELECT_CLAUSE_COLUMNS
			+ DEFAULT_SELECT_CLAUSE_ACL_CLASS_ID_TYPE_COLUMN + DEFAULT_SELECT_CLAUSE_FROM;

	private static final String DEFAULT_LOOKUP_KEYS_WHERE_CLAUSE = "(acl_object_identity.id = ?)";

	private static final String DEFAULT_LOOKUP_IDENTITIES_WHERE_CLAUSE = "(acl_object_identity.object_id_identity = ? and acl_class.class = ?)";

	public static final String DEFAULT_ORDER_BY_CLAUSE = ") order by acl_object_identity.object_id_identity"
			+ " asc, acl_entry.ace_order asc";

	private final AclAuthorizationStrategy aclAuthorizationStrategy;

	private ObjectIdentityGenerator objectIdentityGenerator;

	private PermissionFactory permissionFactory = new DefaultPermissionFactory();

	private final AclCache aclCache;

	private final PermissionGrantingStrategy grantingStrategy;

	private final JdbcTemplate jdbcTemplate;

	private int batchSize = 50;

	private final Field fieldAces = FieldUtils.getField(AclImpl.class, "aces");

	private final Field fieldAcl = FieldUtils.getField(AccessControlEntryImpl.class, "acl");

	// SQL Customization fields
	private String selectClause = DEFAULT_SELECT_CLAUSE;

	private String lookupPrimaryKeysWhereClause = DEFAULT_LOOKUP_KEYS_WHERE_CLAUSE;

	private String lookupObjectIdentitiesWhereClause = DEFAULT_LOOKUP_IDENTITIES_WHERE_CLAUSE;

	private String orderByClause = DEFAULT_ORDER_BY_CLAUSE;

	private AclClassIdUtils aclClassIdUtils;

	/**
	 * Constructor accepting mandatory arguments
	 * @param dataSource to access the database
	 * @param aclCache the cache where fully-loaded elements can be stored
	 * @param aclAuthorizationStrategy authorization strategy (required)
	 */
	public BasicLookupStrategy(DataSource dataSource, AclCache aclCache,
			AclAuthorizationStrategy aclAuthorizationStrategy, AuditLogger auditLogger) {
		this(dataSource, aclCache, aclAuthorizationStrategy, new DefaultPermissionGrantingStrategy(auditLogger));
	}

	/**
	 * Creates a new instance
	 * @param dataSource to access the database
	 * @param aclCache the cache where fully-loaded elements can be stored
	 * @param aclAuthorizationStrategy authorization strategy (required)
	 * @param grantingStrategy the PermissionGrantingStrategy
	 */
	public BasicLookupStrategy(DataSource dataSource, AclCache aclCache,
			AclAuthorizationStrategy aclAuthorizationStrategy, PermissionGrantingStrategy grantingStrategy) {
		Assert.notNull(dataSource, "DataSource required");
		Assert.notNull(aclCache, "AclCache required");
		Assert.notNull(aclAuthorizationStrategy, "AclAuthorizationStrategy required");
		Assert.notNull(grantingStrategy, "grantingStrategy required");
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.aclCache = aclCache;
		this.aclAuthorizationStrategy = aclAuthorizationStrategy;
		this.grantingStrategy = grantingStrategy;
		this.objectIdentityGenerator = new ObjectIdentityRetrievalStrategyImpl();
		this.aclClassIdUtils = new AclClassIdUtils();
		this.fieldAces.setAccessible(true);
		this.fieldAcl.setAccessible(true);
	}

	private String computeRepeatingSql(String repeatingSql, int requiredRepetitions) {
		Assert.isTrue(requiredRepetitions > 0, "requiredRepetitions must be > 0");
		String startSql = this.selectClause;
		String endSql = this.orderByClause;
		StringBuilder sqlStringBldr = new StringBuilder(
				startSql.length() + endSql.length() + requiredRepetitions * (repeatingSql.length() + 4));
		sqlStringBldr.append(startSql);
		for (int i = 1; i <= requiredRepetitions; i++) {
			sqlStringBldr.append(repeatingSql);
			if (i != requiredRepetitions) {
				sqlStringBldr.append(" or ");
			}
		}
		sqlStringBldr.append(endSql);
		return sqlStringBldr.toString();
	}

	@SuppressWarnings("unchecked")
	private List<AccessControlEntryImpl> readAces(AclImpl acl) {
		try {
			return (List<AccessControlEntryImpl>) this.fieldAces.get(acl);
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("Could not obtain AclImpl.aces field", ex);
		}
	}

	private void setAclOnAce(AccessControlEntryImpl ace, AclImpl acl) {
		try {
			this.fieldAcl.set(ace, acl);
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("Could not or set AclImpl on AccessControlEntryImpl fields", ex);
		}
	}

	private void setAces(AclImpl acl, List<AccessControlEntryImpl> aces) {
		try {
			this.fieldAces.set(acl, aces);
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("Could not set AclImpl entries", ex);
		}
	}

	/**
	 * Locates the primary key IDs specified in "findNow", adding AclImpl instances with
	 * StubAclParents to the "acls" Map.
	 * @param acls the AclImpls (with StubAclParents)
	 * @param findNow Long-based primary keys to retrieve
	 * @param sids
	 */
	private void lookupPrimaryKeys(final Map<Serializable, Acl> acls, final Set<Long> findNow, final List<Sid> sids) {
		Assert.notNull(acls, "ACLs are required");
		Assert.notEmpty(findNow, "Items to find now required");
		String sql = computeRepeatingSql(this.lookupPrimaryKeysWhereClause, findNow.size());
		Set<Long> parentsToLookup = this.jdbcTemplate.query(sql, (ps) -> setKeys(ps, findNow),
				new ProcessResultSet(acls, sids));
		// Lookup the parents, now that our JdbcTemplate has released the database
		// connection (SEC-547)
		if (parentsToLookup.size() > 0) {
			lookupPrimaryKeys(acls, parentsToLookup, sids);
		}
	}

	private void setKeys(PreparedStatement ps, Set<Long> findNow) throws SQLException {
		int i = 0;
		for (Long toFind : findNow) {
			i++;
			ps.setLong(i, toFind);
		}
	}

	/**
	 * The main method.
	 * <p>
	 * WARNING: This implementation completely disregards the "sids" argument! Every item
	 * in the cache is expected to contain all SIDs. If you have serious performance needs
	 * (e.g. a very large number of SIDs per object identity), you'll probably want to
	 * develop a custom {@link LookupStrategy} implementation instead.
	 * <p>
	 * The implementation works in batch sizes specified by {@link #batchSize}.
	 * @param objects the identities to lookup (required)
	 * @param sids the SIDs for which identities are required (ignored by this
	 * implementation)
	 * @return a <tt>Map</tt> where keys represent the {@link ObjectIdentity} of the
	 * located {@link Acl} and values are the located {@link Acl} (never <tt>null</tt>
	 * although some entries may be missing; this method should not throw
	 * {@link NotFoundException}, as a chain of {@link LookupStrategy}s may be used to
	 * automatically create entries if required)
	 */
	@Override
	public final Map<ObjectIdentity, Acl> readAclsById(List<ObjectIdentity> objects, List<Sid> sids) {
		Assert.isTrue(this.batchSize >= 1, "BatchSize must be >= 1");
		Assert.notEmpty(objects, "Objects to lookup required");
		// Map<ObjectIdentity,Acl>
		// contains FULLY loaded Acl objects
		Map<ObjectIdentity, Acl> result = new HashMap<>();
		Set<ObjectIdentity> currentBatchToLoad = new HashSet<>();
		for (int i = 0; i < objects.size(); i++) {
			final ObjectIdentity oid = objects.get(i);
			boolean aclFound = false;
			// Check we don't already have this ACL in the results
			if (result.containsKey(oid)) {
				aclFound = true;
			}
			// Check cache for the present ACL entry
			if (!aclFound) {
				Acl acl = this.aclCache.getFromCache(oid);
				// Ensure any cached element supports all the requested SIDs
				// (they should always, as our base impl doesn't filter on SID)
				if (acl != null) {
					Assert.state(acl.isSidLoaded(sids),
							"Error: SID-filtered element detected when implementation does not perform SID filtering "
									+ "- have you added something to the cache manually?");
					result.put(acl.getObjectIdentity(), acl);
					aclFound = true;
				}
			}
			// Load the ACL from the database
			if (!aclFound) {
				currentBatchToLoad.add(oid);
			}
			// Is it time to load from JDBC the currentBatchToLoad?
			if ((currentBatchToLoad.size() == this.batchSize) || ((i + 1) == objects.size())) {
				if (currentBatchToLoad.size() > 0) {
					Map<ObjectIdentity, Acl> loadedBatch = lookupObjectIdentities(currentBatchToLoad, sids);
					// Add loaded batch (all elements 100% initialized) to results
					result.putAll(loadedBatch);
					// Add the loaded batch to the cache
					for (Acl loadedAcl : loadedBatch.values()) {
						this.aclCache.putInCache((AclImpl) loadedAcl);
					}
					currentBatchToLoad.clear();
				}
			}
		}
		return result;
	}

	/**
	 * Looks up a batch of <code>ObjectIdentity</code>s directly from the database.
	 * <p>
	 * The caller is responsible for optimization issues, such as selecting the identities
	 * to lookup, ensuring the cache doesn't contain them already, and adding the returned
	 * elements to the cache etc.
	 * <p>
	 * This subclass is required to return fully valid <code>Acl</code>s, including
	 * properly-configured parent ACLs.
	 */
	private Map<ObjectIdentity, Acl> lookupObjectIdentities(final Collection<ObjectIdentity> objectIdentities,
			List<Sid> sids) {
		Assert.notEmpty(objectIdentities, "Must provide identities to lookup");

		// contains Acls with StubAclParents
		Map<Serializable, Acl> acls = new HashMap<>();

		// Make the "acls" map contain all requested objectIdentities
		// (including markers to each parent in the hierarchy)
		String sql = computeRepeatingSql(this.lookupObjectIdentitiesWhereClause, objectIdentities.size());

		Set<Long> parentsToLookup = this.jdbcTemplate.query(sql,
				(ps) -> setupLookupObjectIdentitiesStatement(ps, objectIdentities), new ProcessResultSet(acls, sids));

		// Lookup the parents, now that our JdbcTemplate has released the database
		// connection (SEC-547)
		if (parentsToLookup.size() > 0) {
			lookupPrimaryKeys(acls, parentsToLookup, sids);
		}

		// Finally, convert our "acls" containing StubAclParents into true Acls
		Map<ObjectIdentity, Acl> resultMap = new HashMap<>();
		for (Acl inputAcl : acls.values()) {
			Assert.isInstanceOf(AclImpl.class, inputAcl, "Map should have contained an AclImpl");
			Assert.isInstanceOf(Long.class, ((AclImpl) inputAcl).getId(), "Acl.getId() must be Long");
			Acl result = convert(acls, (Long) ((AclImpl) inputAcl).getId());
			resultMap.put(result.getObjectIdentity(), result);
		}

		return resultMap;
	}

	private void setupLookupObjectIdentitiesStatement(PreparedStatement ps, Collection<ObjectIdentity> objectIdentities)
			throws SQLException {
		int i = 0;
		for (ObjectIdentity oid : objectIdentities) {
			// Determine prepared statement values for this iteration
			String type = oid.getType();

			// No need to check for nulls, as guaranteed non-null by
			// ObjectIdentity.getIdentifier() interface contract
			String identifier = oid.getIdentifier().toString();

			// Inject values
			ps.setString((2 * i) + 1, identifier);
			ps.setString((2 * i) + 2, type);
			i++;
		}
	}

	/**
	 * The final phase of converting the <code>Map</code> of <code>AclImpl</code>
	 * instances which contain <code>StubAclParent</code>s into proper, valid
	 * <code>AclImpl</code>s with correct ACL parents.
	 * @param inputMap the unconverted <code>AclImpl</code>s
	 * @param currentIdentity the current<code>Acl</code> that we wish to convert (this
	 * may be
	 */
	private AclImpl convert(Map<Serializable, Acl> inputMap, Long currentIdentity) {
		Assert.notEmpty(inputMap, "InputMap required");
		Assert.notNull(currentIdentity, "CurrentIdentity required");

		// Retrieve this Acl from the InputMap
		Acl uncastAcl = inputMap.get(currentIdentity);
		Assert.isInstanceOf(AclImpl.class, uncastAcl, "The inputMap contained a non-AclImpl");

		AclImpl inputAcl = (AclImpl) uncastAcl;

		Acl parent = inputAcl.getParentAcl();

		if ((parent != null) && parent instanceof StubAclParent) {
			// Lookup the parent
			StubAclParent stubAclParent = (StubAclParent) parent;
			parent = convert(inputMap, stubAclParent.getId());
		}

		// Now we have the parent (if there is one), create the true AclImpl
		AclImpl result = new AclImpl(inputAcl.getObjectIdentity(), inputAcl.getId(), this.aclAuthorizationStrategy,
				this.grantingStrategy, parent, null, inputAcl.isEntriesInheriting(), inputAcl.getOwner());

		// Copy the "aces" from the input to the destination

		// Obtain the "aces" from the input ACL
		List<AccessControlEntryImpl> aces = readAces(inputAcl);

		// Create a list in which to store the "aces" for the "result" AclImpl instance
		List<AccessControlEntryImpl> acesNew = new ArrayList<>();

		// Iterate over the "aces" input and replace each nested
		// AccessControlEntryImpl.getAcl() with the new "result" AclImpl instance
		// This ensures StubAclParent instances are removed, as per SEC-951
		for (AccessControlEntryImpl ace : aces) {
			setAclOnAce(ace, result);
			acesNew.add(ace);
		}

		// Finally, now that the "aces" have been converted to have the "result" AclImpl
		// instance, modify the "result" AclImpl instance
		setAces(result, acesNew);

		return result;
	}

	/**
	 * Creates a particular implementation of {@link Sid} depending on the arguments.
	 * @param sid the name of the sid representing its unique identifier. In typical ACL
	 * database schema it's located in table {@code acl_sid} table, {@code sid} column.
	 * @param isPrincipal whether it's a user or granted authority like role
	 * @return the instance of Sid with the {@code sidName} as an identifier
	 */
	protected Sid createSid(boolean isPrincipal, String sid) {
		if (isPrincipal) {
			return new PrincipalSid(sid);
		}
		return new GrantedAuthoritySid(sid);
	}

	/**
	 * Sets the {@code PermissionFactory} instance which will be used to convert loaded
	 * permission data values to {@code Permission}s. A {@code DefaultPermissionFactory}
	 * will be used by default.
	 * @param permissionFactory
	 */
	public final void setPermissionFactory(PermissionFactory permissionFactory) {
		this.permissionFactory = permissionFactory;
	}

	public final void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	/**
	 * The SQL for the select clause. If customizing in order to modify column names,
	 * schema etc, the other SQL customization fields must also be set to match.
	 * @param selectClause the select clause, which defaults to
	 * {@link #DEFAULT_SELECT_CLAUSE}.
	 */
	public final void setSelectClause(String selectClause) {
		this.selectClause = selectClause;
	}

	/**
	 * The SQL for the where clause used in the <tt>lookupPrimaryKey</tt> method.
	 */
	public final void setLookupPrimaryKeysWhereClause(String lookupPrimaryKeysWhereClause) {
		this.lookupPrimaryKeysWhereClause = lookupPrimaryKeysWhereClause;
	}

	/**
	 * The SQL for the where clause used in the <tt>lookupObjectIdentities</tt> method.
	 */
	public final void setLookupObjectIdentitiesWhereClause(String lookupObjectIdentitiesWhereClause) {
		this.lookupObjectIdentitiesWhereClause = lookupObjectIdentitiesWhereClause;
	}

	/**
	 * The SQL for the "order by" clause used in both queries.
	 */
	public final void setOrderByClause(String orderByClause) {
		this.orderByClause = orderByClause;
	}

	public final void setAclClassIdSupported(boolean aclClassIdSupported) {
		if (aclClassIdSupported) {
			Assert.isTrue(DEFAULT_SELECT_CLAUSE.equals(this.selectClause),
					"Cannot set aclClassIdSupported and override the select clause; "
							+ "just override the select clause");
			this.selectClause = DEFAULT_ACL_CLASS_ID_SELECT_CLAUSE;
		}
	}

	public final void setObjectIdentityGenerator(ObjectIdentityGenerator objectIdentityGenerator) {
		Assert.notNull(objectIdentityGenerator, "objectIdentityGenerator cannot be null");
		this.objectIdentityGenerator = objectIdentityGenerator;
	}

	public final void setConversionService(ConversionService conversionService) {
		this.aclClassIdUtils = new AclClassIdUtils(conversionService);
	}

	private class ProcessResultSet implements ResultSetExtractor<Set<Long>> {

		private final Map<Serializable, Acl> acls;

		private final List<Sid> sids;

		ProcessResultSet(Map<Serializable, Acl> acls, List<Sid> sids) {
			Assert.notNull(acls, "ACLs cannot be null");
			this.acls = acls;
			this.sids = sids; // can be null
		}

		/**
		 * Implementation of {@link ResultSetExtractor#extractData(ResultSet)}. Creates an
		 * {@link Acl} for each row in the {@link ResultSet} and ensures it is in member
		 * field <tt>acls</tt>. Any {@link Acl} with a parent will have the parents id
		 * returned in a set. The returned set of ids may requires further processing.
		 * @param rs The {@link ResultSet} to be processed
		 * @return a list of parent IDs remaining to be looked up (may be empty, but never
		 * <tt>null</tt>)
		 * @throws SQLException
		 */
		@Override
		public Set<Long> extractData(ResultSet rs) throws SQLException {
			Set<Long> parentIdsToLookup = new HashSet<>(); // Set of parent_id Longs

			while (rs.next()) {
				// Convert current row into an Acl (albeit with a StubAclParent)
				convertCurrentResultIntoObject(this.acls, rs);

				// Figure out if this row means we need to lookup another parent
				long parentId = rs.getLong("parent_object");

				if (parentId != 0) {
					// See if it's already in the "acls"
					if (this.acls.containsKey(parentId)) {
						continue; // skip this while iteration
					}

					// Now try to find it in the cache
					MutableAcl cached = BasicLookupStrategy.this.aclCache.getFromCache(parentId);
					if ((cached == null) || !cached.isSidLoaded(this.sids)) {
						parentIdsToLookup.add(parentId);
					}
					else {
						// Pop into the acls map, so our convert method doesn't
						// need to deal with an unsynchronized AclCache
						this.acls.put(cached.getId(), cached);
					}
				}
			}

			// Return the parents left to lookup to the caller
			return parentIdsToLookup;
		}

		/**
		 * Accepts the current <code>ResultSet</code> row, and converts it into an
		 * <code>AclImpl</code> that contains a <code>StubAclParent</code>
		 * @param acls the Map we should add the converted Acl to
		 * @param rs the ResultSet focused on a current row
		 * @throws SQLException if something goes wrong converting values
		 * @throws ConversionException if can't convert to the desired Java type
		 */
		private void convertCurrentResultIntoObject(Map<Serializable, Acl> acls, ResultSet rs) throws SQLException {
			Long id = rs.getLong("acl_id");

			// If we already have an ACL for this ID, just create the ACE
			Acl acl = acls.get(id);

			if (acl == null) {
				// Make an AclImpl and pop it into the Map

				// If the Java type is a String, check to see if we can convert it to the
				// target id type, e.g. UUID.
				Serializable identifier = (Serializable) rs.getObject("object_id_identity");
				identifier = BasicLookupStrategy.this.aclClassIdUtils.identifierFrom(identifier, rs);
				ObjectIdentity objectIdentity = BasicLookupStrategy.this.objectIdentityGenerator
					.createObjectIdentity(identifier, rs.getString("class"));

				Acl parentAcl = null;
				long parentAclId = rs.getLong("parent_object");

				if (parentAclId != 0) {
					parentAcl = new StubAclParent(parentAclId);
				}

				boolean entriesInheriting = rs.getBoolean("entries_inheriting");
				Sid owner = createSid(rs.getBoolean("acl_principal"), rs.getString("acl_sid"));

				acl = new AclImpl(objectIdentity, id, BasicLookupStrategy.this.aclAuthorizationStrategy,
						BasicLookupStrategy.this.grantingStrategy, parentAcl, null, entriesInheriting, owner);

				acls.put(id, acl);
			}

			// Add an extra ACE to the ACL (ORDER BY maintains the ACE list order)
			// It is permissible to have no ACEs in an ACL (which is detected by a null
			// ACE_SID)
			if (rs.getString("ace_sid") != null) {
				Long aceId = rs.getLong("ace_id");
				Sid recipient = createSid(rs.getBoolean("ace_principal"), rs.getString("ace_sid"));

				int mask = rs.getInt("mask");
				Permission permission = BasicLookupStrategy.this.permissionFactory.buildFromMask(mask);
				boolean granting = rs.getBoolean("granting");
				boolean auditSuccess = rs.getBoolean("audit_success");
				boolean auditFailure = rs.getBoolean("audit_failure");

				AccessControlEntryImpl ace = new AccessControlEntryImpl(aceId, acl, recipient, permission, granting,
						auditSuccess, auditFailure);

				// Field acesField = FieldUtils.getField(AclImpl.class, "aces");
				List<AccessControlEntryImpl> aces = readAces((AclImpl) acl);

				// Add the ACE if it doesn't already exist in the ACL.aces field
				if (!aces.contains(ace)) {
					aces.add(ace);
				}
			}
		}

	}

	private static class StubAclParent implements Acl {

		private final Long id;

		StubAclParent(Long id) {
			this.id = id;
		}

		Long getId() {
			return this.id;
		}

		@Override
		public List<AccessControlEntry> getEntries() {
			throw new UnsupportedOperationException("Stub only");
		}

		@Override
		public ObjectIdentity getObjectIdentity() {
			throw new UnsupportedOperationException("Stub only");
		}

		@Override
		public Sid getOwner() {
			throw new UnsupportedOperationException("Stub only");
		}

		@Override
		public Acl getParentAcl() {
			throw new UnsupportedOperationException("Stub only");
		}

		@Override
		public boolean isEntriesInheriting() {
			throw new UnsupportedOperationException("Stub only");
		}

		@Override
		public boolean isGranted(List<Permission> permission, List<Sid> sids, boolean administrativeMode)
				throws NotFoundException, UnloadedSidException {
			throw new UnsupportedOperationException("Stub only");
		}

		@Override
		public boolean isSidLoaded(List<Sid> sids) {
			throw new UnsupportedOperationException("Stub only");
		}

	}

}
