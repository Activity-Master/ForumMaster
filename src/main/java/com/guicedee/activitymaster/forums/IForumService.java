package com.guicedee.activitymaster.forums;

import com.guicedee.activitymaster.forums.ForumModels.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;

/** Caller owns the stateless session and transaction; actor identity comes from the host. */
public interface IForumService {
    Uni<Forum> create(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, Create request);
    Uni<Forum> find(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, UUID id);
    Uni<Page<Forum>> list(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, int offset, int limit);
    Uni<Post> post(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, UUID id, PublishPost request);
    Uni<Page<Post>> posts(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, UUID id, int offset, int limit);
    Uni<Forum> addSubscriber(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, UUID id, UUID partyId);
    Uni<Forum> removeSubscriber(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, UUID id, UUID partyId);
    Uni<Forum> updateSubscribers(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, UUID id, UpdateSubscribers request);
    Uni<Void> leave(Mutiny.StatelessSession session, ISystems<?, ?> system, ForumIdentity identity, UUID id);
}
