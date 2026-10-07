package org.kinotic.structures.tests.core.entity;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs every {@link BulkUpdateTests} test with single saves, updates and deletes waiting for the next scheduled refresh
 * instead of forcing one.
 */
@SpringBootTest(properties = {"structures.elastic-refresh-after-mutation=wait_for",
                              "structures.elastic-refresh-after-delete=wait_for"})
public class BulkUpdateWaitForTests extends BulkUpdateTests {

    @Override
    protected String structureSuffix(String suffix) {
        return suffix + "_waitFor";
    }
}
