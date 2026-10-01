module com.guicedee.activitymaster.forums {
    requires transitive com.guicedee.activitymaster.fsdm;
    requires com.guicedee.rest;
    requires com.guicedee.activitymaster.notifications;
    requires com.guicedee.vertx.graphql;
    exports com.guicedee.activitymaster.forums;
    exports com.guicedee.activitymaster.forums.rest;
    exports com.guicedee.activitymaster.forums.graphql;
    opens com.guicedee.activitymaster.forums.graphql to com.google.guice;
    provides com.guicedee.vertx.graphql.services.IGraphQLSchemaProvider
        with com.guicedee.activitymaster.forums.graphql.ForumGraphQLSchemaProvider;
    opens com.guicedee.activitymaster.forums to com.google.guice, tools.jackson.databind;
    opens com.guicedee.activitymaster.forums.rest to com.google.guice, com.guicedee.rest, tools.jackson.databind;
    provides com.guicedee.client.services.lifecycle.IGuiceModule with com.guicedee.activitymaster.forums.ForumModule;
    provides com.guicedee.client.services.config.IGuiceScanModuleInclusions with com.guicedee.activitymaster.forums.ForumInclusionModule;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem with com.guicedee.activitymaster.forums.ForumSystem;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate with com.guicedee.activitymaster.forums.ForumInstall;
}
