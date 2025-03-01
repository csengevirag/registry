/*
 * Copyright 2016 Hortonworks.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hortonworks.registries.schemaregistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.hortonworks.registries.common.QueryParam;
import com.hortonworks.registries.schemaregistry.cache.SchemaBranchCache;
import com.hortonworks.registries.schemaregistry.cache.SchemaVersionInfoCache;
import com.hortonworks.registries.schemaregistry.errors.IncompatibleSchemaException;
import com.hortonworks.registries.schemaregistry.errors.InvalidSchemaBranchVersionMapping;
import com.hortonworks.registries.schemaregistry.errors.InvalidSchemaException;
import com.hortonworks.registries.schemaregistry.errors.SchemaBranchNotFoundException;
import com.hortonworks.registries.schemaregistry.errors.SchemaNotFoundException;
import com.hortonworks.registries.schemaregistry.errors.SchemaVersionMergeException;
import com.hortonworks.registries.schemaregistry.errors.UnsupportedSchemaTypeException;
import com.hortonworks.registries.schemaregistry.state.CustomSchemaStateExecutor;
import com.hortonworks.registries.schemaregistry.state.InbuiltSchemaVersionLifecycleState;
import com.hortonworks.registries.schemaregistry.state.SchemaLifecycleException;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleContext;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleState;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleStateAction;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleStateMachine;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleStateTransition;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleStateTransitionListener;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionLifecycleStates;
import com.hortonworks.registries.schemaregistry.state.SchemaVersionService;
import com.hortonworks.registries.schemaregistry.state.details.InitializedStateDetails;
import com.hortonworks.registries.schemaregistry.utils.ObjectMapperUtils;
import com.hortonworks.registries.storage.OrderByField;
import com.hortonworks.registries.storage.Storable;
import com.hortonworks.registries.storage.StorableKey;
import com.hortonworks.registries.storage.StorageManager;
import com.hortonworks.registries.storage.search.OrderBy;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 */
public class SchemaVersionLifecycleManager {
    private static final Logger LOG = LoggerFactory.getLogger(SchemaVersionLifecycleManager.class);

    private static final String DEFAULT_SCHEMA_REVIEW_EXECUTOR_CLASS = "com.hortonworks.registries.schemaregistry.state.DefaultCustomSchemaStateExecutor";
    public static final InbuiltSchemaVersionLifecycleState DEFAULT_VERSION_STATE = SchemaVersionLifecycleStates.INITIATED;
    private static final List<SchemaVersionLifecycleStateTransitionListener> DEFAULT_LISTENERS = new ArrayList<>();

    private final SchemaVersionLifecycleStateMachine schemaVersionLifecycleStateMachine;
    private CustomSchemaStateExecutor customSchemaStateExecutor;
    private SchemaVersionInfoCache schemaVersionInfoCache;
    private SchemaVersionRetriever schemaVersionRetriever;
    private StorageManager storageManager;
    private SchemaBranchCache schemaBranchCache;
    private HAServerNotificationManager haServerNotificationManager;
    private DefaultSchemaRegistry.SchemaMetadataFetcher schemaMetadataFetcher;

    public SchemaVersionLifecycleManager(StorageManager storageManager,
                                         Map<String, Object> props,
                                         DefaultSchemaRegistry.SchemaMetadataFetcher schemaMetadataFetcher,
                                         SchemaBranchCache schemaBranchCache,
                                         HAServerNotificationManager haServerNotificationManager) {
        this.storageManager = storageManager;
        this.schemaMetadataFetcher = schemaMetadataFetcher;
        this.schemaBranchCache = schemaBranchCache;
        this.haServerNotificationManager = haServerNotificationManager;
        SchemaVersionLifecycleStateMachine.Builder builder = SchemaVersionLifecycleStateMachine.newBuilder();

        DefaultSchemaRegistry.Options options = new DefaultSchemaRegistry.Options(props);
        schemaVersionRetriever = createSchemaVersionRetriever();

        schemaVersionInfoCache = new SchemaVersionInfoCache(
                schemaVersionRetriever,
                options.getMaxSchemaCacheSize(),
                options.getSchemaExpiryInSecs() * 1000L);

        customSchemaStateExecutor = createSchemaReviewExecutor(props, builder);

        schemaVersionLifecycleStateMachine = builder.build();
    }

    private CustomSchemaStateExecutor createSchemaReviewExecutor(Map<String, Object> props,
                                                                 SchemaVersionLifecycleStateMachine.Builder builder) {
        Map<String, Object> schemaReviewExecConfig = (Map<String, Object>) props.getOrDefault("customSchemaStateExecutor",
                                                                                              Collections.emptyMap());
        String className = (String) schemaReviewExecConfig.getOrDefault("className", DEFAULT_SCHEMA_REVIEW_EXECUTOR_CLASS);
        Map<String, ?> executorProps = (Map<String, ?>) schemaReviewExecConfig.getOrDefault("props", Collections.emptyMap());
        CustomSchemaStateExecutor customSchemaStateExecutor;
        try {
            customSchemaStateExecutor = (CustomSchemaStateExecutor) Class.forName(className,
                                                                                  true,
                                                                                  Thread.currentThread()
                                                                                        .getContextClassLoader())
                                                                         .newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            LOG.error("Error encountered while loading class [{}]", className, e);
            throw new IllegalArgumentException(e);
        }

        customSchemaStateExecutor.init(builder,
                                       SchemaVersionLifecycleStates.REVIEWED.getId(),
                                       SchemaVersionLifecycleStates.CHANGES_REQUIRED.getId(),
                                       executorProps);

        return customSchemaStateExecutor;
    }

    public SchemaVersionLifecycleStateMachine getSchemaVersionLifecycleStateMachine() {
        return schemaVersionLifecycleStateMachine;
    }

    public SchemaVersionRetriever getSchemaVersionRetriever() {
        return schemaVersionRetriever;
    }

    public SchemaIdVersion addSchemaVersion(String schemaBranchName,
                                            SchemaMetadata schemaMetadata,
                                            SchemaVersion schemaVersion,
                                            Function<SchemaMetadata, Long> registerSchemaMetadataFn,
                                            boolean disableCanonicalCheck)
            throws IncompatibleSchemaException, InvalidSchemaException, SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");
        Preconditions.checkNotNull(schemaMetadata, "schemaMetadata can't be null");
        Preconditions.checkNotNull(schemaVersion, "schemaVersion can't be null");

        checkSchemaText(schemaVersion.getSchemaText());

