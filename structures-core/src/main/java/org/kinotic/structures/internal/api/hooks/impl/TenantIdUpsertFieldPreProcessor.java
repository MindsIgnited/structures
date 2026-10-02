package org.kinotic.structures.internal.api.hooks.impl;

import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.TenantIdDecorator;
import org.kinotic.structures.internal.api.hooks.UpsertFieldPreProcessor;
import org.springframework.stereotype.Component;

/**
 * Resolves the value of a {@link TenantIdDecorator} field on save. The data names the tenant the entity belongs
 * to, and a value that is not set is filled with the tenant of the participant performing the save.
 * Created by Navíd Mitchell 🤪 on 5/9/23.
 */
@Component
public class TenantIdUpsertFieldPreProcessor implements UpsertFieldPreProcessor<TenantIdDecorator, String, String> {

    @Override
    public Class<TenantIdDecorator> implementsDecorator() {
        return TenantIdDecorator.class;
    }

    @Override
    public Class<String> supportsFieldType() {
        return String.class;
    }

    @Override
    public String process(Structure structure, String fieldName, TenantIdDecorator decorator, String fieldValue, EntityContext context) {
        String ret;
        if(fieldValue == null || fieldValue.isBlank()){
            // clients default the field to an empty string, so a blank value means the entity is the participant's own
            ret = context.getParticipant() != null ? context.getParticipant().getTenantId() : null;
        }else{
            ret = fieldValue;
        }
        return ret;
    }
}
