package com.guicedee.activitymaster.forums;

import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts;
import com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate;
import com.guicedee.activitymaster.fsdm.client.services.systems.SortedUpdate;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;
import static com.guicedee.activitymaster.fsdm.client.services.IActivityMasterService.*;

/** Installs only FSDM taxonomy; forum content is never imported at startup. */
@SortedUpdate(sortOrder = 1180, taskCount = 1)
public final class ForumInstall implements ISystemUpdate {
    @Override public Uni<Boolean> update(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return getISystem(session, ForumSystem.NAME, enterprise)
                .chain(system -> getISystemToken(session, ForumSystem.NAME, enterprise)
                        .chain(token -> install(session, system, token).replaceWith(true)));
    }

    private Uni<Void> install(Mutiny.StatelessSession session, ISystems<?, ?> system, UUID token) {
        IArrangementsService<?> arrangements = IGuiceContext.get(IArrangementsService.class);
        IEventService<?> events = IGuiceContext.get(IEventService.class);
        IResourceItemService<?> resources = IGuiceContext.get(IResourceItemService.class);
        IClassificationService<?> classes = IGuiceContext.get(IClassificationService.class);
        return arrangements.createArrangementType(session, "Forum", system, token)
                .chain(() -> events.createEventType(session, "Forum Post", system, token))
                .chain(() -> resources.createType(session, "Forum Post Body", "Private forum text", system, token))
                .chain(() -> role(session, classes, system, token, "ForumType", EnterpriseClassificationDataConcepts.ArrangementXArrangementType))
                .chain(() -> role(session, classes, system, token, "ForumContext", EnterpriseClassificationDataConcepts.ArrangementXClassification))
                .chain(() -> role(session, classes, system, token, "ForumTitle", EnterpriseClassificationDataConcepts.ArrangementXClassification))
                .chain(() -> role(session, classes, system, token, "ForumModerator", EnterpriseClassificationDataConcepts.ArrangementXInvolvedParty))
                .chain(() -> role(session, classes, system, token, "ForumSubscriber", EnterpriseClassificationDataConcepts.ArrangementXInvolvedParty))
                .chain(() -> role(session, classes, system, token, "ForumPostType", EnterpriseClassificationDataConcepts.EventXEventType))
                .chain(() -> role(session, classes, system, token, "ForumPost", EnterpriseClassificationDataConcepts.EventXArrangement))
                .chain(() -> role(session, classes, system, token, "ForumPoster", EnterpriseClassificationDataConcepts.EventXInvolvedParty))
                .chain(() -> role(session, classes, system, token, "ForumBodyType", EnterpriseClassificationDataConcepts.ResourceItemXResourceItemType))
                .chain(() -> role(session, classes, system, token, "ForumPostBody", EnterpriseClassificationDataConcepts.EventXResourceItem))
                .replaceWithVoid();
    }

    private Uni<?> role(Mutiny.StatelessSession session, IClassificationService<?> classes, ISystems<?, ?> system,
                        UUID token, String name, EnterpriseClassificationDataConcepts concept) {
        return classes.create(session, name, name, concept, system, token);
    }
}