        SchemaVersionInfo schemaVersionInfo;
        String schemaName = schemaMetadata.getName();
        // check whether there exists schema-metadata for schema-metadata-key
        SchemaMetadataInfo retrievedschemaMetadataInfo = getSchemaMetadataInfo(schemaName);
        Long schemaMetadataId;
        if (retrievedschemaMetadataInfo != null) {
            schemaMetadataId = retrievedschemaMetadataInfo.getId();
            // check whether the same schema text exists
            schemaVersionInfo = getSchemaVersionInfo(schemaName, schemaVersion.getSchemaText(), disableCanonicalCheck);
            if (schemaVersionInfo == null) {
                schemaVersionInfo = createSchemaVersion(schemaBranchName,
                                                        schemaMetadata,
                                                        retrievedschemaMetadataInfo.getId(),
                                                        schemaVersion);

            }
        } else {
            schemaMetadataId = registerSchemaMetadataFn.apply(schemaMetadata);
            schemaVersionInfo = createSchemaVersion(schemaBranchName,
                                                    schemaMetadata,
                                                    schemaMetadataId,
                                                    schemaVersion);
        }

        return new SchemaIdVersion(schemaMetadataId, schemaVersionInfo.getVersion(), schemaVersionInfo.getId());
    }

    private void checkSchemaText(String schemaText) throws InvalidSchemaException {
        if(schemaText == null || schemaText.trim().isEmpty()) {
            throw new InvalidSchemaException();
        }
    }

    public SchemaIdVersion addSchemaVersion(String schemaBranchName,
                                            String schemaName,
                                            SchemaVersion schemaVersion,
                                            boolean disableCanonicalCheck)
            throws SchemaNotFoundException, IncompatibleSchemaException, InvalidSchemaException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");
        Preconditions.checkNotNull(schemaName, "schemaName can't be null");
        Preconditions.checkNotNull(schemaVersion, "schemaVersion can't be null");

        checkSchemaText(schemaVersion.getSchemaText());

        // check whether there exists schema-metadata for schema-metadata-key
        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadataInfo(schemaName);
        if (schemaMetadataInfo != null) {
            return addSchemaVersion(schemaBranchName, schemaMetadataInfo, schemaVersion, disableCanonicalCheck);
        } else {
            throw new SchemaNotFoundException("SchemaMetadata not found with the schemaName: " + schemaName);
        }
    }

    public SchemaIdVersion addSchemaVersion(String schemaBranchName,
                                            SchemaMetadataInfo schemaMetadataInfo,
                                            SchemaVersion schemaVersion,
                                            boolean disableCanonicalCheck)
            throws SchemaNotFoundException, IncompatibleSchemaException, InvalidSchemaException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");
        checkSchemaText(schemaVersion.getSchemaText());

        SchemaVersionInfo schemaVersionInfo;
        // check whether there exists schema-metadata for schema-metadata-key
        SchemaMetadata schemaMetadata = schemaMetadataInfo.getSchemaMetadata();
        // check whether the same schema text exists
        schemaVersionInfo = findSchemaVersion(schemaBranchName, schemaMetadata.getType(), schemaVersion.getSchemaText(), schemaMetadataInfo
                .getSchemaMetadata().getName(), disableCanonicalCheck);
        if (schemaVersionInfo == null) {
            schemaVersionInfo = createSchemaVersion(schemaBranchName,
                                                    schemaMetadata,
                                                    schemaMetadataInfo.getId(),
                                                    schemaVersion);
        }

        return new SchemaIdVersion(schemaMetadataInfo.getId(), schemaVersionInfo.getVersion(), schemaVersionInfo.getId());
    }

    public SchemaVersionInfo getLatestEnabledSchemaVersionInfo(String schemaBranchName,
                                                               String schemaName) throws SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");
        Preconditions.checkNotNull(schemaName, "schemaName can't be null");

        return getLatestSchemaVersionInfo(schemaBranchName, schemaName, SchemaVersionLifecycleStates.ENABLED.getId());
    }

    public SchemaVersionInfo getLatestSchemaVersionInfo(String schemaBranchName,
                                                        String schemaName) throws SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");
        Preconditions.checkNotNull(schemaName, "schemaName can't be null");

        return getLatestSchemaVersionInfo(schemaBranchName, schemaName, null);
    }

    public SchemaVersionInfo getLatestSchemaVersionInfo(String schemaBranchName,
                                                        String schemaName,
                                                        Byte stateId) throws SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");
        Preconditions.checkNotNull(schemaName, "schemaName can't be null");

        Collection<SchemaVersionInfo> schemaVersionInfos = getAllVersions(schemaBranchName, schemaName);

        SchemaVersionInfo latestSchema = null;
        if (schemaVersionInfos != null && !schemaVersionInfos.isEmpty()) {
            for (SchemaVersionInfo schemaVersionInfo : schemaVersionInfos) {
                if (stateId == null || schemaVersionInfo.getStateId().equals(stateId)) {
                    latestSchema = schemaVersionInfo;
                    break;
                }
            }
        }

        return latestSchema;
    }

    public SchemaVersionInfo getLatestSchemaVersionInfo(String schemaName) throws SchemaNotFoundException {
        Preconditions.checkNotNull(schemaName, "schemaName can't be null");

        return getLatestSchemaVersionInfo(schemaName,(Byte) null);
    }

    public SchemaVersionInfo getLatestSchemaVersionInfo(String schemaName, Byte stateId) throws SchemaNotFoundException {
        Preconditions.checkNotNull(schemaName, "schemaName can't be null");

        Collection<SchemaVersionInfo> schemaVersionInfos = getAllVersions(schemaName);

        SchemaVersionInfo latestSchema = null;
        if (schemaVersionInfos != null && !schemaVersionInfos.isEmpty()) {
            for (SchemaVersionInfo schemaVersionInfo : schemaVersionInfos) {
                if (stateId == null || schemaVersionInfo.getStateId().equals(stateId)) {
                    latestSchema = schemaVersionInfo;
                    break;
                }
            }
        }

        return latestSchema;
    }

    private SchemaVersionInfo createSchemaVersion(String schemaBranchName,
                                                  SchemaMetadata schemaMetadata,
                                                  Long schemaMetadataId,
                                                  SchemaVersion schemaVersion)
            throws IncompatibleSchemaException, InvalidSchemaException, SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "schemaBranchName must not be null");
        Preconditions.checkNotNull(schemaMetadataId, "schemaMetadataId must not be null");

        String type = schemaMetadata.getType();
        if (getSchemaProvider(type) == null) {
            throw new UnsupportedSchemaTypeException("Given schema type " + type + " not supported");
        }

        SchemaBranch schemaBranch = null;
        try {
            schemaBranch = schemaBranchCache.get(SchemaBranchCache.Key.of(new SchemaBranchKey(schemaBranchName, schemaMetadata
                    .getName())));
        } catch (SchemaBranchNotFoundException e) {
            // Ignore this error
        }

        if (schemaBranch == null) {
            if (getAllVersions(schemaBranchName, schemaMetadata.getName()).size() != 0)
                throw new RuntimeException(String.format("Schema name : '%s' and branch name : '%s' has schema version, yet failed to obtain schema branch instance", schemaMetadata
                        .getName(), schemaBranchName));

        }

        // generate fingerprint, it parses the schema and checks for semantic validation.
        // throws InvalidSchemaException for invalid schemas.
        final String fingerprint = getFingerprint(type, schemaVersion.getSchemaText());
        final String schemaName = schemaMetadata.getName();

        SchemaVersionStorable schemaVersionStorable = new SchemaVersionStorable();
        final Long schemaVersionStorableId = storageManager.nextId(schemaVersionStorable.getNameSpace());
        schemaVersionStorable.setId(schemaVersionStorableId);
        schemaVersionStorable.setSchemaMetadataId(schemaMetadataId);

        schemaVersionStorable.setFingerprint(fingerprint);

        schemaVersionStorable.setName(schemaName);

        schemaVersionStorable.setSchemaText(schemaVersion.getSchemaText());
        schemaVersionStorable.setDescription(schemaVersion.getDescription());
        schemaVersionStorable.setTimestamp(System.currentTimeMillis());

        schemaVersionStorable.setState(DEFAULT_VERSION_STATE.getId());

        if (!schemaBranchName.equals(SchemaBranch.MASTER_BRANCH)) {
            schemaVersion.setState(SchemaVersionLifecycleStates.INITIATED.getId());
            schemaVersion.setStateDetails(null);
        }

        Integer version = 0;
        Byte initialState = schemaVersion.getInitialState();
        if (schemaMetadata.isEvolve()) {
            // if the given version is added with enabled or initiated state then only check for compatibility
            if (SchemaVersionLifecycleStates.ENABLED.getId().equals(initialState) ||
                    SchemaVersionLifecycleStates.INITIATED.getId().equals(initialState)) {
                CompatibilityResult compatibilityResult = checkCompatibility(schemaBranchName, schemaName, schemaVersion
                        .getSchemaText());
                if (!compatibilityResult.isCompatible()) {
                    String errMsg = String.format("Given schema is not compatible with latest schema versions. \n" +
                                    "Error location: [%s] \n" +
                                    "Error encountered is: [%s]",
                            compatibilityResult.getErrorLocation(),
                            compatibilityResult.getErrorMessage());
                    LOG.error(errMsg);
                    throw new IncompatibleSchemaException(errMsg);
                }
            }
            SchemaVersionInfo latestSchemaVersionInfo = getLatestSchemaVersionInfo(schemaName);
            if (latestSchemaVersionInfo != null) {
                version = latestSchemaVersionInfo.getVersion();
            }
        }

        schemaVersionStorable.setVersion(version + 1);

        storageManager.add(schemaVersionStorable);
        updateSchemaVersionState(schemaVersionStorable.getId(), 1, initialState, schemaVersion.getStateDetails());

        // fetching this as the ID may have been set by storage manager.
        Long schemaInstanceId = schemaVersionStorable.getId();

        SchemaBranchVersionMapping schemaBranchVersionMapping = new SchemaBranchVersionMapping(schemaBranch.getId(), schemaInstanceId);
        storageManager.add(schemaBranchVersionMapping);

        String storableNamespace = new SchemaFieldInfoStorable().getNameSpace();
        List<SchemaFieldInfo> schemaFieldInfos = getSchemaProvider(type).generateFields(schemaVersionStorable.getSchemaText());
        for (SchemaFieldInfo schemaFieldInfo : schemaFieldInfos) {
            final Long fieldInstanceId = storageManager.nextId(storableNamespace);
            SchemaFieldInfoStorable schemaFieldInfoStorable = SchemaFieldInfoStorable.fromSchemaFieldInfo(schemaFieldInfo, fieldInstanceId);
            schemaFieldInfoStorable.setSchemaInstanceId(schemaInstanceId);
            schemaFieldInfoStorable.setTimestamp(System.currentTimeMillis());
            storageManager.add(schemaFieldInfoStorable);
        }

        return schemaVersionStorable.toSchemaVersionInfo();
    }

    private void updateSchemaVersionState(Long schemaVersionId,
                                          Integer sequence,
                                          Byte initialState,
                                          byte[] stateDetails) throws SchemaNotFoundException {
        try {
            SchemaVersionLifecycleContext schemaVersionLifecycleContext =
                    new SchemaVersionLifecycleContext(schemaVersionId,
                                                      sequence,
                                                      createSchemaVersionService(),
                                                      schemaVersionLifecycleStateMachine,
                                                      customSchemaStateExecutor);
            schemaVersionLifecycleContext.setState(schemaVersionLifecycleStateMachine.getStates().get(initialState));
            schemaVersionLifecycleContext.setDetails(stateDetails);
            schemaVersionLifecycleContext.updateSchemaVersionState();
        } catch (SchemaLifecycleException e) {
            throw new RuntimeException(e);
        }
    }

    private SchemaProvider getSchemaProvider(String type) {
        return schemaMetadataFetcher.getSchemaProvider(type);
    }

    public CompatibilityResult checkCompatibility(String schemaBranchName,
                                                  String schemaName,
                                                  String toSchema) throws SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");

        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadataInfo(schemaName);
        SchemaMetadata schemaMetadata = schemaMetadataInfo.getSchemaMetadata();
        SchemaValidationLevel validationLevel = schemaMetadata.getValidationLevel();
        CompatibilityResult compatibilityResult = null;
        switch (validationLevel) {
            case LATEST:
                SchemaVersionInfo latestSchemaVersionInfo = getLatestEnabledSchemaVersionInfo(schemaBranchName, schemaName);
                if (latestSchemaVersionInfo != null) {
                    compatibilityResult = checkCompatibility(schemaMetadata.getType(),
                                                             toSchema,
                                                             latestSchemaVersionInfo.getSchemaText(),
                                                             schemaMetadata.getCompatibility());
                    if (!compatibilityResult.isCompatible()) {
                        LOG.info("Received schema is not compatible with the latest schema versions [{}] with schema name [{}]",
                                 latestSchemaVersionInfo.getVersion(), schemaName);
                    }
                }
                break;
            case ALL:
                Collection<SchemaVersionInfo> schemaVersionInfos = getAllVersions(schemaBranchName, schemaName);
                for (SchemaVersionInfo schemaVersionInfo : schemaVersionInfos) {
                    if (SchemaVersionLifecycleStates.ENABLED.getId().equals(schemaVersionInfo.getStateId())) {
                        compatibilityResult = checkCompatibility(schemaMetadata.getType(),
                                                                 toSchema,
                                                                 schemaVersionInfo.getSchemaText(),
                                                                 schemaMetadata.getCompatibility());
                        if (!compatibilityResult.isCompatible()) {
                            LOG.info("Received schema is not compatible with one of the schema versions [{}] with schema name [{}]",
                                     schemaVersionInfo.getVersion(), schemaName);
                            break;
                        }
                    }
                }
                break;
        }
        return compatibilityResult != null ? compatibilityResult : CompatibilityResult.createCompatibleResult(toSchema);
    }

    private CompatibilityResult checkCompatibility(String type,
                                                   String toSchema,
                                                   String existingSchema,
                                                   SchemaCompatibility compatibility) {
        SchemaProvider schemaProvider = getSchemaProvider(type);
        if (schemaProvider == null) {
            throw new IllegalStateException("No SchemaProvider registered for type: " + type);
        }

        return schemaProvider.checkCompatibility(toSchema, existingSchema, compatibility);
    }

    public Collection<SchemaVersionInfo> getAllVersions(final String schemaBranchName,
                                                        final String schemaName) throws SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");

        Collection<SchemaVersionInfo> schemaVersionInfos;
        SchemaBranchKey schemaBranchKey = new SchemaBranchKey(schemaBranchName, schemaName);

        schemaVersionInfos = Lists.reverse(getSortedSchemaVersions(schemaBranchCache.get(SchemaBranchCache.Key.of(schemaBranchKey))));
        if (schemaVersionInfos == null || schemaVersionInfos.isEmpty())
            schemaVersionInfos = Collections.emptyList();

        return schemaVersionInfos;
    }


    public Collection<SchemaVersionInfo> getAllVersions(final String schemaBranchName,
                                                        final String schemaName,
                                                        final List<Byte> stateIds) throws SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");
        Preconditions.checkNotNull(stateIds, "State Ids can't be null");

        Set<Byte> stateIdSet = stateIds.stream().collect(Collectors.toSet());

        return getAllVersions(schemaBranchName, schemaName).stream().
                filter(schemaVersionInfo -> stateIdSet.contains(schemaVersionInfo.getStateId())).
                collect(Collectors.toList());
    }


    public Collection<SchemaVersionInfo> getAllVersions(final String schemaName) throws SchemaNotFoundException {
        List<QueryParam> queryParams = Collections.singletonList(new QueryParam(SchemaVersionStorable.NAME, schemaName));

        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadataInfo(schemaName);
        if (schemaMetadataInfo == null) {
            throw new SchemaNotFoundException("Schema not found with name " + schemaName);
        }

        Collection<SchemaVersionStorable> storables = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams,
                                                                          Collections.singletonList(OrderByField.of(SchemaVersionStorable.VERSION, true)));
        List<SchemaVersionInfo> schemaVersionInfos;
        if (storables != null && !storables.isEmpty()) {
            schemaVersionInfos = storables
                    .stream()
                    .map(SchemaVersionStorable::toSchemaVersionInfo)
                    .collect(Collectors.toList());
        } else {
            schemaVersionInfos = Collections.emptyList();
        }
        return schemaVersionInfos;
    }

    private SchemaMetadataInfo getSchemaMetadataInfo(String schemaName) {
        return schemaMetadataFetcher.getSchemaMetadataInfo(schemaName);
    }

    public SchemaVersionInfo getSchemaVersionInfo(String schemaName,
                                                  String schemaText,
                                                  boolean disableCanonicalCheck) throws SchemaNotFoundException, InvalidSchemaException, SchemaBranchNotFoundException {
        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadataInfo(schemaName);
        if (schemaMetadataInfo == null) {
            throw new SchemaNotFoundException("No schema found for schema metadata key: " + schemaName);
        }

        return findSchemaVersion(SchemaBranch.MASTER_BRANCH,
                                 schemaMetadataInfo.getSchemaMetadata().getType(),
                                 schemaText,
                                 schemaName,
                                 disableCanonicalCheck);
    }

    private SchemaVersionInfo fetchSchemaVersionInfo(Long id) throws SchemaNotFoundException {
        StorableKey storableKey = new StorableKey(SchemaVersionStorable.NAME_SPACE, SchemaVersionStorable.getPrimaryKey(id));

        SchemaVersionStorable versionedSchema = storageManager.get(storableKey);
        if (versionedSchema == null) {
            throw new SchemaNotFoundException("No Schema version exists with id " + id);
        }
        return versionedSchema.toSchemaVersionInfo();
    }

    private SchemaVersionInfo findSchemaVersion(String schemaBranchName,
                                                String type,
                                                String schemaText,
                                                String schemaMetadataName,
                                                boolean disableCanonicalCheck) throws InvalidSchemaException, SchemaNotFoundException, SchemaBranchNotFoundException {

        Preconditions.checkNotNull(schemaBranchName, "Schema branch name can't be null");

        String fingerPrint = getFingerprint(type, schemaText);
        LOG.debug("Fingerprint of the given schema [{}] is [{}]", schemaText, fingerPrint);
        List<QueryParam> queryParams = Lists.newArrayList(
                new QueryParam(SchemaVersionStorable.NAME, schemaMetadataName),
                new QueryParam(SchemaVersionStorable.FINGERPRINT, fingerPrint));

        Collection<SchemaVersionStorable> versionedSchemas = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams);

        Map<Long, SchemaVersionStorable> matchedSchemaVersionMap = null;
        if (versionedSchemas != null && !versionedSchemas.isEmpty()) {
            if (versionedSchemas.size() > 1) {
                LOG.warn("Exists more than one schema with schemaMetadataName: [{}] and schemaText [{}]", schemaMetadataName, schemaText);
            }

            matchedSchemaVersionMap = versionedSchemas.stream().collect(Collectors.toMap(SchemaVersionStorable::getId, v -> v));
        }

        if (matchedSchemaVersionMap == null) {
            return null;
        } else {
            SchemaBranch schemaBranch = schemaBranchCache.get(SchemaBranchCache.Key.of(new SchemaBranchKey(schemaBranchName, schemaMetadataName)));
            SchemaVersionInfo matchedSchemaVersionInfo = null;

            // If the disableCanonicalCheck is set to false, then return the lastest schema version that matches the fingerprint
            for (SchemaVersionInfo schemaVersionInfo : getSortedSchemaVersions(schemaBranch)) {
                if (matchedSchemaVersionMap.containsKey(schemaVersionInfo.getId())) {
                    if (!disableCanonicalCheck || schemaVersionInfo.getSchemaText().equals(schemaText)) {
                        matchedSchemaVersionInfo = schemaVersionInfo;
                    }
                }
            }

            return matchedSchemaVersionInfo;
        }
    }

    private String getFingerprint(String type,
                                  String schemaText) throws InvalidSchemaException, SchemaNotFoundException {
        SchemaProvider schemaProvider = getSchemaProvider(type);
        return Hex.encodeHexString(schemaProvider.getFingerprint(schemaText));
    }

    public SchemaVersionInfo getSchemaVersionInfo(SchemaIdVersion schemaIdVersion) throws SchemaNotFoundException {
        return schemaVersionInfoCache.getSchema(SchemaVersionInfoCache.Key.of(schemaIdVersion));
    }

    public SchemaVersionInfo getSchemaVersionInfo(SchemaVersionKey schemaVersionKey) throws SchemaNotFoundException {
        return schemaVersionInfoCache.getSchema(SchemaVersionInfoCache.Key.of(schemaVersionKey));
    }

    public SchemaVersionInfo findSchemaVersionInfoByFingerprint(final String fingerprint) throws SchemaNotFoundException {
        final List<QueryParam> queryParams = Collections.singletonList(new QueryParam(SchemaVersionStorable.FINGERPRINT, fingerprint));
        final List<OrderByField> orderParams = Collections.singletonList(OrderByField.of(SchemaVersionStorable.TIMESTAMP, true));

        final Collection<SchemaVersionStorable> schemas = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams, orderParams);

        if (schemas.isEmpty()) {
            throw new SchemaNotFoundException(String.format("No schema found for fingerprint: %s", fingerprint));
        } else {
            if (schemas.size() > 1) {
                LOG.warn(String.format("Multiple schemas found for the same fingerprint: %s", fingerprint));
            }

            return schemas.stream()
                    .findFirst()
                    .get()
                    .toSchemaVersionInfo();
        }
    }

    public void deleteSchemaVersion(SchemaVersionKey schemaVersionKey) throws SchemaNotFoundException, SchemaLifecycleException {
        SchemaVersionInfoCache.Key schemaVersionCacheKey = new SchemaVersionInfoCache.Key(schemaVersionKey);
        SchemaVersionInfo schemaVersionInfo = schemaVersionInfoCache.getSchema(schemaVersionCacheKey);
        invalidateSchemaInAllHAServer(schemaVersionCacheKey);
        storageManager.remove(createSchemaVersionStorableKey(schemaVersionInfo.getId()));
        deleteSchemaVersionBranchMapping(schemaVersionInfo.getId());
    }

    public SchemaVersionMergeResult mergeSchemaVersion(Long schemaVersionId,
                                                       SchemaVersionMergeStrategy schemaVersionMergeStrategy,
                                                       boolean disableCanonicalCheck) throws SchemaNotFoundException, IncompatibleSchemaException {

        try {
            SchemaVersionInfo schemaVersionInfo = getSchemaVersionInfo(new SchemaIdVersion(schemaVersionId));
            SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadataInfo(schemaVersionInfo.getName());

            Set<SchemaBranch> schemaBranches = getSchemaBranches(schemaVersionId).stream().filter(schemaBranch -> {
                try {
                    return !getRootVersion(schemaBranch).getId().equals(schemaVersionId);
                } catch (SchemaNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toSet());

            if (schemaBranches.size() > 1) {
                throw new SchemaVersionMergeException(String.format("Can't determine a unique schema branch for schema version id : '%s'", schemaVersionId));
            } else if (schemaBranches.size() == 0) {
                throw new SchemaVersionMergeException(String.format("Schema version id : '%s' is not associated with any branch", schemaVersionId));
            }

            Long schemaBranchId = schemaBranches.iterator().next().getId();
            SchemaBranch schemaBranch = schemaBranchCache.get(SchemaBranchCache.Key.of(schemaBranchId));

            if (schemaVersionMergeStrategy.equals(SchemaVersionMergeStrategy.PESSIMISTIC)) {
                SchemaVersionInfo latestSchemaVersion = getLatestEnabledSchemaVersionInfo(SchemaBranch.MASTER_BRANCH,
                                                                                          schemaMetadataInfo.getSchemaMetadata()
                                                                                                            .getName());
                SchemaVersionInfo rootSchemaVersion = getRootVersion(schemaBranch);
                if (!latestSchemaVersion.getId().equals(rootSchemaVersion.getId())) {
                    throw new SchemaVersionMergeException(String.format("The latest version of '%s' is different from the root version of the branch : '%s'",
                                                                        SchemaBranch.MASTER_BRANCH, schemaMetadataInfo.getSchemaMetadata()
                                                                                                                      .getName()));
                }
            }

            byte[] initializedStateDetails;
            try {
                initializedStateDetails = ObjectMapperUtils.serialize(new InitializedStateDetails(schemaBranch.getName(), schemaVersionInfo
                        .getId()));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(String.format("Failed to serialize initializedState for %s and %s", schemaBranch
                        .getName(), schemaVersionInfo.getId()));
            }

            SchemaVersionInfo createdSchemaVersionInfo;
            try {
                SchemaVersionInfo existingSchemaVersionInfo = findSchemaVersion(SchemaBranch.MASTER_BRANCH,
                                                                                schemaMetadataInfo.getSchemaMetadata().getType(),
                                                                                schemaVersionInfo.getSchemaText(),
                                                                                schemaMetadataInfo.getSchemaMetadata().getName(),
                                                                                disableCanonicalCheck);
                if (existingSchemaVersionInfo != null) {
                    String mergeMessage = String.format("Given version %d is already merged to master with version %d",
                                                        schemaVersionId, existingSchemaVersionInfo.getVersion());
                    LOG.info(mergeMessage);
                    return new SchemaVersionMergeResult(new SchemaIdVersion(schemaMetadataInfo.getId(),
                                                                            existingSchemaVersionInfo.getVersion(),
                                                                            existingSchemaVersionInfo.getId()),
                                                        mergeMessage);
                }

                createdSchemaVersionInfo = createSchemaVersion(SchemaBranch.MASTER_BRANCH,
                                                               schemaMetadataInfo.getSchemaMetadata(),
                                                               schemaMetadataInfo.getId(),
                                                               new SchemaVersion(schemaVersionInfo.getSchemaText(),
                                                                                 schemaVersionInfo.getDescription(),
                                                                                 SchemaVersionLifecycleStates.INITIATED.getId(),
                                                                                 initializedStateDetails));
            } catch (InvalidSchemaException e) {
                throw new SchemaVersionMergeException(String.format("Failed to merge schema version : '%s'", schemaVersionId
                        .toString()), e);
            }

            Collection<SchemaVersionStateStorable> schemaVersionStates =
                    storageManager.find(SchemaVersionStateStorable.NAME_SPACE,
                                        Collections.singletonList(new QueryParam(SchemaVersionStateStorable.SCHEMA_VERSION_ID,
                                                                                 schemaVersionId.toString())),
                                        Collections.singletonList(OrderByField.of(SchemaVersionStateStorable.SEQUENCE, true)));

            if (schemaVersionStates == null || schemaVersionStates.isEmpty()) {
                throw new RuntimeException(String.format("The database doesn't have any state transition recorded for the schema version id : '%s'", schemaVersionId));
            }

            updateSchemaVersionState(createdSchemaVersionInfo.getId(),
                                     schemaVersionStates.iterator().next().getSequence(),
                                     SchemaVersionLifecycleStates.ENABLED.getId(),
                                     null);

            String mergeMessage = String.format("Given version %d is merged successfully to master with version %d",
                                                schemaVersionId, createdSchemaVersionInfo.getVersion());
            LOG.info(mergeMessage);
            return new SchemaVersionMergeResult(new SchemaIdVersion(schemaMetadataInfo.getId(),
                                                                    createdSchemaVersionInfo.getVersion(),
                                                                    createdSchemaVersionInfo.getId()),
                                                mergeMessage);
        } catch (SchemaBranchNotFoundException e) {
            throw new SchemaVersionMergeException(String.format("Failed to merge schema version : '%s'", schemaVersionId
                    .toString()), e);
        }
    }

    private ImmutablePair<SchemaVersionLifecycleContext, SchemaVersionLifecycleState>
    createSchemaVersionLifeCycleContextAndState(Long schemaVersionId) throws SchemaNotFoundException {
        // get the current state from storage for the given versionID
        // we can use a query to get max value for the column for a given schema-version-id but StorageManager does not
        // have API to take custom queries.
        Collection<SchemaVersionStateStorable> schemaVersionStates =
                storageManager.find(SchemaVersionStateStorable.NAME_SPACE,
                                    Collections.singletonList(new QueryParam(SchemaVersionStateStorable.SCHEMA_VERSION_ID,
                                                                             schemaVersionId.toString())),
                                    Collections.singletonList(OrderByField.of(SchemaVersionStateStorable.SEQUENCE, true)));
        if (schemaVersionStates.isEmpty()) {
            throw new SchemaNotFoundException("No schema versions found with id " + schemaVersionId);
        }
        SchemaVersionStateStorable stateStorable = schemaVersionStates.iterator().next();

        SchemaVersionLifecycleState schemaVersionLifecycleState = schemaVersionLifecycleStateMachine.getStates()
                                                                                                    .get(stateStorable.getStateId());
        SchemaVersionService schemaVersionService = createSchemaVersionService();
        SchemaVersionLifecycleContext context = new SchemaVersionLifecycleContext(stateStorable.getSchemaVersionId(),
                                                                                  stateStorable.getSequence(),
                                                                                  schemaVersionService,
                                                                                  schemaVersionLifecycleStateMachine,
                                                                                  customSchemaStateExecutor);
        return new ImmutablePair<>(context, schemaVersionLifecycleState);
    }

    public SchemaVersionLifecycleContext createSchemaVersionLifeCycleContext(Long schemaVersionId,
                                                                             SchemaVersionLifecycleState schemaVersionLifecycleState) throws SchemaNotFoundException {
        // get the current state from storage for the given versionID
        // we can use a query to get max value for the column for a given schema-version-id but StorageManager does not
        // have API to take custom queries.

        List<QueryParam> queryParams = new ArrayList<>();
        queryParams.add(new QueryParam(SchemaVersionStateStorable.SCHEMA_VERSION_ID, schemaVersionId.toString()));
        queryParams.add(new QueryParam(SchemaVersionStateStorable.STATE, schemaVersionLifecycleState.getId()
                                                                                                    .toString()));

        Collection<SchemaVersionStateStorable> schemaVersionStates =
                storageManager.find(SchemaVersionStateStorable.NAME_SPACE,
                                    queryParams,
                                    Collections.singletonList(OrderByField.of(SchemaVersionStateStorable.SEQUENCE, true)));
        if (schemaVersionStates.isEmpty()) {
            throw new SchemaNotFoundException("No schema versions found with id " + schemaVersionId);
        }
        SchemaVersionStateStorable stateStorable = schemaVersionStates.iterator().next();

        SchemaVersionService schemaVersionService = createSchemaVersionService();
        SchemaVersionLifecycleContext context = new SchemaVersionLifecycleContext(stateStorable.getSchemaVersionId(),
                                                                                  stateStorable.getSequence(),
                                                                                  schemaVersionService,
                                                                                  schemaVersionLifecycleStateMachine,
                                                                                  customSchemaStateExecutor);
        context.setDetails(stateStorable.getDetails());
        return context;
    }

    private SchemaVersionService createSchemaVersionService() {
        return new SchemaVersionService() {

            public void updateSchemaVersionState(SchemaVersionLifecycleContext schemaVersionLifecycleContext) throws SchemaNotFoundException {
                storeSchemaVersionState(schemaVersionLifecycleContext);
            }

            public void deleteSchemaVersion(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException {
                doDeleteSchemaVersion(schemaVersionId);
            }

            @Override
            public SchemaMetadataInfo getSchemaMetadata(long schemaVersionId) throws SchemaNotFoundException {
                SchemaVersionInfo schemaVersionInfo = getSchemaVersionInfo(schemaVersionId);
                return getSchemaMetadataInfo(schemaVersionInfo.getName());
            }

            @Override
            public SchemaVersionInfo getSchemaVersionInfo(long schemaVersionId) throws SchemaNotFoundException {
                return SchemaVersionLifecycleManager.this.getSchemaVersionInfo(new SchemaIdVersion(schemaVersionId));
            }

            @Override
            public CompatibilityResult checkForCompatibility(SchemaMetadata schemaMetadata,
                                                             String toSchemaText,
                                                             String existingSchemaText) {
                return checkCompatibility(schemaMetadata.getType(), toSchemaText, existingSchemaText, schemaMetadata.getCompatibility());
            }

            @Override
            public Collection<SchemaVersionInfo> getAllSchemaVersions(String schemaBranchName,
                                                                      String schemaName)
                    throws SchemaNotFoundException, SchemaBranchNotFoundException {
                return getAllVersions(schemaBranchName, schemaName);
            }
        };
    }

    private void storeSchemaVersionState(SchemaVersionLifecycleContext schemaVersionLifecycleContext) throws SchemaNotFoundException {
        // store versions state, sequence
        SchemaVersionStateStorable stateStorable = new SchemaVersionStateStorable();
        Long schemaVersionId = schemaVersionLifecycleContext.getSchemaVersionId();
        byte stateId = schemaVersionLifecycleContext.getState().getId();

        stateStorable.setSchemaVersionId(schemaVersionId);
        stateStorable.setSequence(schemaVersionLifecycleContext.getSequence() + 1);
        stateStorable.setStateId(stateId);
        stateStorable.setTimestamp(System.currentTimeMillis());
        stateStorable.setDetails(schemaVersionLifecycleContext.getDetails());
        stateStorable.setId(storageManager.nextId(SchemaVersionStateStorable.NAME_SPACE));

        storageManager.add(stateStorable);

        // store latest state in versions entity
        StorableKey storableKey = new StorableKey(SchemaVersionStorable.NAME_SPACE, SchemaVersionStorable.getPrimaryKey(schemaVersionId));
        SchemaVersionStorable versionedSchema = storageManager.get(storableKey);
        if (versionedSchema == null) {
            throw new SchemaNotFoundException("No Schema version exists with id " + schemaVersionId);
        }
        versionedSchema.setState(stateId);
        storageManager.update(versionedSchema);

        // invalidate schema version from cache
        SchemaVersionInfoCache.Key schemaVersionCacheKey = SchemaVersionInfoCache.Key.of(new SchemaIdVersion(schemaVersionId));
        invalidateSchemaInAllHAServer(schemaVersionCacheKey);
    }

    public void enableSchemaVersion(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException, IncompatibleSchemaException, SchemaBranchNotFoundException {
        ImmutablePair<SchemaVersionLifecycleContext, SchemaVersionLifecycleState> pair = createSchemaVersionLifeCycleContextAndState(schemaVersionId);
        ((InbuiltSchemaVersionLifecycleState) pair.getRight()).enable(pair.getLeft());
    }

    public void deleteSchemaVersion(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException {
        ImmutablePair<SchemaVersionLifecycleContext, SchemaVersionLifecycleState> pair = createSchemaVersionLifeCycleContextAndState(schemaVersionId);
        ((InbuiltSchemaVersionLifecycleState) pair.getRight()).delete(pair.getLeft());
    }

    private void doDeleteSchemaVersion(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException {
        SchemaVersionInfoCache.Key schemaVersionCacheKey = SchemaVersionInfoCache.Key.of(new SchemaIdVersion(schemaVersionId));
        invalidateSchemaInAllHAServer(schemaVersionCacheKey);
        storageManager.remove(createSchemaVersionStorableKey(schemaVersionId));
        deleteSchemaVersionBranchMapping(schemaVersionId);
    }

    private StorableKey createSchemaVersionStorableKey(Long id) {
        SchemaVersionStorable schemaVersionStorable = new SchemaVersionStorable();
        schemaVersionStorable.setId(id);
        return schemaVersionStorable.getStorableKey();
    }

    private void deleteSchemaVersionBranchMapping(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException {
        List<QueryParam> schemaVersionMappingStorableQueryParams = Lists.newArrayList();
        schemaVersionMappingStorableQueryParams.add(new QueryParam(SchemaBranchVersionMapping.SCHEMA_VERSION_INFO_ID, schemaVersionId
                .toString()));
        List<OrderByField> orderByFields = new ArrayList<>();
        orderByFields.add(OrderByField.of(SchemaBranchVersionMapping.SCHEMA_VERSION_INFO_ID, false));

        Collection<SchemaBranchVersionMapping> storables = storageManager.find(SchemaBranchVersionMapping.NAMESPACE, schemaVersionMappingStorableQueryParams, orderByFields);

        if (storables == null || storables.isEmpty()) {
            LOG.debug("No need to delete schema version mapping as the database did a cascade delete");
            return;
        }

        if (storables.size() > 1) {
            List<String> branchNamesTiedToSchema = storables.stream().map(storable -> schemaBranchCache.get(SchemaBranchCache.Key.of(storable.getSchemaBranchId())).getName()).collect(Collectors.toList());
            throw new SchemaLifecycleException(String.format("Schema version with id : '%s' is tied with more than one branch : '%s' ", schemaVersionId.toString(), Arrays.toString(branchNamesTiedToSchema.toArray())));
        }

        storageManager.remove(new StorableKey(SchemaBranchVersionMapping.NAMESPACE,
                                              storables.iterator()
                                                       .next()
                                                       .getPrimaryKey()));
    }

    public void archiveSchemaVersion(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException {
        ImmutablePair<SchemaVersionLifecycleContext, SchemaVersionLifecycleState> pair = createSchemaVersionLifeCycleContextAndState(schemaVersionId);
        ((InbuiltSchemaVersionLifecycleState) pair.getRight()).archive(pair.getLeft());
    }

    public void disableSchemaVersion(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException {
        ImmutablePair<SchemaVersionLifecycleContext, SchemaVersionLifecycleState> pair = createSchemaVersionLifeCycleContextAndState(schemaVersionId);
        ((InbuiltSchemaVersionLifecycleState) pair.getRight()).disable(pair.getLeft());
    }

    public void startSchemaVersionReview(Long schemaVersionId) throws SchemaNotFoundException, SchemaLifecycleException {
        ImmutablePair<SchemaVersionLifecycleContext, SchemaVersionLifecycleState> pair = createSchemaVersionLifeCycleContextAndState(schemaVersionId);
        ((InbuiltSchemaVersionLifecycleState) pair.getRight()).startReview(pair.getLeft());
    }

    public void executeState(Long schemaVersionId, Byte targetState, byte[] transitionDetails)
            throws SchemaLifecycleException, SchemaNotFoundException {
        ImmutablePair<SchemaVersionLifecycleContext, SchemaVersionLifecycleState> schemaLifeCycleContextAndState =
                createSchemaVersionLifeCycleContextAndState(schemaVersionId);
        SchemaVersionLifecycleContext schemaVersionLifecycleContext = schemaLifeCycleContextAndState.getLeft();
        SchemaVersionLifecycleState currentState = schemaLifeCycleContextAndState.getRight();

        schemaVersionLifecycleContext.setState(currentState);
        schemaVersionLifecycleContext.setDetails(transitionDetails);
        SchemaVersionLifecycleStateTransition transition =
                new SchemaVersionLifecycleStateTransition(currentState.getId(), targetState);
        SchemaVersionLifecycleStateAction action = schemaVersionLifecycleContext.getSchemaLifeCycleStatesMachine()
                                                                                .getTransitions()
                                                                                .get(transition);
        try {
            List<SchemaVersionLifecycleStateTransitionListener> listeners =
                    schemaVersionLifecycleContext.getSchemaLifeCycleStatesMachine()
                                                 .getListeners()
                                                 .getOrDefault(transition, DEFAULT_LISTENERS);

            listeners.stream().forEach(listener -> listener.preStateTransition(schemaVersionLifecycleContext));
            action.execute(schemaVersionLifecycleContext);
            listeners.stream().forEach(listener -> listener.postStateTransition(schemaVersionLifecycleContext));
        } catch (SchemaLifecycleException e) {
            Throwable cause = e.getCause();
            if (cause != null && cause instanceof SchemaNotFoundException) {
                throw (SchemaNotFoundException) cause;
            }
            throw e;
        }
    }

    private SchemaVersionInfo retrieveSchemaVersionInfo(SchemaVersionKey schemaVersionKey) throws SchemaNotFoundException {
        String schemaName = schemaVersionKey.getSchemaName();
        Integer version = schemaVersionKey.getVersion();
        SchemaMetadataInfo schemaMetadataInfo = getSchemaMetadataInfo(schemaName);

        if (schemaMetadataInfo == null) {
            throw new SchemaNotFoundException("No SchemaMetadata exists with key: " + schemaName);
        }

        return fetchSchemaVersionInfo(schemaVersionKey.getSchemaName(), version);
    }

    private SchemaVersionInfo retrieveSchemaVersionInfo(SchemaIdVersion key) throws SchemaNotFoundException {
        SchemaVersionInfo schemaVersionInfo = null;
        if (key.getSchemaVersionId() != null) {
            schemaVersionInfo = fetchSchemaVersionInfo(key.getSchemaVersionId());
        } else if (key.getSchemaMetadataId() != null) {
            SchemaMetadataInfo schemaMetadataInfo = schemaMetadataFetcher.getSchemaMetadataInfo(key.getSchemaMetadataId());
            Integer version = key.getVersion();
            schemaVersionInfo = fetchSchemaVersionInfo(schemaMetadataInfo.getSchemaMetadata().getName(), version);
        } else {
            throw new IllegalArgumentException("Invalid SchemaIdVersion: " + key);
        }

        return schemaVersionInfo;
    }

    private SchemaVersionRetriever createSchemaVersionRetriever() {
        return new SchemaVersionRetriever() {
            @Override
            public SchemaVersionInfo retrieveSchemaVersion(SchemaVersionKey key) throws SchemaNotFoundException {
                return retrieveSchemaVersionInfo(key);
            }

            @Override
            public SchemaVersionInfo retrieveSchemaVersion(SchemaIdVersion key) throws SchemaNotFoundException {
                return retrieveSchemaVersionInfo(key);
            }
        };
    }

    private SchemaVersionInfo fetchSchemaVersionInfo(String schemaName,
                                                     Integer version) throws SchemaNotFoundException {
        LOG.info("##### fetching schema version for name: [{}] version: [{}]", schemaName, version);
        SchemaVersionInfo schemaVersionInfo = null;
        if (SchemaVersionKey.LATEST_VERSION.equals(version)) {
            schemaVersionInfo = getLatestSchemaVersionInfo(schemaName);
        } else {
            List<QueryParam> queryParams = Lists.newArrayList(
                    new QueryParam(SchemaVersionStorable.NAME, schemaName),
                    new QueryParam(SchemaVersionStorable.VERSION, version.toString()));

            Collection<SchemaVersionStorable> versionedSchemas = storageManager.find(SchemaVersionStorable.NAME_SPACE, queryParams);
            if (versionedSchemas != null && !versionedSchemas.isEmpty()) {
                if (versionedSchemas.size() > 1) {
                    LOG.warn("More than one schema exists with name: [{}] and version [{}]", schemaName, version);
                }
                schemaVersionInfo = versionedSchemas.iterator().next().toSchemaVersionInfo();
            } else {
                throw new SchemaNotFoundException("No Schema version exists with name " + schemaName + " and version " + version);
            }
        }
        LOG.info("##### fetched schema version info [{}]", schemaVersionInfo);
        return schemaVersionInfo;
    }

    public Set<SchemaBranch> getSchemaBranches(Long schemaVersionId) throws SchemaBranchNotFoundException {
        List<QueryParam> schemaVersionMappingStorableQueryParams = new ArrayList<>();
        Set<SchemaBranch> schemaBranches = new HashSet<>();
        schemaVersionMappingStorableQueryParams.add(new QueryParam(SchemaBranchVersionMapping.SCHEMA_VERSION_INFO_ID, schemaVersionId
                .toString()));

        for (Storable storable : storageManager.find(SchemaBranchVersionMapping.NAMESPACE, schemaVersionMappingStorableQueryParams)) {
            schemaBranches.add(schemaBranchCache.get(SchemaBranchCache.Key.of(((SchemaBranchVersionMapping) storable).getSchemaBranchId())));
        }

        return schemaBranches;
    }

    private List<SchemaVersionInfo> getSortedSchemaVersions(Long schemaBranchId) throws SchemaNotFoundException, SchemaBranchNotFoundException {
        List<QueryParam> schemaVersionMappingStorableQueryParams = Lists.newArrayList();
        schemaVersionMappingStorableQueryParams.add(new QueryParam(SchemaBranchVersionMapping.SCHEMA_BRANCH_ID, schemaBranchId
                .toString()));
        List<OrderByField> orderByFields = new ArrayList<>();
        orderByFields.add(OrderByField.of(SchemaBranchVersionMapping.SCHEMA_VERSION_INFO_ID, false));
        List<SchemaVersionInfo> schemaVersionInfos = new ArrayList<>();

        Collection<SchemaBranchVersionMapping> storables = storageManager.find(SchemaBranchVersionMapping.NAMESPACE, schemaVersionMappingStorableQueryParams, orderByFields);
        if (storables == null || storables.size() == 0) {
            if (schemaBranchCache.get(SchemaBranchCache.Key.of(schemaBranchId))
                                 .getName()
                                 .equals(SchemaBranch.MASTER_BRANCH))
                return Collections.emptyList();
            else
                throw new InvalidSchemaBranchVersionMapping(String.format("No schema versions are attached to the schema branch id : '%s'", schemaBranchId));
        }

        for (SchemaBranchVersionMapping storable : storables) {
            SchemaIdVersion schemaIdVersion = new SchemaIdVersion(storable.getSchemaVersionInfoId());
            schemaVersionInfos.add(schemaVersionInfoCache.getSchema(SchemaVersionInfoCache.Key.of(schemaIdVersion)));
        }

        return schemaVersionInfos;
    }

    public List<SchemaVersionInfo> getSortedSchemaVersions(SchemaBranch schemaBranch) throws SchemaNotFoundException {
        try {
            return getSortedSchemaVersions(schemaBranch.getId());
        } catch (SchemaBranchNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public SchemaVersionInfo getRootVersion(SchemaBranch schemaBranch) throws SchemaNotFoundException {

        if (schemaBranch.getName().equals(SchemaBranch.MASTER_BRANCH)) {
            throw new SchemaNotFoundException(String.format("There is no root schema version attached to the schema branch '%s'",
                                                            schemaBranch.getName()));
        }

        List<SchemaVersionInfo> sortedVersionInfo;
        try {
            sortedVersionInfo = getSortedSchemaVersions(schemaBranch.getId());
        } catch (SchemaBranchNotFoundException e) {
            throw new RuntimeException(e);
        }

        if (sortedVersionInfo == null)
            throw new SchemaNotFoundException(String.format("There were no schema versions attached to schema branch '%s'",
                                                            schemaBranch.getName()));
        return sortedVersionInfo.iterator().next();
    }

    public void invalidateAllSchemaVersionCache() {
        schemaVersionInfoCache.invalidateAll();
    }

    public void invalidateSchemaVersionCache(SchemaVersionInfoCache.Key key) {
        schemaVersionInfoCache.invalidateSchema(key);
    }

    public void invalidateSchemaInAllHAServer(SchemaVersionInfoCache.Key key) {
        schemaVersionInfoCache.invalidateSchema(key);

        String keyAsString;

        try {
            keyAsString = ObjectMapperUtils.serializeToString(key);
        } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to serialized key : %s", key),e);
        }

        haServerNotificationManager.notifyCacheInvalidation(schemaVersionInfoCache.getCacheType(),keyAsString);

    }

}
