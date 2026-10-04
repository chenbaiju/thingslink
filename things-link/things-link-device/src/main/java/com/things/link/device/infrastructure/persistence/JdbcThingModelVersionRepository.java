package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 使用项目关系轴解析版本和绑定历史；不对动态 JSON 建索引或执行 JSONPath。 */
@Repository
public class JdbcThingModelVersionRepository implements ThingModelVersionRepository {
    /** JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate JDBC 访问器 */
    public JdbcThingModelVersionRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override public Optional<ThingModelVersion> findPublished(UUID projectId, UUID versionId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, version_number, change_level,
                       model_snapshot::text, schema_digest, digest_algorithm, published_at
                  FROM dev_thing_model_version WHERE project_id = ? AND id = ?
                """, this::mapVersion, projectId, versionId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ThingModelVersion> findLatest(UUID projectId, UUID deviceTypeId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, version_number, change_level,
                       model_snapshot::text, schema_digest, digest_algorithm, published_at
                  FROM dev_thing_model_version
                 WHERE project_id = ? AND device_type_id = ?
                 ORDER BY version_major DESC, version_minor DESC, version_patch DESC LIMIT 1
                """, this::mapVersion, projectId, deviceTypeId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public ThingModelVersion create(UUID id, UUID tenantId, UUID projectId, UUID deviceTypeId,
                                    String versionNumber, ThingModelVersion.ChangeLevel changeLevel,
                                    String modelSnapshot, Instant publishedAt) {
        String[] parts = versionNumber.split("\\.");
        return jdbcTemplate.queryForObject("""
                INSERT INTO dev_thing_model_version
                    (id, tenant_id, project_id, device_type_id, version_number,
                     version_major, version_minor, version_patch, change_level, schema_profile,
                     model_snapshot, schema_digest, digest_algorithm, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TC_PROPERTY_COMPOSITE_V1', ?::jsonb,
                        encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex'),
                        'PG_JSONB_TEXT_V1_SHA256', ?)
                RETURNING id, tenant_id, project_id, device_type_id, version_number, change_level,
                          model_snapshot::text, schema_digest, digest_algorithm, published_at
                """, this::mapVersion, id, tenantId, projectId, deviceTypeId, versionNumber,
                Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                changeLevel.name(), modelSnapshot, modelSnapshot, Timestamp.from(publishedAt));
    }

    /** {@inheritDoc} */
    @Override
    public ThingModelVersion createInitialFromDefinitions(UUID tenantId, UUID projectId, UUID deviceTypeId) {
        ThingModelVersion version = jdbcTemplate.queryForObject("""
                WITH snapshot AS (
                    SELECT jsonb_build_object(
                        'properties', COALESCE((
                            SELECT jsonb_object_agg(p.property_key, jsonb_strip_nulls(jsonb_build_object(
                                'dataType', p.data_type, 'accessType', p.access_type, 'unit', p.unit,
                                'decimalPlaces', p.decimal_places, 'minimum', p.minimum_value,
                                'maximum', p.maximum_value, 'enum', p.enum_options, 'schema', p.schema)))
                              FROM dev_property_definition p
                             WHERE p.project_id = ? AND p.device_type_id = ? AND p.deleted_at IS NULL
                        ), '{}'::jsonb),
                        'events', COALESCE((
                            SELECT jsonb_object_agg(e.event_key, jsonb_build_object(
                                'level', e.level,
                                'parameters', COALESCE((
                                    SELECT jsonb_object_agg(ep.parameter_key,
                                        jsonb_strip_nulls(jsonb_build_object(
                                            'dataType', ep.data_type, 'required', ep.required,
                                            'enum', ep.enum_options)))
                                      FROM dev_event_parameter_definition ep
                                     WHERE ep.project_id = e.project_id AND ep.event_id = e.id
                                ), '{}'::jsonb)))
                              FROM dev_event_definition e
                             WHERE e.project_id = ? AND e.device_type_id = ? AND e.deleted_at IS NULL
                        ), '{}'::jsonb),
                        'commands', COALESCE((
                            SELECT jsonb_object_agg(c.command_key, jsonb_strip_nulls(jsonb_build_object(
                                'inputSchema', c.input_schema, 'outputSchema', c.output_schema)))
                              FROM dev_command_definition c
                             WHERE c.project_id = ? AND c.device_type_id = ? AND c.deleted_at IS NULL
                        ), '{}'::jsonb)) AS value
                )
                INSERT INTO dev_thing_model_version
                    (id, tenant_id, project_id, device_type_id, version_number,
                     version_major, version_minor, version_patch, change_level, schema_profile,
                     model_snapshot, schema_digest, digest_algorithm, published_at)
                SELECT gen_random_uuid(), ?, ?, ?, '1.0.0', 1, 0, 0, 'MAJOR',
                       'TC_PROPERTY_COMPOSITE_V1', value,
                       encode(digest(convert_to(value::text, 'UTF8'), 'sha256'), 'hex'),
                       'PG_JSONB_TEXT_V1_SHA256', now()
                  FROM snapshot
                RETURNING id, tenant_id, project_id, device_type_id, version_number, change_level,
                          model_snapshot::text, schema_digest, digest_algorithm, published_at
                """, this::mapVersion,
                projectId, deviceTypeId, projectId, deviceTypeId, projectId, deviceTypeId,
                tenantId, projectId, deviceTypeId);
        // S12-P0-2a / 架构§5.2：无关联ARRAY先按PostgreSQL UUID顺序完整取得设备锁，再更新。
        // 保留原单语句快照，不能在两条语句之间遗漏新提交的设备；只补空指针并同事务记录INITIAL。
        jdbcTemplate.update("""
                WITH bound AS (
                    UPDATE dev_device
                       SET thing_model_version_id = ?, updated_at = now()
                     WHERE project_id = ? AND device_type_id = ? AND deleted_at IS NULL
                       AND thing_model_version_id IS NULL
                       AND id = ANY(ARRAY(
                           SELECT candidate.id FROM dev_device candidate
                            WHERE candidate.project_id = ? AND candidate.device_type_id = ?
                              AND candidate.deleted_at IS NULL AND candidate.thing_model_version_id IS NULL
                            ORDER BY candidate.id FOR UPDATE
                       ))
                    RETURNING id, tenant_id, project_id, thing_model_version_id
                )
                INSERT INTO dev_device_model_binding_history
                    (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                     transition_key, transition_type, effective_at)
                SELECT gen_random_uuid(), tenant_id, project_id, id, NULL, thing_model_version_id,
                       gen_random_uuid(), 'INITIAL', now()
                  FROM bound
                """, version.id(), projectId, deviceTypeId, projectId, deviceTypeId);
        return version;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ResolvedBinding> resolve(UUID projectId, UUID deviceId, String versionNumber) {
        return jdbcTemplate.query("""
                SELECT d.id AS device_id, d.device_type_id, d.thing_model_version_id AS current_version_id,
                       v.id, v.tenant_id, v.project_id, v.version_number, v.change_level,
                       v.model_snapshot::text, v.schema_digest, v.digest_algorithm, v.published_at
                  FROM dev_device d
                  JOIN dev_thing_model_version v
                    ON v.project_id = d.project_id AND v.device_type_id = d.device_type_id
                   AND v.version_number = ?
                 WHERE d.project_id = ? AND d.id = ? AND d.deleted_at IS NULL
                   AND d.thing_model_version_id IS NOT NULL
                """, this::mapResolved, versionNumber, projectId, deviceId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public boolean wasDirectlyReplacedWithinWindow(UUID projectId, UUID deviceId, UUID oldVersionId,
                                                   UUID currentVersionId, Instant receivedAt, Instant notBefore) {
        Boolean result = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM dev_device_model_binding_history h
                     WHERE h.project_id = ? AND h.device_id = ?
                       AND h.from_model_version_id = ? AND h.to_model_version_id = ?
                       AND h.effective_at >= ? AND h.effective_at <= ?)
                """, Boolean.class, projectId, deviceId, oldVersionId, currentVersionId,
                Timestamp.from(notBefore), Timestamp.from(receivedAt));
        return Boolean.TRUE.equals(result);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<BindingTransition> findTransition(UUID projectId, UUID deviceId, UUID transitionKey) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                       transition_key, transition_type, effective_at
                  FROM dev_device_model_binding_history
                 WHERE project_id = ? AND device_id = ? AND transition_key = ?
                """, this::mapTransition, projectId, deviceId, transitionKey).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ResolvedBinding> lockCurrent(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT d.id AS device_id, d.device_type_id, d.thing_model_version_id AS current_version_id,
                       v.id, v.tenant_id, v.project_id, v.version_number, v.change_level,
                       v.model_snapshot::text, v.schema_digest, v.digest_algorithm, v.published_at
                  FROM dev_device d
                  JOIN dev_thing_model_version v
                    ON v.project_id = d.project_id AND v.id = d.thing_model_version_id
                 WHERE d.project_id = ? AND d.id = ? AND d.deleted_at IS NULL
                 FOR UPDATE OF d
                """, this::mapResolved, projectId, deviceId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ResolvedBinding> findCurrent(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT d.id AS device_id, d.device_type_id, d.thing_model_version_id AS current_version_id,
                       v.id, v.tenant_id, v.project_id, v.version_number, v.change_level,
                       v.model_snapshot::text, v.schema_digest, v.digest_algorithm, v.published_at
                  FROM dev_device d
                  JOIN dev_thing_model_version v
                    ON v.project_id = d.project_id AND v.id = d.thing_model_version_id
                 WHERE d.project_id = ? AND d.id = ? AND d.deleted_at IS NULL
                """, this::mapResolved, projectId, deviceId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasNonInitialTransition(UUID projectId, UUID deviceId) {
        Boolean result = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM dev_device_model_binding_history h
                     WHERE h.project_id = ? AND h.device_id = ? AND h.transition_type <> 'INITIAL')
                """, Boolean.class, projectId, deviceId);
        return Boolean.TRUE.equals(result);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<ThingModelVersion> findById(UUID projectId, UUID deviceTypeId, UUID versionId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, version_number, change_level,
                       model_snapshot::text, schema_digest, digest_algorithm, published_at
                  FROM dev_thing_model_version
                 WHERE project_id = ? AND device_type_id = ? AND id = ?
                """, this::mapVersion, projectId, deviceTypeId, versionId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public void appendTransition(BindingTransition value) {
        jdbcTemplate.update("""
                INSERT INTO dev_device_model_binding_history
                    (id, tenant_id, project_id, device_id, from_model_version_id, to_model_version_id,
                     transition_key, transition_type, effective_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.id(), value.tenantId(), value.projectId(), value.deviceId(), value.fromVersionId(),
                value.toVersionId(), value.transitionKey(), value.transitionType().name(),
                Timestamp.from(value.effectiveAt()));
    }

    /** {@inheritDoc} */
    @Override
    public boolean compareAndSetCurrent(UUID projectId, UUID deviceId, UUID expectedVersionId, UUID targetVersionId) {
        return jdbcTemplate.update("""
                UPDATE dev_device SET thing_model_version_id = ?, updated_at = now()
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                   AND thing_model_version_id IS NOT DISTINCT FROM ?
                """, targetVersionId, projectId, deviceId, expectedVersionId) == 1;
    }

    /** 映射联合版本投影。 */
    private ResolvedBinding mapResolved(ResultSet resultSet, int rowNum) throws SQLException {
        ThingModelVersion version = new ThingModelVersion(resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class), resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("device_type_id", UUID.class), resultSet.getString("version_number"),
                ThingModelVersion.ChangeLevel.valueOf(resultSet.getString("change_level")),
                resultSet.getString("model_snapshot"), resultSet.getString("schema_digest"),
                resultSet.getString("digest_algorithm"),
                resultSet.getTimestamp("published_at").toInstant());
        return new ResolvedBinding(resultSet.getObject("device_id", UUID.class),
                resultSet.getObject("device_type_id", UUID.class),
                resultSet.getObject("current_version_id", UUID.class), version);
    }

    /** 映射单个不可变版本。 */
    private ThingModelVersion mapVersion(ResultSet resultSet, int rowNum) throws SQLException {
        return new ThingModelVersion(resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class), resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("device_type_id", UUID.class), resultSet.getString("version_number"),
                ThingModelVersion.ChangeLevel.valueOf(resultSet.getString("change_level")),
                resultSet.getString("model_snapshot"), resultSet.getString("schema_digest"),
                resultSet.getString("digest_algorithm"),
                resultSet.getTimestamp("published_at").toInstant());
    }

    /** 映射不可变转换事实。 */
    private BindingTransition mapTransition(ResultSet resultSet, int rowNum) throws SQLException {
        return new BindingTransition(resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class), resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("device_id", UUID.class), resultSet.getObject("from_model_version_id", UUID.class),
                resultSet.getObject("to_model_version_id", UUID.class), resultSet.getObject("transition_key", UUID.class),
                BindingTransition.TransitionType.valueOf(resultSet.getString("transition_type")),
                resultSet.getTimestamp("effective_at").toInstant());
    }
}
