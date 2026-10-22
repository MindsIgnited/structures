package org.kinotic.structures.tests.core.structure;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.internal.api.services.impl.CrudServiceTemplate;
import org.kinotic.structures.support.elastic.ElasticTestBase;
import org.kinotic.structures.tests.core.support.TestHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.IndexSettings;

/**
 * Checks that a published structure's index, and the index template behind a stream structure, get the shards and
 * replicas configured by elasticNumberOfShards and elasticNumberOfReplicas rather than the defaults.
 */
@SpringBootTest(properties = {
        "structures.elastic-number-of-shards=2",
        "structures.elastic-number-of-replicas=0"
})
public class StructureIndexSettingsTests extends ElasticTestBase {

    @Autowired
    private ElasticsearchClient client;
    @Autowired
    private CrudServiceTemplate crudServiceTemplate;
    @Autowired
    private TestHelper testHelper;

    @Test
    public void testPublishedStructureIndexUsesConfiguredShardsAndReplicas() throws IOException {
        Structure structure = testHelper.createAndVerify().getStructure();
        String indexName = structure.getItemIndex();

        IndexSettings settings = client.indices()
                                       .getSettings(b -> b.index(indexName))
                                       .get(indexName)
                                       .settings()
                                       .index();

        Assertions.assertEquals("2", settings.numberOfShards());
        Assertions.assertEquals("0", settings.numberOfReplicas());
    }

    @Test
    public void testIndexTemplateUsesConfiguredShardsAndReplicas() throws IOException {
        String templateName = "struct_index_settings_test_" + System.currentTimeMillis() + "_tpl";
        crudServiceTemplate.createIndexTemplate(templateName, templateName + "*", null, null).join();
        try {
            IndexSettings settings = client.indices()
                                           .getIndexTemplate(b -> b.name(templateName))
                                           .indexTemplates()
                                           .getFirst()
                                           .indexTemplate()
                                           .template()
                                           .settings()
                                           .index();

            Assertions.assertEquals("2", settings.numberOfShards());
            Assertions.assertEquals("0", settings.numberOfReplicas());
        } finally {
            client.indices().deleteIndexTemplate(b -> b.name(templateName));
        }
    }
}
