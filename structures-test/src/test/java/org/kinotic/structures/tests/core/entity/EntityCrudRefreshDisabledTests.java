package org.kinotic.structures.tests.core.entity;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs every {@link EntityCrudTests} test with Structures no longer forcing a refresh after single saves, updates and deletes.
 */
@SpringBootTest(properties = {"structures.elastic-refresh-after-mutation=false",
                              "structures.elastic-refresh-after-delete=false"})
public class EntityCrudRefreshDisabledTests extends EntityCrudTests {

    @Override
    protected String structureSuffix(String suffix) {
        return suffix + "_refreshOff";
    }
}
