package com.guicedee.activitymaster.forums;

import com.google.inject.ImplementedBy;
import io.smallrye.mutiny.Uni;

/** The consuming host binds this to its authenticated, call-scoped identity. */
@ImplementedBy(ForumIdentityProvider.Deny.class)
public interface ForumIdentityProvider {
    Uni<ForumIdentity> current();

    final class Deny implements ForumIdentityProvider {
        @Override public Uni<ForumIdentity> current() {
            return Uni.createFrom().failure(new SecurityException("Authenticated forum identity required"));
        }
    }
}
