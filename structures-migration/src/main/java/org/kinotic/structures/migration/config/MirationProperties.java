package org.kinotic.structures.migration.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.kinotic.structures.sql.executor.IndexShardSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Properties to configure migration module.
 * Created By Navíd Mitchell 🤪on 1/31/26
 */
@Getter
@Setter
@Accessors(chain = true)
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "structures-migration")
@Validated
public class MirationProperties implements IndexShardSettings {

    @NotBlank
    private String elasticHost;

    @NotNull
    private Integer elasticPort;

    @NotBlank
    private String elasticScheme;

    private String elasticUsername = null;

    private String elasticPassword = null;

    /**
     * The number of primary shards given to the system indexes created by migrations. It affects only indexes created
     * afterwards, so set it to the same value as the server's {@code structures.elasticNumberOfShards}.
     */
    private int elasticNumberOfShards = DEFAULT_NUMBER_OF_SHARDS;

    /**
     * The number of replicas of each primary shard given to the system indexes created by migrations. Set it to the same
     * value as the server's {@code structures.elasticNumberOfReplicas}.
     */
    private int elasticNumberOfReplicas = DEFAULT_NUMBER_OF_REPLICAS;

    public MirationProperties setElasticNumberOfShards(int elasticNumberOfShards) {
        this.elasticNumberOfShards = IndexShardSettings.validateNumberOfShards(elasticNumberOfShards);
        return this;
    }

    public MirationProperties setElasticNumberOfReplicas(int elasticNumberOfReplicas) {
        this.elasticNumberOfReplicas = IndexShardSettings.validateNumberOfReplicas(elasticNumberOfReplicas);
        return this;
    }

    public boolean hasElasticUsernameAndPassword(){
        return elasticUsername != null && !elasticUsername.isBlank() && elasticPassword != null && !elasticPassword.isBlank();
    }

}
