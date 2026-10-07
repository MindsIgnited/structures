package org.kinotic.structures.tests.core.entity;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "structures.elastic-refresh-after-mutation=false")
public class EntityRefreshAfterMutationDisabledTests extends AbstractEntityRefreshTests {
}
