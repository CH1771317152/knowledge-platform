package com.platform.relation.event;

import java.util.List;
import java.util.Map;

/**
 * The subset of a Canal flat message that this service consumes. Canal emits data-change events
 * as flat JSON; the {@code data} field is a list of row maps, where each map is column-name to
 * string-value. The consumer reads {@code data.get(0).get("payload_json")} to recover the
 * {@link RelationEventPayload}.
 *
 * @param database source database name
 * @param table    source table name (e.g. "relation_outbox_event")
 * @param type     Canal change type (INSERT / UPDATE / DELETE)
 * @param data     list of changed rows, each a column-to-string-value map
 */
public record CanalFlatMessage(
        String database,
        String table,
        String type,
        List<Map<String, String>> data
) {}
