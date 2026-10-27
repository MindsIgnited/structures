package org.kinotic.structures.sql.executor;

/**
 * The number of shards and replicas Structures gives the indexes it creates: those created by CREATE TABLE in system
 * and project migrations, and those created when a structure is published. Implemented by the configuration of each
 * application that runs migrations, so the server and the migration job read the same settings.
 * Elasticsearch fixes the number of shards when an index is created, so either value only affects indexes created
 * afterwards.
 */
public interface IndexShardSettings {

    int DEFAULT_NUMBER_OF_SHARDS = 3;

    int DEFAULT_NUMBER_OF_REPLICAS = 2;

    /**
     * The most shards Elasticsearch allows an index by default (the {@code es.index.max_number_of_shards} system
     * property).
     */
    int MAX_NUMBER_OF_SHARDS = 1024;

    int getElasticNumberOfShards();

    int getElasticNumberOfReplicas();

    /**
     * @param numberOfShards the number of primary shards to check
     * @return the given number, if it is between 1 and {@link #MAX_NUMBER_OF_SHARDS}
     * @throws IllegalArgumentException if it is not
     */
    static int validateNumberOfShards(int numberOfShards) {
        if (numberOfShards < 1 || numberOfShards > MAX_NUMBER_OF_SHARDS) {
            throw new IllegalArgumentException("elasticNumberOfShards must be between 1 and " + MAX_NUMBER_OF_SHARDS
                                                       + ", but was " + numberOfShards);
        }
        return numberOfShards;
    }

    /**
     * @param numberOfReplicas the number of replicas to check
     * @return the given number, if it is not negative
     * @throws IllegalArgumentException if it is negative
     */
    static int validateNumberOfReplicas(int numberOfReplicas) {
        if (numberOfReplicas < 0) {
            throw new IllegalArgumentException("elasticNumberOfReplicas must not be negative, but was "
                                                       + numberOfReplicas);
        }
        return numberOfReplicas;
    }
}
