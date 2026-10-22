package org.kinotic.structures.api.domain;

import java.util.List;

/**
 * Holds information for all "Entity" related operations.
 * Created by Navíd Mitchell 🤪 on 6/7/23.
 */
public interface EntityContext extends SecurityContext {

    /**
     * The tenant selection entry that selects every tenant of the {@link Structure}.
     */
    String ALL_TENANTS = "*";

    /**
     * If defined, this will restrict the response to only include the fields listed here.
     * @return a list of included fields, if {@link List} is empty no fields will be included, if null all fields will be included.
     */
    List<String> getIncludedFieldsFilter();

    /**
     * Returns whether there is an included fields filter defined.
     * @return true if an included fields filter is defined, false otherwise
     */
    boolean hasIncludedFieldsFilter();

    /**
     * Checks if the tenant selection names every tenant, see {@link #ALL_TENANTS}
     * @return true if the operation spans every tenant, false otherwise
     */
    default boolean selectsAllTenants() {
        return hasTenantSelection() && getTenantSelection().contains(ALL_TENANTS);
    }

}
