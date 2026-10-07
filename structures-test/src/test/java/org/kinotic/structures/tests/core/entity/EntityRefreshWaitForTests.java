package org.kinotic.structures.tests.core.entity;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"structures.elastic-refresh-after-mutation=wait_for",
                              "structures.elastic-refresh-after-delete=wait_for"})
public class EntityRefreshWaitForTests extends AbstractEntityRefreshTests {
}
