package org.kinotic.structures.internal.api.hooks.impl;

import org.kinotic.structures.api.domain.EntityContext;
import org.kinotic.structures.api.domain.Structure;
import org.kinotic.structures.api.domain.idl.decorators.TenantIdDecorator;
import org.kinotic.structures.internal.api.hooks.UpsertFieldPreProcessor;
import org.springframework.stereotype.Component;

/**
 * Resolves the value of a {@link TenantIdDecorator} field on save. A participant that belongs to a tenant
 * owns the field: an unset value is filled with that tenant and a different value is rejected. A participant
 * that has no tenant of its own names the tenant of each entity through the data.
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
        String participantTenantId = context.getParticipant() != null ? context.getParticipant().getTenantId() : null;
        if(participantTenantId == null){
            ret = fieldValue;
        }else if(fieldValue == null || fieldValue.isBlank()){
            ret = participantTenantId;
        }else if(fieldValue.equals(participantTenantId)){
            ret = fieldValue;
        }else{
            throw new IllegalArgumentException("Tenant Id invalid for logged in participant");
        }
        return ret;
    }
}
