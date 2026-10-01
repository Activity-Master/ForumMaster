package com.guicedee.activitymaster.forums;

import com.guicedee.activitymaster.fsdm.transactions.ActivityScope;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Immutable service and transport values. Identity and context are supplied by the host. */
public final class ForumModels {
    private ForumModels() { }

    public record Create(ActivityScope.Realm realm, UUID ownerId, String title, List<UUID> subscribers) {
        public Create {
            subscribers = subscribers == null ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(subscribers));
        }
    }
    public record Forum(UUID id, ActivityScope.Realm realm, UUID ownerId, String title, List<UUID> subscribers) {
        public Forum { subscribers = List.copyOf(subscribers); }
    }
    public record PublishPost(String text) { }
    public record Post(UUID id, UUID forumId, UUID posterId, String text, OffsetDateTime createdAt) { }
    /** The complete desired audience; the forum creator remains subscribed. */
    public record UpdateSubscribers(List<UUID> subscribers) {
        public UpdateSubscribers {
            subscribers = subscribers == null ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(subscribers));
        }
    }
    public record Page<T>(List<T> items, int offset, int limit, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
}
