package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceLocationPoint;
import com.things.link.device.domain.DeviceLocationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

/** 坐标与文本字段分离；PostGIS坐标顺序为经度、纬度。 */
@Repository
public class JdbcDeviceLocationRepository implements DeviceLocationRepository {
    private final JdbcTemplate jdbc;
    public JdbcDeviceLocationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<DeviceLocationPoint> find(UUID project, UUID device) {
        return jdbc.query("""
                SELECT ST_X(location_point::geometry) AS longitude,
                       ST_Y(location_point::geometry) AS latitude, location_point_version
                  FROM dev_device WHERE project_id=? AND id=? AND deleted_at IS NULL
                """, (r,n) -> new DeviceLocationPoint(r.getObject("longitude", Double.class),
                        r.getObject("latitude", Double.class), r.getLong("location_point_version")),
                project, device).stream().findFirst();
    }
    @Override public boolean update(UUID project, UUID device, Double longitude, Double latitude, long version) {
        return jdbc.update("""
                UPDATE dev_device SET location_point =
                    CASE WHEN ?::double precision IS NULL THEN NULL
                         ELSE ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision),4326)::geography END,
                    location_point_version=location_point_version+1, updated_at=now()
                 WHERE project_id=? AND id=? AND deleted_at IS NULL AND location_point_version=?
                   AND location_point_version < 9223372036854775807
                """, longitude, longitude, latitude, project, device, version) == 1;
    }
}
