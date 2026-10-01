package com.guicedee.activitymaster.forums;

import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import java.util.Objects;
import java.util.UUID;

/** A host-resolved actor and FSDM context. This is never request body data. */
public record ForumIdentity(UUID partyId, UUID enterpriseId, ActivityScope.Context context, UUID identityToken) {
    public ForumIdentity {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(enterpriseId, "enterpriseId");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(identityToken, "identityToken");
        if (context.realm() == ActivityScope.Realm.WORK && !enterpriseId.equals(context.ownerId()))
            throw new SecurityException("Work forums require the authorized enterprise context");
        if (context.realm() != ActivityScope.Realm.WORK && !partyId.equals(context.ownerId()))
            throw new SecurityException("Personal and social forums require the verified party context");
    }
    public UUID[] tokens() { return new UUID[]{identityToken}; }
}
