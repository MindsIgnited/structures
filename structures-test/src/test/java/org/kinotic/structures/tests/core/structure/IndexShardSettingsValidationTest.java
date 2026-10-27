package org.kinotic.structures.tests.core.structure;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.structures.api.config.StructuresProperties;
import org.kinotic.structures.sql.executor.IndexShardSettings;

/**
 * Checks that shard and replica counts Elasticsearch would refuse are rejected when the properties are bound, so a bad
 * value stops the server from starting instead of failing every publish.
 */
public class IndexShardSettingsValidationTest {

    @Test
    public void testDefaultsAreThreeShardsAndTwoReplicas() {
        StructuresProperties properties = new StructuresProperties();

        Assertions.assertEquals(3, properties.getElasticNumberOfShards());
        Assertions.assertEquals(2, properties.getElasticNumberOfReplicas());
    }

    @Test
    public void testNumberOfShardsMustBeBetweenOneAndTheElasticsearchLimit() {
        StructuresProperties properties = new StructuresProperties();

        Assertions.assertThrows(IllegalArgumentException.class, () -> properties.setElasticNumberOfShards(0));
        Assertions.assertThrows(IllegalArgumentException.class,
                                () -> properties.setElasticNumberOfShards(IndexShardSettings.MAX_NUMBER_OF_SHARDS + 1));

        properties.setElasticNumberOfShards(1);
        Assertions.assertEquals(1, properties.getElasticNumberOfShards());
        properties.setElasticNumberOfShards(IndexShardSettings.MAX_NUMBER_OF_SHARDS);
        Assertions.assertEquals(IndexShardSettings.MAX_NUMBER_OF_SHARDS, properties.getElasticNumberOfShards());
    }

    @Test
    public void testNumberOfReplicasMustNotBeNegative() {
        StructuresProperties properties = new StructuresProperties();

        Assertions.assertThrows(IllegalArgumentException.class, () -> properties.setElasticNumberOfReplicas(-1));

        properties.setElasticNumberOfReplicas(0);
        Assertions.assertEquals(0, properties.getElasticNumberOfReplicas());
    }
}
