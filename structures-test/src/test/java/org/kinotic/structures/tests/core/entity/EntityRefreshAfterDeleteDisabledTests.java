package org.kinotic.structures.tests.core.entity;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "structures.elastic-refresh-after-delete=false")
public class EntityRefreshAfterDeleteDisabledTests extends AbstractEntityRefreshTests {

    @Override
    protected boolean refreshesAfterMutation() {
        return true;
    }

    @Override
    protected boolean refreshesAfterDelete() {
        return false;
    }
}
