package org.fetarute.fetaruteTCAddon.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.fetarute.fetaruteTCAddon.storage.schema.StorageSchema;
import org.junit.jupiter.api.Test;

final class StorageSchemaDialectTest {

    @Test
    void sqliteSchemaUsesTablePrefix() {
        StorageSchema schema = new StorageSchema("fta_");
        List<String> ddl = schema.sqliteStatements();
        String routes = ddl.stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_routes"))
                .findFirst()
                .orElseThrow();
        assertTrue(routes.contains("fta_routes"), "应当带上表前缀");
    }

    @Test
    void schemaDoesNotExposeTcRouteIdColumn() {
        StorageSchema schema = new StorageSchema("fta_");
        String routes = schema.sqliteStatements().stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS fta_routes"))
                .findFirst()
                .orElseThrow();
        boolean hasTcRouteColumn = routes.lines()
                .anyMatch(line -> line.stripLeading().startsWith("tc_route_id"));
        assertTrue(!hasTcRouteColumn, "schema 中不应再包含 tc_route_id");
    }
}
